package com.kyou.gtnhmelogisticsenhancement.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongSupplier;

import org.junit.Test;

/** All time values below are deterministic virtual time, never measured Minecraft Tick or MSPT results. */
public class TransferSchedulerTest {

    private final VirtualTime time = new VirtualTime();
    private final TransferScheduler scheduler = new TransferScheduler(this.time);

    @Test
    public void sixtyThreeSmallTypesNeedThirtyTwoWorkTicksAtTwoTransactionsPerSlice() {
        final int[] remaining = { 63 };
        final List<Integer> perTick = new ArrayList<>();
        this.scheduler.submit(budget -> {
            int attempts = 0;
            while (remaining[0] > 0 && budget.nextStep() && budget.transaction()) {
                remaining[0]--;
                attempts++;
            }
            perTick.add(attempts);
            return remaining[0] > 0;
        });
        for (int tick = 0; tick < 31; tick++) {
            this.scheduler.runTick();
        }
        assertEquals(1, remaining[0]);
        assertEquals(31, perTick.size());
        for (final int count : perTick) {
            assertEquals(2, count);
        }
        this.scheduler.runTick();
        assertEquals(0, remaining[0]);
        assertEquals(1, (int) perTick.get(31));
        this.scheduler.runTick();
        assertEquals(32, perTick.size());
    }

    @Test
    public void oneSliceBoundsSnapshotsProbesAndCursorStepsAndNextTickRenewsThem() {
        final List<List<Integer>> counts = new ArrayList<>();
        this.scheduler.submit(budget -> {
            int steps = 0;
            int snapshots = 0;
            int probes = 0;
            while (budget.nextStep()) {
                steps++;
                if (budget.openCell()) {
                    snapshots++;
                }
                if (budget.probeType()) {
                    probes++;
                }
            }
            counts.add(Arrays.asList(steps, snapshots, probes));
            return true;
        });
        this.scheduler.runTick();
        this.scheduler.runTick();
        assertEquals(Arrays.asList(Arrays.asList(8, 1, 4), Arrays.asList(8, 1, 4)), counts);
    }

    @Test
    public void failedOrRejectedAttemptsStillConsumeTheTransactionAllowance() {
        final int[] attempts = { 0 };
        this.scheduler.submit(budget -> {
            while (budget.nextStep() && budget.transaction()) {
                attempts[0]++;
                // A zero-capacity receiver still required an actual simulated attempt.
            }
            return true;
        });
        this.scheduler.runTick();
        assertEquals(2, attempts[0]);
        this.scheduler.runTick();
        assertEquals(4, attempts[0]);
    }

    @Test
    public void exactlyFiveHundredVirtualMicrosecondsStopsFurtherDeviceWork() {
        final int[] attempts = { 0 };
        this.scheduler.submit(budget -> {
            assertTrue(budget.nextStep());
            assertTrue(budget.transaction());
            attempts[0]++;
            this.time.advance(500_000);
            assertFalse(budget.transaction());
            assertFalse(budget.nextStep());
            assertFalse(budget.openCell());
            assertFalse(budget.probeType());
            return false;
        });
        this.scheduler.runTick();
        assertEquals(1, attempts[0]);
    }

    @Test
    public void deviceTimeLimitDoesNotPreventAnotherDevicesFreshSlice() {
        final int[] visits = { 0, 0 };
        for (int device = 0; device < visits.length; device++) {
            final int index = device;
            this.scheduler.submit(budget -> {
                assertTrue(budget.transaction());
                visits[index]++;
                this.time.advance(500_000);
                assertFalse(budget.transaction());
                return false;
            });
        }
        this.scheduler.runTick();
        assertEquals(1, visits[0]);
        assertEquals(1, visits[1]);
    }

    @Test
    public void exactlyTwoVirtualMillisecondsDefersTheNextQueuedDevice() {
        final List<Integer> visits = new ArrayList<>();
        for (int device = 0; device < 5; device++) {
            final int index = device;
            this.scheduler.submit(budget -> {
                assertTrue(budget.transaction());
                visits.add(index);
                this.time.advance(500_000);
                return true;
            });
        }
        this.scheduler.runTick();
        assertEquals(Arrays.asList(0, 1, 2, 3), visits);
        this.scheduler.runTick();
        assertEquals(Arrays.asList(0, 1, 2, 3, 4, 0, 1, 2), visits);
    }

    @Test
    public void slowIndivisibleTransactionCompletesItsKnownRollbackBeforeYielding() {
        final List<String> calls = new ArrayList<>();
        final TransferTransaction<String> transaction = new TransferTransaction<>(() -> {});
        final TransferTransaction.Source source = new TransferTransaction.Source() {

            @Override
            public long extract(final long amount) {
                calls.add("extract");
                return amount;
            }

            @Override
            public long restore(final long amount) {
                calls.add("return " + amount);
                return amount;
            }
        };
        final TransferTransaction.Destination destination = new TransferTransaction.Destination() {

            @Override
            public long simulate(final long amount) {
                calls.add("simulate");
                return amount;
            }

            @Override
            public long insert(final long amount) {
                calls.add("insert");
                time.advance(3_000_000);
                return 2;
            }
        };
        this.scheduler.submit(budget -> {
            assertTrue(budget.transaction());
            assertEquals(2, transaction.move("aspect", 7, source, destination, amount -> amount));
            assertFalse(transaction.isPending());
            assertFalse(budget.transaction());
            return false;
        });
        this.scheduler.submit(budget -> {
            calls.add("next device");
            return false;
        });
        this.scheduler.runTick();
        assertEquals(Arrays.asList("simulate", "extract", "insert", "return 5"), calls);
        this.scheduler.runTick();
        assertEquals(Arrays.asList("simulate", "extract", "insert", "return 5", "next device"), calls);
    }

