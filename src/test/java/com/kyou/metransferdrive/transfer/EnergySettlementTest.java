package com.kyou.metransferdrive.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagString;

import org.junit.Test;

/**
 * Real transaction/ledger/budget logic; energy providers, Grid identity and inventories are deterministic contracts.
 */
public class EnergySettlementTest {

    @Test
    public void fullReceptionChargesBeforeExtractionAndDoesNotRefund() {
        final Fixture f = new Fixture();
        f.source.beforeExtract = () -> assertEquals(999_920, f.power.power, 0);
        assertEquals(80, f.move(80));
        assertEquals(80, f.power.debited - f.power.refunded, 0);
        assertEquals(0, f.power.refundCalls);
        assertFalse(f.account.isPending());
        assertFalse(f.transaction.isPending());
        f.conserved(100);
    }

    @Test
    public void partialActualReceptionAndRestoredRemainderPayOnlyDeliveredQuantity() {
        final Fixture f = new Fixture();
        f.destination.capacity = 30;
        assertEquals(30, f.move(80));
        assertEquals(80, f.power.debited, 0);
        assertEquals(50, f.power.refunded, 0);
        assertEquals(999_970, f.power.power, 0);
        assertEquals(70, f.source.count);
        f.conserved(100);
    }

    @Test
    public void simulatedRejectionNeverDebitsAndActualRejectionRefundsOnce() {
        final Fixture f = new Fixture();
        f.destination.simulation = 0;
        assertEquals(0, f.move(80));
        assertEquals(0, f.power.debitCalls);
        f.destination.simulation = Long.MAX_VALUE;
        f.destination.capacity = 0;
        assertEquals(0, f.move(80));
        assertEquals(80, f.power.refunded, 0);
        assertEquals(1, f.power.refundCalls);
        assertEquals(1_000_000, f.power.power, 0);
        f.conserved(100);
    }

    @Test
    public void noActualExtractionRefundsTheEntireReservation() {
        final Fixture f = new Fixture();
        f.source.extractLimit = 0;
        assertEquals(0, f.move(80));
        assertEquals(f.power.debited, f.power.refunded, 0);
        assertEquals(0, f.destination.stored);
        assertFalse(f.account.isPending());
    }

    @Test
    public void fewerExtractedItemsAlsoReduceTheFinalBill() {
        final Fixture f = new Fixture();
        f.source.extractLimit = 17;
        assertEquals(17, f.move(80));
        assertEquals(63, f.power.refunded, 0);
        assertEquals(17, f.power.debited - f.power.refunded, 0);
        f.conserved(100);
    }

    @Test
    public void recoveredKnownRemainderIsSettledOnceAndDoesNotBillAgainOnReturn() {
        final Fixture f = new Fixture();
        f.destination.capacity = 30;
        f.source.restoreLimit = 10;
        assertEquals(30, f.move(80));
        assertEquals(TransferTransaction.Phase.RECOVERABLE, f.transaction.getPhase());
        assertEquals(40, f.transaction.getHeld());
        assertEquals(50, f.power.refunded, 0);
        assertFalse(f.account.isPending());
        f.source.restoreLimit = Long.MAX_VALUE;
        f.transaction.retryReturn(f.source);
        assertEquals(1, f.power.debitCalls);
        assertEquals(1, f.power.refundCalls);
        assertFalse(f.transaction.isPending());
        f.conserved(100);
    }

    @Test
    public void unknownInsertionOrRollbackNeverBlindlyRefunds() {
        for (boolean duringRollback : new boolean[] { false, true }) {
            final Fixture f = new Fixture();
            f.destination.capacity = 30;
            f.destination.throwAfter = !duringRollback;
            f.source.throwOnRestore = duringRollback;
            f.move(80);
            assertEquals(TransferTransaction.Phase.UNCERTAIN, f.transaction.getPhase());
            assertTrue(f.account.isPending());
            assertEquals(0, f.power.refundCalls);
            f.account.settle(f.transaction.getDelivered(), false);
            assertEquals(0, f.move(80));
            assertEquals(1, f.power.debitCalls);
            assertEquals(0, f.power.refundCalls);
        }
    }

