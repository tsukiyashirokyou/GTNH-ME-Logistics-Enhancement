package com.kyou.gtnhmelogisticsenhancement.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * Deterministic component counts with a zero-cost virtual clock and bounded counting inventories. These are neither
 * Minecraft/handler benchmarks nor measured Tick, MSPT, NBT-save or network-notification timings. Both policies use the
 * same fixture; only the legacy quantity ceiling and per-slice transaction allowance differ.
 */
public class TransferThroughputTest {

    @Test
    public void singleItemTypeReportsWholeQuantityAndLegacyBatchCounts() {
        for (final long amount : new long[] { 4096, 1_000_000, 10_000_000, 100_000_000,
            TransferEnergy.MAX_AMOUNT + 1000 }) {
            final Fixture old = single(Kind.ITEM, amount, true);
            final Fixture current = single(Kind.ITEM, amount, false);
            old.drain();
            current.drain();
            final long oldAttempts = divideUp(amount, Kind.ITEM.legacyBatch);
            final long newAttempts = divideUp(amount, TransferEnergy.MAX_AMOUNT);
            assertEquals(oldAttempts, old.attempts);
            assertEquals(oldAttempts, old.workTicks);
            assertEquals(newAttempts, current.attempts);
            assertEquals(divideUp(newAttempts, TransferScheduler.TRANSACTIONS), current.workTicks);
            assertEquals(oldAttempts, old.extractions());
            assertEquals(newAttempts, current.extractions());
            assertEquals(1, old.snapshots);
            assertEquals(1, current.snapshots);
            assertDrained(old, Kind.ITEM, amount);
            assertDrained(current, Kind.ITEM, amount);
            assertEquals((double) amount, old.power.spent, 0);
            assertEquals((double) amount, current.power.spent, 0);
            report("single-item-" + amount, old, current);
        }
    }

    @Test
    public void sixtyThreeSmallTypesRequireSixtyThreeTransactionsButFewerWorkTicks() {
        final Fixture old = new Fixture(Arrays.asList(cell(0, Kind.ITEM, 63, 100)), true);
        final Fixture current = new Fixture(Arrays.asList(cell(0, Kind.ITEM, 63, 100)), false);
        old.drain();
        current.drain();
        assertEquals(63, old.attempts);
        assertEquals(63, current.attempts);
        assertEquals(63, old.workTicks);
        assertEquals(32, current.workTicks);
        assertEquals(63, old.probes);
        assertEquals(63, current.probes);
        assertEquals(1, current.snapshots);
        assertDrained(old, Kind.ITEM, 6300);
        assertDrained(current, Kind.ITEM, 6300);
        report("one-cell-63-small-types", old, current);
    }

    @Test
    public void sixtyFourMixedCellsKeepNativeUnitsAndBoundSnapshotProgress() {
        final List<Cell> oldCells = mixedCells(63);
        final List<Cell> newCells = mixedCells(63);
        final Fixture old = new Fixture(oldCells, true);
        final Fixture current = new Fixture(newCells, false);
        old.drain();
        current.drain();
        // 22 item cells, 21 fluid cells, 21 essentia cells; each has 63 distinct identities.
        assertDrained(old, Kind.ITEM, 22L * 63 * 100);
        assertDrained(current, Kind.ITEM, 22L * 63 * 100);
        assertDrained(old, Kind.FLUID, 21L * 63 * 100_000);
        assertDrained(current, Kind.FLUID, 21L * 63 * 100_000);
        assertDrained(old, Kind.ESSENTIA, 21L * 63 * 100);
        assertDrained(current, Kind.ESSENTIA, 21L * 63 * 100);
        assertEquals(64, old.snapshots);
        assertEquals(64, current.snapshots);
        assertEquals(64 * 63, current.probes);
        assertEquals(64 * 63, current.attempts);
        assertEquals((22L + 21 * 2 + 21) * 63, old.attempts);
        assertEquals(old.attempts, old.workTicks);
        assertEquals(divideUp(current.attempts, 2), current.workTicks);
        assertEquals(64L * 63 * 100, (long) current.power.spent);
        assertEquals(old.power.spent, current.power.spent, 0);
        report("64-cells-mixed-63-types", old, current);

        final Fixture sparse = new Fixture(mixedCells(1), false);
        sparse.drain();
        assertEquals(64, sparse.attempts);
        assertEquals(64, sparse.snapshots);
        // Two transactions do not permit opening two new cells in the same slice.
        assertEquals(64, sparse.workTicks);
    }

