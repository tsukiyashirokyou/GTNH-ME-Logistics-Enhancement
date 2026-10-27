package com.kyou.metransferdrive.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class TransferTransactionTest {

    private final TransferTransaction<String> transaction = new TransferTransaction<>(() -> {});
    private final Inventories inventories = new Inventories();

    @Test
    public void partialInsertionReturnsOnlyTheRemainder() {
        this.inventories.actualCapacity = 30;
        assertEquals(30, this.move(80));
        assertEquals(70, this.inventories.sourceCount);
        assertEquals(30, this.inventories.targetCount);
        assertFalse(this.transaction.isPending());
    }

    @Test
    public void zeroCapacityDoesNotExtract() {
        this.inventories.simulatedCapacity = 0;
        assertEquals(0, this.move(80));
        assertEquals(0, this.inventories.extractions);
        assertEquals(100, this.inventories.sourceCount);
    }

    @Test
    public void disappearingCapacityReturnsEverything() {
        this.inventories.actualCapacity = 0;
        assertEquals(0, this.move(80));
        assertEquals(100, this.inventories.sourceCount);
        assertFalse(this.transaction.isPending());
    }

    @Test
    public void actualExtractionLimitsInsertion() {
        this.inventories.extractionLimit = 17;
        assertEquals(17, this.move(80));
        assertEquals(17, this.inventories.targetCount);
        assertEquals(83, this.inventories.sourceCount);
    }

    @Test
    public void rollbackFailureRetainsOneKnownBatchAndBlocksFurtherExtraction() {
        this.inventories.actualCapacity = 30;
        this.inventories.restoreLimit = 10;
        assertEquals(30, this.move(80));
        assertEquals(TransferTransaction.Phase.RECOVERABLE, this.transaction.getPhase());
        assertEquals(40, this.transaction.getHeld());
        assertEquals(100, this.inventories.sourceCount + this.inventories.targetCount + this.transaction.getHeld());
        assertEquals(0, this.move(80));
        assertEquals(1, this.inventories.extractions);
        this.inventories.restoreLimit = 100;
        this.transaction.retryReturn(this.inventories);
        this.transaction.retryReturn(this.inventories);
        assertFalse(this.transaction.isPending());
        assertEquals(100, this.inventories.sourceCount + this.inventories.targetCount);
        assertEquals(30, this.inventories.targetCount);
    }

    @Test
    public void exceptionAfterDestinationMutationIsNeverAutomaticallyCompensatedOrReplayed() {
        this.inventories.throwAfterInsert = true;
        this.move(80);
        assertEquals(TransferTransaction.Phase.UNCERTAIN, this.transaction.getPhase());
        assertEquals(TransferTransaction.Phase.INSERTING, this.transaction.getUncertainOperation());
        this.transaction.retryReturn(this.inventories);
        this.move(80);
        assertEquals(20, this.inventories.sourceCount);
        assertEquals(80, this.inventories.targetCount);
        assertEquals(1, this.inventories.extractions);
    }

    @Test
    public void invalidActualExtractionStopsBeforeTargetInsertion() {
        this.inventories.invalidExtraction = true;
        this.move(80);
        assertEquals(TransferTransaction.Phase.UNCERTAIN, this.transaction.getPhase());
        assertEquals(0, this.inventories.targetCount);
        assertEquals(81, this.transaction.getReportedAmount());
    }

    @Test
    public void failingCompletionCheckpointRetainsIdentityAndNeverReplaysDelivery() {
        final AtomicInteger checkpoints = new AtomicInteger();
        final TransferTransaction<String> guarded = new TransferTransaction<>(() -> {
            if (checkpoints.incrementAndGet() == 5) {
                throw new IllegalStateException("Checkpoint failed");
            }
        });
        guarded.move("item", 80, this.inventories, this.inventories, amount -> amount);
        assertEquals(TransferTransaction.Phase.UNCERTAIN, guarded.getPhase());
        assertEquals("item", guarded.getKey());
        guarded.move("item", 80, this.inventories, this.inventories, amount -> amount);
        assertEquals(80, this.inventories.targetCount);
        assertEquals(1, this.inventories.extractions);
    }

    @Test
    public void invalidRemainderCannotBecomeARefund() {
        this.inventories.invalidInsertion = true;
        this.move(80);
        assertEquals(TransferTransaction.Phase.UNCERTAIN, this.transaction.getPhase());
        assertEquals(20, this.inventories.sourceCount);
    }

    @Test
    public void exceptionAfterExtractionStopsWithoutInventingARefund() {
        this.inventories.throwAfterExtract = true;
        this.move(80);
        assertEquals(TransferTransaction.Phase.UNCERTAIN, this.transaction.getPhase());
        assertEquals(TransferTransaction.Phase.EXTRACTING, this.transaction.getUncertainOperation());
        this.transaction.retryReturn(this.inventories);
        assertEquals(20, this.inventories.sourceCount);
        assertEquals(0, this.inventories.targetCount);
    }

    @Test
    public void exceptionAfterPartialRefundCannotRepeatThatRefund() {
        this.inventories.actualCapacity = 30;
        this.inventories.restoreLimit = 10;
        this.inventories.throwAfterRestore = true;
        this.move(80);
        assertEquals(TransferTransaction.Phase.UNCERTAIN, this.transaction.getPhase());
        assertEquals(TransferTransaction.Phase.RETURNING, this.transaction.getUncertainOperation());
        this.transaction.retryReturn(this.inventories);
        assertEquals(30, this.inventories.sourceCount);
        assertEquals(30, this.inventories.targetCount);
    }

    @Test
    public void reloadQuarantinesEvenPreviouslyKnownRemainders() {
        this.transaction.quarantine("item", TransferTransaction.Phase.RECOVERABLE, 80, 40, 30);
        this.transaction.retryReturn(this.inventories);
        this.move(80);
        assertTrue(this.transaction.isPending());
        assertEquals(40, this.transaction.getHeld());
        assertEquals(0, this.inventories.extractions);
    }

    @Test
    public void actualEnergyReservationLimitsExtraction() {
        assertEquals(12, this.transaction.move("item", 80, this.inventories, this.inventories, amount -> 12));
        assertEquals(88, this.inventories.sourceCount);
    }

    @Test
    public void noEnergyDoesNotExtract() {
        assertEquals(0, this.transaction.move("item", 80, this.inventories, this.inventories, amount -> 0));
        assertEquals(0, this.inventories.extractions);
    }

    @Test
    public void successiveCallsAccountOnlyForTheirOwnDelivery() {
        assertEquals(80, this.move(80));
        assertEquals(20, this.move(80));
        assertEquals(0, this.move(80));
        assertEquals(100, this.inventories.targetCount);
        assertEquals(0, this.inventories.sourceCount);
    }

    @Test
    public void invalidRestorationAmountsAreEvidenceRatherThanRefundAuthorization() {
        for (final long reported : new long[] { -1, Long.MAX_VALUE }) {
            final TransferTransaction<String> transaction = new TransferTransaction<>(() -> {});
            final TransferTransaction.Source source = new TransferTransaction.Source() {

                @Override
                public long extract(long amount) {
                    return amount;
                }

                @Override
                public long restore(long amount) {
                    return reported;
                }
            };
            final TransferTransaction.Destination destination = new TransferTransaction.Destination() {

                @Override
                public long simulate(long amount) {
                    return amount;
                }

                @Override
                public long insert(long amount) {
                    return 50;
                }
            };
            assertEquals(50, transaction.move("item", 80, source, destination, amount -> amount));
            assertEquals(TransferTransaction.Phase.UNCERTAIN, transaction.getPhase());
            assertEquals(TransferTransaction.Phase.RETURNING, transaction.getUncertainOperation());
            assertEquals(30, transaction.getHeld());
            assertEquals(reported, transaction.getReportedAmount());
            assertEquals(0, transaction.move("item", 80, source, destination, amount -> amount));
            assertTrue(
                transaction.getFailureDescription()
                    .contains("invalid transfer amount"));
        }
    }

    @Test
    public void diagnosticTextIsBoundedAndTheUncertainCallIsNotReplayed() {
        final TransferTransaction<String> transaction = new TransferTransaction<>(() -> {});
        final String message = String.join("", java.util.Collections.nCopies(1000, "x"));
        final int[] calls = { 0 };
        final TransferTransaction.Source source = new TransferTransaction.Source() {

            @Override
            public long extract(long amount) {
                calls[0]++;
                throw new IllegalStateException(message);
            }

            @Override
            public long restore(long amount) {
                throw new AssertionError("Uncertain extraction must not be refunded");
            }
        };
        transaction.move("item", 20, source, this.inventories, amount -> amount);
        transaction.move("item", 20, source, this.inventories, amount -> amount);
        assertEquals(1, calls[0]);
        assertEquals(
            256,
            transaction.getFailureDescription()
                .length());
        assertEquals("item", transaction.getKey());
    }

    private long move(final long amount) {
        return this.transaction.move("item", amount, this.inventories, this.inventories, available -> available);
    }

    private static final class Inventories implements TransferTransaction.Source, TransferTransaction.Destination {

        long sourceCount = 100;
        long targetCount;
        long simulatedCapacity = 100;
        long actualCapacity = 100;
        long extractionLimit = 100;
        long restoreLimit = 100;
        int extractions;
        boolean throwAfterInsert;
        boolean invalidExtraction;
        boolean invalidInsertion;
        boolean throwAfterExtract;
        boolean throwAfterRestore;

        @Override
        public long simulate(final long amount) {
            return Math.min(amount, this.simulatedCapacity);
        }

        @Override
        public long extract(final long amount) {
            this.extractions++;
            final long extracted = Math.min(this.sourceCount, Math.min(amount, this.extractionLimit));
            this.sourceCount -= extracted;
            if (this.throwAfterExtract) {
                throw new IllegalStateException("Source failed after extraction");
            }
            return this.invalidExtraction ? amount + 1 : extracted;
        }

        @Override
        public long insert(final long amount) {
            final long accepted = Math.min(amount, this.actualCapacity);
            this.targetCount += accepted;
            if (this.throwAfterInsert) {
                throw new IllegalStateException("Destination failed after mutation");
            }
            return this.invalidInsertion ? -1 : accepted;
        }

        @Override
        public long restore(final long amount) {
            final long accepted = Math.min(amount, this.restoreLimit);
            this.sourceCount += accepted;
            if (this.throwAfterRestore) {
                throw new IllegalStateException("Source failed after restoration");
            }
            return accepted;
        }
    }
}