    @Test
    public void targetFailoverSettlesEachActualPortionWithoutDoubleCharging() {
        final Fixture f = new Fixture();
        final Object first = new Object();
        final Object second = new Object();
        final TargetRegistry registry = new TargetRegistry();
        registry.register(new TransferTarget(UUID.randomUUID(), 0, 1, 2, 3));
        registry.register(new TransferTarget(UUID.randomUUID(), 0, 2, 2, 3));
        registry.setRoute(0, TargetRegistry.Route.automatic());
        final RoutingCycle<Object> cycle = new RoutingCycle<>(
            registry,
            () -> f.grid,
            target -> new RoutingCycle.Endpoint<>(
                target.getX() == 1 ? first : second,
                () -> target.getX() == 1 ? first : second,
                () -> true));
        cycle.prepare(4);
        final RoutedTransfer<String, Object> route = new RoutedTransfer<>("item", 100, cycle, 0);
        final Destination other = new Destination();
        f.destination.capacity = 30;
        assertEquals(
            30,
            route.step(
                100,
                f.transaction,
                f.source,
                target -> target.getGrid() == first ? f.destination : other,
                f.bind()));
        assertEquals(70, route.getRemaining());
        assertEquals(
            70,
            route.step(
                100,
                f.transaction,
                f.source,
                target -> target.getGrid() == first ? f.destination : other,
                f.bind()));
        assertEquals(100, f.power.debited - f.power.refunded, 0);
        assertEquals(30, f.destination.stored);
        assertEquals(70, other.stored);
        assertTrue(route.isFinished());
    }

    @Test
    public void largeRequestsAreBoundedAndExactForAllNativeUnits() {
        for (long count : new long[] { 1_000_000, 10_000_000, 100_000_000, TransferEnergy.MAX_AMOUNT + 1000 }) {
            for (int unit : new int[] { 1, 1000, 1 }) {
                final Fixture f = new Fixture();
                f.unit = unit;
                f.power.power = f.power.capacity = 100_000_000_000.0;
                f.source.count = count;
                final long delivered = Math.min(count, TransferEnergy.MAX_AMOUNT);
                assertEquals(delivered, f.move(count));
                assertEquals(TransferEnergy.unitsFor(delivered, unit), f.power.debited - f.power.refunded, 0);
                f.conserved(count);
            }
        }
    }

    @Test
    public void fluidBillingRoundsActualReceptionUpUsingConfiguredPhysicalUnits() {
        final Fixture f = new Fixture();
        f.unit = 1000;
        f.multiplier = 2.5;
        f.source.count = 64000;
        f.destination.capacity = 1501;
        assertEquals(1501, f.move(64000));
        assertEquals(160, f.power.debited, 0);
        assertEquals(155, f.power.refunded, 0);
        assertEquals(5, f.power.debited - f.power.refunded, 0);
        f.conserved(64000);
    }

    @Test
    public void lessThanOnePaidNominalUnitIsRefundedRatherThanProvidingFreeFluid() {
        final Fixture f = new Fixture();
        f.unit = 1000;
        f.power.actualLimit = Math.nextDown(1.0);
        assertEquals(0, f.move(100));
        assertEquals(0, f.destination.stored);
        assertEquals(f.power.debited, f.power.refunded, 0);
        assertTrue(f.account.isDeferred());
        assertFalse(f.account.isPending());
    }

    @Test
    public void supplyChangingAfterSimulationAuthorizesOnlyWholePaidUnits() {
        final Fixture f = new Fixture();
        f.power.actualLimit = 17.75;
        assertEquals(17, f.move(80));
        assertEquals(0.75, f.power.refunded, 0);
        assertEquals(17, f.power.debited - f.power.refunded, 0);
        f.conserved(100);
    }

    @Test
    public void nativeOverflowAcknowledgementIsNotRetriedAsUnacceptedEnergy() {
        final Fixture f = new Fixture();
        f.destination.capacity = 30;
        f.power.overflow = 5000;
        f.move(80);
        assertEquals(1, f.power.refundCalls);
        assertEquals(50, f.power.refunded, 0);
        assertFalse(f.account.isPending());
        assertEquals(999_970, f.power.power, 0);
    }