    @Test
    public void partialCapacityUsesOnlyActualRemainderWithTwoTargetsInOneWorkTick() {
        final Fixture old = single(Kind.ITEM, 1_000_000, true);
        final Fixture current = single(Kind.ITEM, 1_000_000, false);
        old.first().destinations[0].capacity = 100_000;
        current.first().destinations[0].capacity = 100_000;
        old.drain();
        current.drain();
        assertEquals(100_000, current.first().destinations[0].stored);
        assertEquals(900_000, current.first().destinations[1].stored);
        assertEquals(2, current.attempts);
        assertEquals(1, current.workTicks);
        assertDrained(old, Kind.ITEM, 1_000_000);
        assertDrained(current, Kind.ITEM, 1_000_000);
        report("partial-capacity", old, current);
    }

    @Test
    public void limitedEnergyShrinksAuthorizedQuantityWithoutTryingLowerTargets() {
        final Fixture old = single(Kind.ITEM, 1_000_000, true);
        final Fixture current = single(Kind.ITEM, 1_000_000, false);
        old.power.available = 250_000;
        current.power.available = 250_000;
        old.drain();
        current.drain();
        assertEquals(250_000, old.delivered(Kind.ITEM));
        assertEquals(250_000, current.delivered(Kind.ITEM));
        assertEquals(750_000, current.first().count);
        assertEquals(0, current.first().destinations[1].insertions);
        assertEquals(2, current.attempts);
        assertEquals(1, current.extractions());
        assertEquals(1, current.workTicks);
        assertEquals(250_000, current.power.spent, 0);
        assertEquals(old.power.spent, current.power.spent, 0);
        assertFalse(current.transaction.isPending());
        report("limited-energy", old, current);

        final Fixture fluid = single(Kind.FLUID, 640_000, false);
        fluid.power.available = 500.5;
        fluid.drain();
        assertEquals(500_500, fluid.delivered(Kind.FLUID));
        assertEquals(139_500, fluid.first().count);
        assertEquals(500.5, fluid.power.spent, 0);
        assertEquals(1, fluid.extractions());
        assertEquals(0, fluid.first().destinations[1].insertions);
    }

    @Test
    public void repeatedlyInvalidTopologyStopsEachAttemptBeforeExtraction() {
        long attempts = 0;
        long extractions = 0;
        for (int tick = 0; tick < 20; tick++) {
            final Fixture current = single(Kind.ESSENTIA, 1_000_000, false);
            current.invalidateBeforeAttempt = true;
            current.drain();
            attempts += current.attempts;
            extractions += current.extractions();
            assertEquals(1, current.topologyStops);
            assertEquals(1_000_000, current.first().count);
            assertEquals(0, current.delivered(Kind.ESSENTIA));
            assertEquals(0, current.power.spent, 0);
            assertFalse(current.transaction.isPending());
        }
        assertEquals(20, attempts);
        assertEquals(0, extractions);
    }

    private static Fixture single(final Kind kind, final long amount, final boolean legacy) {
        return new Fixture(Arrays.asList(cell(0, kind, 1, amount)), legacy);
    }

    private static Cell cell(final int slot, final Kind kind, final int types, final long amount) {
        final Cell cell = new Cell();
        for (int type = 0; type < types; type++) {
            cell.contents.add(new Content(kind, kind.name() + ":" + slot + ":" + type, amount));
        }
        return cell;
    }

