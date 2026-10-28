package com.kyou.metransferdrive.transfer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Audit witness for the current energy fairness boundary, not an assertion that starvation is desirable. Time and
 * energy providers are deterministic contracts; this is not a Minecraft performance measurement.
 */
public class ReleaseEnergyAuditTest {

    @Test
    public void completeCheapFifoRoundsCanKeepTheSameGridEnergyWinnerForEveryTick() {
        final TransferScheduler scheduler = new TransferScheduler(() -> 0);
        final EnergySettlementTest.Power power = new EnergySettlementTest.Power();
        power.power = power.capacity = 1000;
        final Object grid = new Object();
        final int[] visits = new int[2];
        final long[] moved = new long[2];
        final long[] remaining = { 1_000_000, 1_000_000 };
        for (int device = 0; device < 2; device++) {
            final int index = device;
            final TransferEnergyAccount account = new TransferEnergyAccount(() -> {}, TransferPowerBudget.SERVER);
            final TransferTransaction<String> transaction = new TransferTransaction<>(() -> {});
            scheduler.submit(slice -> {
                visits[index]++;
                if (slice.transaction()) {
                    moved[index] += transaction.move("item", remaining[index], new TransferTransaction.Source() {

                        @Override
                        public long extract(final long amount) {
                            remaining[index] -= amount;
                            return amount;
                        }

                        @Override
                        public long restore(final long amount) {
                            remaining[index] += amount;
                            return amount;
                        }
                    }, new TransferTransaction.Destination() {

                        @Override
                        public long simulate(final long amount) {
                            return amount;
                        }

                        @Override
                        public long insert(final long amount) {
                            return amount;
                        }
                    }, account.bind(grid, power, 1, 1));
                }
                return true;
            });
        }
        for (int tick = 0; tick < 100; tick++) {
            // Model an external supply restoring the same headroom before each server work Tick.
            power.power = 1000;
            scheduler.runTick();
        }
        assertEquals(100, visits[0]);
        assertEquals(100, visits[1]);
        assertEquals(10_000, moved[0]);
        assertEquals(0, moved[1]);
        assertEquals(1_000_000, remaining[0] + moved[0]);
        assertEquals(1_000_000, remaining[1] + moved[1]);
        assertEquals(10_000, power.debited, 0);
    }
}