    @Test
    public void refundMayHaveHappenedBeforeExceptionSoEvidenceNeverReplaysIt() {
        final Fixture f = new Fixture();
        f.destination.capacity = 30;
        f.power.throwAfterRefund = true;
        f.move(80);
        assertEquals(TransferTransaction.Phase.UNCERTAIN, f.transaction.getPhase());
        assertTrue(f.account.isPending());
        assertEquals(1, f.power.refundCalls);
        final NBTTagCompound saved = new NBTTagCompound();
        f.account.write(saved);
        assertTrue(
            saved.getCompoundTag("transferEnergy")
                .getBoolean("refundAttempted"));
        assertFalse(
            saved.getCompoundTag("transferEnergy")
                .getBoolean("refundAcknowledged"));
        final TransferEnergyAccount loaded = new TransferEnergyAccount(() -> {}, new TransferPowerBudget());
        loaded.read(saved);
        assertTrue(loaded.isPending());
        loaded.settle(30, false);
        assertThrows(IllegalStateException.class, () -> loaded.bind(f.grid, f.power, 1, 1));
        assertEquals(1, f.power.refundCalls);
        final NBTTagCompound roundTrip = new NBTTagCompound();
        loaded.write(roundTrip);
        assertEquals(saved, roundTrip);
    }

    @Test
    public void disconnectedOriginalGridBlocksRefundWithoutSendingItToAnotherGrid() {
        final Fixture f = new Fixture();
        f.destination.capacity = 30;
        f.destination.after = () -> f.power.valid = false;
        f.move(80);
        assertEquals(TransferTransaction.Phase.UNCERTAIN, f.transaction.getPhase());
        final NBTTagCompound saved = new NBTTagCompound();
        f.account.write(saved);
        assertEquals(
            "REFUND_BLOCKED",
            saved.getCompoundTag("transferEnergy")
                .getString("stage"));
        assertEquals(0, f.power.refundCalls);
        assertEquals(
            50,
            saved.getCompoundTag("transferEnergy")
                .getDouble("refund"),
            0);
        assertTrue(f.account.isPending());
        f.conserved(100);
    }

    @Test
    public void unknownDebitLocksEnergyEvenBeforeAnyInventoryWasExtracted() {
        final Fixture f = new Fixture();
        f.power.throwAfterDebit = true;
        assertThrows(IllegalStateException.class, () -> f.move(80));
        assertTrue(f.account.isPending());
        assertFalse(f.transaction.isPending());
        assertEquals(100, f.source.count);
        assertEquals(0, f.power.refundCalls);
        final NBTTagCompound saved = new NBTTagCompound();
        f.account.write(saved);
        assertEquals(
            "UNCERTAIN",
            saved.getCompoundTag("transferEnergy")
                .getString("stage"));
        assertEquals(
            80,
            saved.getCompoundTag("transferEnergy")
                .getDouble("debitRequest"),
            0);
    }

    @Test
    public void malformedOrOldPendingEnergyNbtIsEvidenceNeverAReusableCredit() {
        final Fixture f = new Fixture();
        final NBTTagCompound saved = new NBTTagCompound();
        saved.setTag("transferEnergy", new NBTTagString("damaged or future version"));
        f.account.read(saved);
        assertTrue(f.account.isPending());
        assertThrows(IllegalStateException.class, () -> f.account.bind(f.grid, f.power, 1, 1));
        final NBTTagCompound copy = new NBTTagCompound();
        f.account.write(copy);
        assertEquals(saved, copy);
        assertEquals(0, f.power.refundCalls);
        assertEquals(0, f.power.debitCalls);
    }

    @Test
    public void normalSettlementDoesNotLeaveAReusableOrPendingNbtCredit() {
        final Fixture f = new Fixture();
        f.destination.capacity = 30;
        f.move(80);
        final NBTTagCompound data = new NBTTagCompound();
        f.account.write(data);
        assertFalse(data.hasKey("transferEnergy"));
        f.account.settle(30, true);
        assertEquals(1, f.power.refundCalls);
    }

    @Test
    public void invalidRefundAcknowledgementLocksWithoutRefundReplay() {
        for (final double invalid : new double[] { -1, Double.NaN, Double.POSITIVE_INFINITY }) {
            final Fixture f = new Fixture();
            f.destination.capacity = 30;
            f.power.overflow = invalid;
            assertEquals(30, f.move(80));
            assertTrue(f.account.isPending());
            assertTrue(f.transaction.isPending());
            f.account.settle(30, false);
            assertEquals(1, f.power.refundCalls);
            f.conserved(100);
        }
    }