    private static List<Cell> mixedCells(final int types) {
        final List<Cell> cells = new ArrayList<>();
        for (int slot = 0; slot < 64; slot++) {
            final Kind kind = Kind.values()[slot % 3];
            cells.add(cell(slot, kind, types, 100L * kind.unit));
        }
        return cells;
    }

    private static long divideUp(final long amount, final long unit) {
        return amount / unit + (amount % unit == 0 ? 0 : 1);
    }

    private static void assertDrained(final Fixture fixture, final Kind kind, final long expected) {
        assertEquals(expected, fixture.delivered(kind));
        for (final Cell cell : fixture.cells) {
            for (final Content content : cell.contents) {
                if (content.kind == kind) {
                    assertEquals(0, content.count);
                }
            }
        }
        assertFalse(fixture.transaction.isPending());
    }

    private static void report(final String scenario, final Fixture old, final Fixture current) {
        System.out.println(
            String.format(
                Locale.ROOT,
                "DETERMINISTIC_COMPONENT_COUNTS %s: legacy attempts=%d workTicks=%d; current attempts=%d workTicks=%d"
                    + "; snapshots=%d probes=%d; zero-cost virtual calls, no Minecraft/MSPT measurement",
                scenario,
                old.attempts,
                old.workTicks,
                current.attempts,
                current.workTicks,
                current.snapshots,
                current.probes));
    }

    private enum Kind {

        ITEM(1, 4096),
        FLUID(1000, 64000),
        ESSENTIA(1, 4096);

        final int unit;
        final long legacyBatch;

        Kind(final int unit, final long legacyBatch) {
            this.unit = unit;
            this.legacyBatch = legacyBatch;
        }
    }

    private static final class Cell {

        final List<Content> contents = new ArrayList<>();
        long[] snapshot;
    }

    private static final class Content implements TransferTransaction.Source {

        final Kind kind;
        final String identity;
        final Receiver[] destinations = { new Receiver(), new Receiver() };
        long count;
        long extractions;

        Content(final Kind kind, final String identity, final long count) {
            this.kind = kind;
            this.identity = identity;
            this.count = count;
        }

        @Override
        public long extract(final long amount) {
            this.extractions++;
            final long extracted = Math.min(amount, this.count);
            this.count -= extracted;
            return extracted;
        }

        @Override
        public long restore(final long amount) {
            this.count = Math.addExact(this.count, amount);
            return amount;
        }
    }

    private static final class Receiver implements TransferTransaction.Destination {

        long capacity = Long.MAX_VALUE;
        long stored;
        long insertions;

        @Override
        public long simulate(final long amount) {
            return Math.min(amount, this.capacity - this.stored);
        }

        @Override
        public long insert(final long amount) {
            this.insertions++;
            final long received = this.simulate(amount);
            this.stored = Math.addExact(this.stored, received);
            return received;
        }
    }

    private static final class Power implements TransferEnergy.Source {

        double available = Double.POSITIVE_INFINITY;
        double spent;

        @Override
        public double extract(final double amount, final boolean simulate) {
            final double provided = Math.min(amount, this.available);
            if (!simulate) {
                this.available -= provided;
                this.spent += provided;
            }
            return provided;
        }
    }

    /** Models cursor preparation explicitly; it does not call or stand in for AETransferController's world adapter. */
    private static final class Fixture implements TransferScheduler.Work {

        final List<Cell> cells;
        final TransferScheduler scheduler = new TransferScheduler(() -> 0);
        final TransferTransaction<String> transaction = new TransferTransaction<>(() -> {});
        final Power power = new Power();
        final Object firstGrid = new Object();
        final Object secondGrid = new Object();
        final AtomicReference<Object> sourceGrid = new AtomicReference<>(new Object());
        final RoutingCycle<Object> cycle;
        final boolean legacy;
        int cellIndex;
        int typeIndex;
        boolean opened;
        boolean finished;
        boolean invalidateBeforeAttempt;
        RoutedTransfer<String, Object> route;
        Content current;
        long attempts;
        long workTicks;
        int snapshots;
        int probes;
        int topologyStops;