    @Test
    public void oneDeviceCannotReceiveASecondSliceInTheSameTick() {
        final int[] visits = { 0 };
        this.scheduler.submit(budget -> {
            visits[0]++;
            return true;
        });
        this.scheduler.runTick();
        assertEquals(1, visits[0]);
        this.scheduler.runTick();
        assertEquals(2, visits[0]);
    }

    @Test
    public void repeatedSubmissionsKeepOnlyOneQueueEntry() {
        final int[] visits = { 0 };
        final TransferScheduler.Work work = budget -> {
            visits[0]++;
            return false;
        };
        this.scheduler.submit(work);
        this.scheduler.submit(work);
        this.scheduler.submit(work);
        this.scheduler.runTick();
        this.scheduler.runTick();
        assertEquals(1, visits[0]);
    }

    @Test
    public void cancellingAWaitingDevicePreventsItsExecution() {
        final int[] visits = { 0 };
        final TransferScheduler.Work work = budget -> {
            visits[0]++;
            return true;
        };
        this.scheduler.submit(work);
        this.scheduler.cancel(work);
        this.scheduler.cancel(work);
        this.scheduler.runTick();
        assertEquals(0, visits[0]);
    }

    @Test
    public void runningDeviceCancellationOverridesItsRequestToContinue() {
        final int[] visits = { 0 };
        final TransferScheduler.Work[] work = new TransferScheduler.Work[1];
        work[0] = budget -> {
            visits[0]++;
            this.scheduler.cancel(work[0]);
            return true;
        };
        this.scheduler.submit(work[0]);
        this.scheduler.runTick();
        this.scheduler.runTick();
        assertEquals(1, visits[0]);
    }

    @Test
    public void cancellingWaitingDevicesCannotMakeAnAlreadyServedDeviceRunAgain() {
        final int[] visits = { 0, 0, 0 };
        final TransferScheduler.Work waiting = budget -> {
            visits[1]++;
            return true;
        };
        this.scheduler.submit(budget -> {
            visits[0]++;
            this.scheduler.cancel(waiting);
            return true;
        });
        this.scheduler.submit(waiting);
        this.scheduler.submit(budget -> {
            visits[2]++;
            return true;
        });
        this.scheduler.runTick();
        assertEquals(1, visits[0]);
        assertEquals(0, visits[1]);
        assertEquals(1, visits[2]);
        this.scheduler.runTick();
        assertEquals(2, visits[0]);
        assertEquals(0, visits[1]);
        assertEquals(2, visits[2]);
    }

    @Test
    public void cancellingAndResubmittingRunningWorkStillCannotRunItTwiceInOneTick() {
        final int[] visits = { 0 };
        final TransferScheduler.Work[] work = new TransferScheduler.Work[1];
        work[0] = budget -> {
            visits[0]++;
            if (visits[0] == 1) {
                this.scheduler.cancel(work[0]);
                this.scheduler.submit(work[0]);
            }
            return visits[0] == 1;
        };
        this.scheduler.submit(work[0]);
        this.scheduler.runTick();
        assertEquals(1, visits[0]);
        this.scheduler.runTick();
        assertEquals(2, visits[0]);
    }

    @Test
    public void throwingWorkIsRemovedAndOtherDevicesCanContinueOnTheNextTick() {
        final int[] visits = { 0, 0 };
        final TransferScheduler.Work broken = budget -> {
            if (++visits[0] == 1) {
                throw new IllegalStateException("synthetic operation failure");
            }
            return false;
        };
        this.scheduler.submit(broken);
        this.scheduler.submit(budget -> {
            visits[1]++;
            return false;
        });
        assertThrows(IllegalStateException.class, this.scheduler::runTick);
        this.scheduler.runTick();
        assertEquals(1, visits[0]);
        assertEquals(1, visits[1]);
        this.scheduler.submit(broken);
        this.scheduler.runTick();
        assertEquals(2, visits[0]);
    }

    @Test
    public void oneHundredDevicesRotateInFifoOrderAcrossTheGlobalBudget() {
        final int[] visits = new int[100];
        final List<Integer> order = new ArrayList<>();
        for (int device = 0; device < visits.length; device++) {
            final int index = device;
            this.scheduler.submit(budget -> {
                assertTrue(budget.transaction());
                visits[index]++;
                order.add(index);
                this.time.advance(500_000);
                return true;
            });
        }
        for (int tick = 0; tick < 25; tick++) {
            this.scheduler.runTick();
        }
        assertEquals(100, order.size());
        for (int device = 0; device < visits.length; device++) {
            assertEquals(1, visits[device]);
            assertEquals(device, (int) order.get(device));
        }
        this.scheduler.runTick();
        for (int device = 0; device < visits.length; device++) {
            assertEquals(device < 4 ? 2 : 1, visits[device]);
        }
    }

    private static final class VirtualTime implements LongSupplier {

        private long nanos;

        @Override
        public long getAsLong() {
            return this.nanos;
        }

        void advance(final long amount) {
            this.nanos += amount;
        }
    }
}