    @Test
    public void energyDeferredRouteRetainsTheSameTargetAndResumesWithoutRepeatingDelivery() {
        final Fixture f = new Fixture();
        f.power.power = 200;
        f.power.capacity = 1000;
        final Object targetGrid = new Object();
        final TargetRegistry registry = new TargetRegistry();
        registry.register(new TransferTarget(UUID.randomUUID(), 0, 1, 2, 3));
        registry.setRoute(0, TargetRegistry.Route.automatic());
        final RoutingCycle<Object> cycle = new RoutingCycle<>(
            registry,
            () -> f.grid,
            target -> new RoutingCycle.Endpoint<>(targetGrid, () -> targetGrid, () -> true));
        cycle.prepare(4);
        final RoutedTransfer<String, Object> route = new RoutedTransfer<>("item", 100, cycle, 0);
        assertEquals(0, route.step(100, f.transaction, f.source, target -> f.destination, f.bind()));
        assertTrue(f.account.isDeferred());
        assertFalse(route.isFinished());
        assertEquals(100, route.getRemaining());
        f.budget.clear();
        f.power.power = 1000;
        assertEquals(100, route.step(100, f.transaction, f.source, target -> f.destination, f.bind()));
        assertTrue(route.isFinished());
        assertEquals(0, route.step(100, f.transaction, f.source, target -> f.destination, f.bind()));
        assertEquals(100, f.power.debited - f.power.refunded, 0);
        assertEquals(1, f.power.debitCalls);
        f.conserved(100);
    }

    @Test
    public void badNativeDebitIsEvidenceAndNeverAuthorizesInventoryOrRefund() {
        for (final double invalid : new double[] { -1, Double.NaN, Double.POSITIVE_INFINITY, Math.nextUp(80.0) }) {
            final Fixture f = new Fixture();
            f.power.debitReport = invalid;
            assertThrows(IllegalStateException.class, () -> f.move(80));
            assertTrue(f.account.isPending());
            assertEquals(100, f.source.count);
            assertEquals(0, f.destination.stored);
            assertEquals(0, f.power.refundCalls);
            final NBTTagCompound evidence = new NBTTagCompound();
            f.account.write(evidence);
            assertEquals(
                invalid,
                evidence.getCompoundTag("transferEnergy")
                    .getDouble("prepaid"),
                0);
        }
    }

    @Test
    public void eachPrepaymentCheckpointCanBeSavedButNeverReplayedAfterLoad() {
        final TransferEnergyAccount[] holder = new TransferEnergyAccount[1];
        final List<String> stages = new ArrayList<>();
        final List<NBTTagCompound> snapshots = new ArrayList<>();
        holder[0] = new TransferEnergyAccount(() -> {
            final NBTTagCompound data = new NBTTagCompound();
            holder[0].write(data);
            snapshots.add(data);
            stages.add(
                data.hasKey("transferEnergy") ? data.getCompoundTag("transferEnergy")
                    .getString("stage") : "IDLE");
        }, new TransferPowerBudget());
        final Power power = new Power();
        assertEquals(
            80,
            holder[0].bind(new Object(), power, 1, 1)
                .reserve(80));
        holder[0].settle(30, true);
        assertEquals(Arrays.asList("DEBITING", "RESERVED", "REFUNDING", "IDLE"), stages);
        for (int n = 0; n < snapshots.size(); n++) {
            final TransferEnergyAccount loaded = new TransferEnergyAccount(() -> {}, new TransferPowerBudget());
            loaded.read(snapshots.get(n));
            assertEquals(n < 3, loaded.isPending());
            loaded.settle(30, false);
            assertEquals(1, power.refundCalls);
        }
    }

    @Test
    public void energyOnlyQuarantineIsPreservedByExistingMachineRecoveryPacking() {
        final Fixture f = new Fixture();
        f.power.throwAfterDebit = true;
        assertThrows(IllegalStateException.class, () -> f.move(80));
        final NBTTagCompound state = new NBTTagCompound();
        f.account.write(state);
        assertFalse(state.hasKey("transferRecovery"));
        final ItemStack packed = RecoveryEvidence.pack(new ItemStack(new Item()), state);
        final NBTTagCompound restored = RecoveryEvidence.packedState(
            packed.getTagCompound()
                .getTag("metransferdriveRecovery"));
        final TransferEnergyAccount loaded = new TransferEnergyAccount(() -> {}, new TransferPowerBudget());
        loaded.read(restored);
        assertTrue(loaded.isPending());
        assertEquals(state, restored);
        assertEquals(1, packed.stackSize);
        assertEquals(0, f.power.refundCalls);
    }