        Fixture(final List<Cell> cells, final boolean legacy) {
            this.cells = cells;
            this.legacy = legacy;
            final TargetRegistry registry = new TargetRegistry();
            registry.register(new TransferTarget(new UUID(0, 1), 0, 0, 64, 0));
            registry.register(new TransferTarget(new UUID(0, 2), 0, 1, 64, 0));
            for (int group = 0; group < 8; group++) {
                registry.setRoute(group, TargetRegistry.Route.automatic());
            }
            this.cycle = new RoutingCycle<>(registry, this.sourceGrid::get, target -> {
                final Object grid = target.getX() == 0 ? this.firstGrid : this.secondGrid;
                return new RoutingCycle.Endpoint<>(grid, () -> grid, () -> true);
            });
            this.cycle.prepare(4);
        }

        void drain() {
            this.scheduler.submit(this);
            while (!this.finished && this.workTicks < 1_000_000) {
                this.scheduler.runTick();
                this.workTicks++;
            }
            assertTrue("Bounded fixture failed to finish", this.finished);
        }

        @Override
        public boolean run(final TransferScheduler.Slice budget) {
            int transactions = 0;
            while (transactions < (this.legacy ? 1 : TransferScheduler.TRANSACTIONS) && budget.nextStep()) {
                final Cell cell = this.cells.get(this.cellIndex);
                if (!this.opened) {
                    if (!budget.openCell()) {
                        break;
                    }
                    this.snapshots++;
                    cell.snapshot = new long[cell.contents.size()];
                    for (int type = 0; type < cell.snapshot.length; type++) {
                        cell.snapshot[type] = cell.contents.get(type).count;
                    }
                    this.opened = true;
                    continue;
                }
                if (this.route == null) {
                    if (!budget.probeType()) {
                        break;
                    }
                    this.probes++;
                    this.current = cell.contents.get(this.typeIndex);
                    this.route = new RoutedTransfer<>(
                        this.current.identity,
                        cell.snapshot[this.typeIndex],
                        this.cycle,
                        this.cellIndex / 8);
                    continue;
                }
                this.route.limitRemaining(this.current.count);
                if (!this.route.isFinished()) {
                    if (!budget.transaction()) {
                        break;
                    }
                    this.attempts++;
                    transactions++;
                    if (this.invalidateBeforeAttempt) {
                        this.sourceGrid.set(new Object());
                    }
                    this.route.step(
                        this.legacy ? this.current.kind.legacyBatch : TransferEnergy.MAX_AMOUNT,
                        this.transaction,
                        this.current,
                        target -> this.current.destinations[target.getGrid() == this.firstGrid ? 0 : 1],
                        amount -> TransferEnergy.reserve(amount, this.current.kind.unit, 1, this.power));
                    if (!this.cycle.isTopologyValid()) {
                        this.topologyStops++;
                        this.finished = true;
                        return false;
                    }
                    if (this.transaction.isPending()) {
                        throw new AssertionError("An ordinary counting receiver left a pending transaction");
                    }
                }
                if (this.route.isFinished()) {
                    this.route = null;
                    this.current = null;
                    this.typeIndex++;
                    if (this.typeIndex == cell.contents.size()) {
                        this.cellIndex++;
                        this.typeIndex = 0;
                        this.opened = false;
                        if (this.cellIndex == this.cells.size()) {
                            this.finished = true;
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        Content first() {
            return this.cells.get(0).contents.get(0);
        }

        long extractions() {
            long count = 0;
            for (final Cell cell : this.cells) {
                for (final Content content : cell.contents) {
                    count += content.extractions;
                }
            }
            return count;
        }

        long delivered(final Kind kind) {
            long count = 0;
            for (final Cell cell : this.cells) {
                for (final Content content : cell.contents) {
                    if (content.kind == kind) {
                        for (final Receiver receiver : content.destinations) {
                            count = Math.addExact(count, receiver.stored);
                        }
                    }
                }
            }
            return count;
        }
    }
}
