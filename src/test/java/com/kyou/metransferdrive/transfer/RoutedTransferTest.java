package com.kyou.metransferdrive.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class RoutedTransferTest {

    private final Object sourceGrid = new Object();
    private final Object firstGrid = new Object();
    private final Object secondGrid = new Object();
    private final TransferTransaction<String> transaction = new TransferTransaction<>(() -> {});
    private final Source source = new Source();
    private final Destination first = new Destination();
    private final Destination second = new Destination();
    private final Map<Object, Destination> inventories = new IdentityHashMap<>();

    public RoutedTransferTest() {
        this.inventories.put(this.firstGrid, this.first);
        this.inventories.put(this.secondGrid, this.second);
    }

    @Test
    public void partialActualInsertionFallsBackOnlyWithTheKnownRemainingAmount() {
        this.first.actualCapacity = 30;
        final RoutedTransfer<String, Object> route = this.route(100, false);
        assertEquals(30, this.step(route, 100));
        assertEquals(70, this.source.count);
        assertFalse(this.transaction.isPending());
        assertEquals(70, route.getRemaining());
        assertEquals(70, this.step(route, 100));
        assertEquals(30, this.first.stored);
        assertEquals(70, this.second.stored);
        assertEquals(0, this.source.count);
        assertTrue(route.isFinished());
        assertEquals(0, this.step(route, 100));
        assertEquals(1, this.second.insertions);
    }

    @Test
    public void rejectionAndOfflineDestinationsDoNotExtractBeforeTryingTheNextGrid() {
        this.first.simulatedCapacity = 0;
        final RoutedTransfer<String, Object> route = this.route(100, false);
        assertEquals(0, this.step(route, 100));
        assertEquals(0, this.source.extractions);
        assertEquals(100, this.step(route, 100));
        assertEquals(1, this.source.extractions);
    }

    @Test
    public void capacityDisappearingAfterSimulationIsReturnedBeforeFailover() {
        this.first.actualCapacity = 0;
        final RoutedTransfer<String, Object> route = this.route(100, false);
        assertEquals(0, this.step(route, 100));
        assertEquals(100, this.source.count);
        assertFalse(this.transaction.isPending());
        assertEquals(100, this.step(route, 100));
        assertEquals(100, this.second.stored);
    }

    @Test
    public void changedExtractionQuantityNeverClaimsOrDuplicatesTheUnextractedPart() {
        this.source.extractLimit = 17;
        final RoutedTransfer<String, Object> route = this.route(100, false);
        assertEquals(17, this.step(route, 100));
        assertEquals(83, route.getRemaining());
        this.source.extractLimit = 100;
        assertEquals(83, this.step(route, 100));
        assertEquals(100, this.first.stored);
        assertEquals(0, this.second.stored);
    }

    @Test
    public void energyLimitationDoesNotDemoteAnOtherwiseWritableHighPriorityTarget() {
        final RoutedTransfer<String, Object> route = this.route(100, false);
        assertEquals(
            25,
            route.step(
                100,
                this.transaction,
                this.source,
                target -> this.inventories.get(target.getGrid()),
                amount -> Math.min(25, amount)));
        assertEquals(75, this.step(route, 100));
        assertEquals(100, this.first.stored);
        assertEquals(0, this.second.insertions);
    }

    @Test
    public void noEnergyEndsTheCandidateWithoutProbingEveryLowerPriorityNetwork() {
        final RoutedTransfer<String, Object> route = this.route(100, false);
        assertEquals(
            0,
            route.step(
                100,
                this.transaction,
                this.source,
                target -> this.inventories.get(target.getGrid()),
                amount -> 0));
        assertTrue(route.isFinished());
        assertEquals(0, this.step(route, 100));
        assertEquals(0, this.second.insertions);
        assertEquals(0, this.source.extractions);
    }

    @Test
    public void partialEnergyDoesNotTreatAllSimulatedReceiverCapacityAsConsumed() {
        this.first.simulatedCapacity = 60;
        this.first.actualCapacity = 60;
        final RoutedTransfer<String, Object> route = this.route(100, false);
        assertEquals(
            25,
            route.step(
                100,
                this.transaction,
                this.source,
                target -> this.inventories.get(target.getGrid()),
                amount -> Math.min(25, amount)));
        assertEquals(35, this.step(route, 100));
        assertEquals(60, this.first.stored);
        assertEquals(0, this.second.insertions);
        assertEquals(40, this.step(route, 100));
        assertEquals(40, this.second.stored);
        assertEquals(0, this.source.count);
    }

    @Test
    public void fullBatchesStayOnTheSameLogicalGridUntilItsCapacityIsExhausted() {
        this.first.actualCapacity = 50;
        this.first.simulatedCapacity = 50;
        final RoutedTransfer<String, Object> route = this.route(100, false);
        assertEquals(40, this.step(route, 40));
        assertEquals(0, this.second.insertions);
        assertEquals(10, this.step(route, 40));
        assertEquals(40, this.step(route, 40));
        assertEquals(10, this.step(route, 40));
        assertEquals(50, this.first.stored);
        assertEquals(50, this.second.stored);
        assertTrue(route.isFinished());
    }

    @Test
    public void duplicateInterfaceIsNotRetriedAsAnotherDestination() {
        this.first.actualCapacity = 30;
        final RoutedTransfer<String, Object> route = this.route(100, true);
        this.step(route, 100);
        this.step(route, 100);
        assertEquals(1, this.first.insertions);
        assertEquals(70, this.second.stored);
    }

    @Test
    public void failedRollbackIsolatesTheCursorAndBlocksEveryOtherTarget() {
        this.first.actualCapacity = 30;
        this.source.restoreLimit = 10;
        final RoutedTransfer<String, Object> route = this.route(100, false);
        assertEquals(30, this.step(route, 100));
        assertEquals(TransferTransaction.Phase.RECOVERABLE, this.transaction.getPhase());
        assertEquals(60, this.transaction.getHeld());
        assertEquals(0, this.step(route, 100));
        assertEquals(0, this.second.insertions);
        assertEquals(100, this.source.count + this.first.stored + this.transaction.getHeld());
        this.source.restoreLimit = 100;
        this.transaction.retryReturn(this.source);
        assertFalse(this.transaction.isPending());
        // Recovery ends this route; only a new scheduled cycle may examine the real cell again.
        assertEquals(0, this.step(route, 100));
        assertEquals(70, this.source.count);
        assertEquals(0, this.second.insertions);
    }

    @Test
    public void unknownInsertionResultNeverFailsOverOrRefundsAnAlreadyAcceptedBatch() {
        this.first.throwAfterInsert = true;
        final RoutedTransfer<String, Object> route = this.route(100, false);
        this.step(route, 100);
        assertEquals(TransferTransaction.Phase.UNCERTAIN, this.transaction.getPhase());
        assertEquals(0, this.step(route, 100));
        assertEquals(0, this.second.insertions);
        assertEquals(100, this.first.stored);
        assertEquals(0, this.source.count);
    }

    @Test
    public void invalidActualReturnCannotFallThroughToTheNextTarget() {
        this.first.invalidReturn = true;
        final RoutedTransfer<String, Object> route = this.route(100, false);
        this.step(route, 100);
        this.step(route, 100);
        assertEquals(TransferTransaction.Phase.UNCERTAIN, this.transaction.getPhase());
        assertEquals(0, this.second.insertions);
    }

    @Test
    public void configuredVoidSuccessIsDeductedInsteadOfSentAgainToTheNextNetwork() {
        this.first.voids = true;
        final RoutedTransfer<String, Object> route = this.route(100, false);
        assertEquals(100, this.step(route, 100));
        assertTrue(route.isFinished());
        assertEquals(0, this.step(route, 100));
        assertEquals(0, this.second.insertions);
    }

    @Test
    public void specifiedTargetRejectionDoesNotUseOtherRegisteredNetworks() {
        final TargetRegistry registry = new TargetRegistry();
        final TransferTarget selected = binding(0);
        registry.register(selected);
        registry.register(binding(1));
        registry.setRoute(0, TargetRegistry.Route.specific(selected.getId()));
        final RoutingCycle<Object> cycle = this.cycle(registry);
        this.first.simulatedCapacity = 0;
        final RoutedTransfer<String, Object> route = new RoutedTransfer<>("item", 100, cycle, 0);
        assertEquals(0, this.step(route, 100));
        assertTrue(route.isFinished());
        assertEquals(0, this.step(route, 100));
        assertEquals(0, this.second.insertions);
        assertEquals(100, this.source.count);
    }

    @Test
    public void mergingAnAlreadyUsedGridBlocksFurtherAttemptsInTheOldCycle() {
        final AtomicReference<Object> previousGrid = new AtomicReference<>(this.firstGrid);
        final TargetRegistry registry = new TargetRegistry();
        registry.register(binding(0));
        registry.register(binding(1));
        registry.setRoute(0, TargetRegistry.Route.automatic());
        final RoutingCycle<Object> cycle = new RoutingCycle<>(
            registry,
            () -> this.sourceGrid,
            binding -> binding.getX() == 0 ? new RoutingCycle.Endpoint<>(this.firstGrid, previousGrid::get, () -> true)
                : new RoutingCycle.Endpoint<>(this.secondGrid, () -> this.secondGrid, () -> true));
        cycle.prepare(4);
        this.first.actualCapacity = 30;
        final RoutedTransfer<String, Object> route = new RoutedTransfer<>("item", 100, cycle, 0);
        assertEquals(30, this.step(route, 100));
        previousGrid.set(this.secondGrid);
        assertEquals(0, this.step(route, 100));
        assertEquals(0, this.second.insertions);
        assertEquals(70, this.source.count);
        previousGrid.set(this.firstGrid);
        assertEquals(0, this.step(route, 100));
    }

    private long step(final RoutedTransfer<String, Object> route, final long batch) {
        return route.step(
            batch,
            this.transaction,
            this.source,
            target -> this.inventories.get(target.getGrid()),
            amount -> amount);
    }

    private RoutedTransfer<String, Object> route(final long amount, final boolean duplicate) {
        final TargetRegistry registry = new TargetRegistry();
        registry.register(binding(0));
        if (duplicate) {
            registry.register(binding(2));
        }
        registry.register(binding(1));
        registry.setRoute(0, TargetRegistry.Route.automatic());
        return new RoutedTransfer<>("item", amount, this.cycle(registry), 0);
    }

    private RoutingCycle<Object> cycle(final TargetRegistry registry) {
        final List<Object> grids = Arrays.asList(this.firstGrid, this.secondGrid, this.firstGrid);
        final RoutingCycle<Object> cycle = new RoutingCycle<>(
            registry,
            () -> this.sourceGrid,
            binding -> new RoutingCycle.Endpoint<>(
                grids.get(binding.getX()),
                () -> grids.get(binding.getX()),
                () -> true));
        cycle.prepare(4);
        return cycle;
    }

    private static TransferTarget binding(final int x) {
        return new TransferTarget(UUID.randomUUID(), 0, x, 64, 0);
    }

    private static final class Source implements TransferTransaction.Source {

        long count = 100;
        long extractLimit = 100;
        long restoreLimit = 100;
        int extractions;

        @Override
        public long extract(final long amount) {
            this.extractions++;
            final long removed = Math.min(amount, Math.min(this.count, this.extractLimit));
            this.count -= removed;
            return removed;
        }

        @Override
        public long restore(final long amount) {
            final long returned = Math.min(amount, this.restoreLimit);
            this.count += returned;
            return returned;
        }
    }

    private static final class Destination implements TransferTransaction.Destination {

        long simulatedCapacity = 100;
        long actualCapacity = 100;
        long stored;
        int insertions;
        boolean throwAfterInsert;
        boolean invalidReturn;
        boolean voids;

        @Override
        public long simulate(final long amount) {
            return Math.min(amount, this.simulatedCapacity);
        }

        @Override
        public long insert(final long amount) {
            this.insertions++;
            final long inserted = Math.min(amount, Math.max(0, this.actualCapacity - this.stored));
            this.stored += inserted;
            if (this.throwAfterInsert) {
                throw new IllegalStateException("Unknown result after mutation");
            }
            return this.invalidReturn ? amount + 1 : this.voids ? amount : inserted;
        }
    }
}