    static final class Power implements TransferEnergyAccount.Port {

        double power = 1_000_000;
        double capacity = 1_000_000;
        double storedOverride = -1;
        double idle;
        double actualLimit = Double.MAX_VALUE;
        double debited;
        double refunded;
        double overflow;
        int debitCalls;
        int refundCalls;
        int simulationCalls;
        boolean valid = true;
        boolean infinite;
        boolean throwAfterDebit;
        boolean throwAfterRefund;
        double debitReport = Double.NEGATIVE_INFINITY;
        Runnable beforeDebit = () -> {};

        @Override
        public boolean valid() {
            return this.valid;
        }

        @Override
        public boolean infinite() {
            return this.infinite;
        }

        @Override
        public double stored() {
            return this.storedOverride < 0 ? this.power : this.storedOverride;
        }

        @Override
        public double capacity() {
            return this.capacity;
        }

        @Override
        public double idle() {
            return this.idle;
        }

        @Override
        public double extract(final double request, final boolean simulate) {
            if (simulate) {
                this.simulationCalls++;
                return this.infinite ? request : Math.min(request, this.power);
            }
            this.debitCalls++;
            this.beforeDebit.run();
            final double paid = Math.min(this.actualLimit, this.infinite ? request : Math.min(request, this.power));
            this.debited += paid;
            if (!this.infinite) {
                this.power -= paid;
            }
            if (this.throwAfterDebit) {
                throw new IllegalStateException("Unknown debit result");
            }
            return this.debitReport == Double.NEGATIVE_INFINITY ? paid : this.debitReport;
        }

        @Override
        public double refund(final double amount) {
            this.refundCalls++;
            this.refunded += amount;
            this.power += amount;
            if (this.throwAfterRefund) {
                throw new IllegalStateException("Unknown refund result");
            }
            return this.overflow;
        }
    }

    private static final class Source implements TransferTransaction.Source {

        long count = 100;
        long extractLimit = Long.MAX_VALUE;
        long restoreLimit = Long.MAX_VALUE;
        boolean throwOnRestore;
        Runnable beforeExtract = () -> {};

        @Override
        public long extract(final long amount) {
            this.beforeExtract.run();
            final long actual = Math.min(Math.min(amount, this.count), this.extractLimit);
            this.count -= actual;
            return actual;
        }

        @Override
        public long restore(final long amount) {
            final long actual = Math.min(amount, this.restoreLimit);
            this.count += actual;
            if (this.throwOnRestore) {
                throw new IllegalStateException("Unknown rollback result");
            }
            return actual;
        }
    }

    private static final class Destination implements TransferTransaction.Destination {

        long simulation = Long.MAX_VALUE;
        long capacity = Long.MAX_VALUE;
        long stored;
        boolean throwAfter;
        Runnable after = () -> {};

        @Override
        public long simulate(final long amount) {
            return Math.min(amount, this.simulation);
        }

        @Override
        public long insert(final long amount) {
            final long received = Math.min(amount, this.capacity - this.stored);
            this.stored += received;
            this.after.run();
            if (this.throwAfter) {
                throw new IllegalStateException("Unknown reception result");
            }
            return received;
        }
    }

    private static final class Fixture {

        final Object grid = new Object();
        final TransferPowerBudget budget = new TransferPowerBudget();
        final Power power = new Power();
        final Source source = new Source();
        final Destination destination = new Destination();
        final TransferTransaction<String> transaction = new TransferTransaction<>(() -> {});
        final TransferEnergyAccount account = new TransferEnergyAccount(() -> {}, this.budget);
        int unit = 1;
        double multiplier = 1;

        TransferEnergyAccount bind() {
            return this.account.bind(this.grid, this.power, this.unit, this.multiplier);
        }

        long move(final long limit) {
            if (this.transaction.isPending()) {
                return 0;
            }
            return this.transaction.move("type", limit, this.source, this.destination, this.bind());
        }

        void conserved(final long initial) {
            assertEquals(initial, this.source.count + this.destination.stored + this.transaction.getHeld());
        }
    }
}
