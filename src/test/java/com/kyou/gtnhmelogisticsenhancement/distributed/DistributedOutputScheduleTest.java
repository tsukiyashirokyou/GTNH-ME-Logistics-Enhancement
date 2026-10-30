package com.kyou.gtnhmelogisticsenhancement.distributed;

import static org.junit.Assert.*;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

/** Production shared scheduler with string identities and real NBT; no world/Grid or MSPT measurement. */
public class DistributedOutputScheduleTest {

    private static DistributedOutputState<String> state() {
        return new DistributedOutputState<>(
            1600,
            PartitionNbtTest.CODEC,
            card -> new DistributedOutputState.CapacityPolicy<String>() {

                @Override
                public boolean valid() {
                    return true;
                }

                @Override
                public int types() {
                    return 16;
                }

                @Override
                public long capacity(final int partitions) {
                    return 1600;
                }

                @Override
                public boolean accepts(final String identity) {
                    return true;
                }
            });
    }

    private static void receive(final DistributedOutputState<String> state, final String identity, final long amount) {
        final PartitionStore<String>.Transaction tx = state.store()
            .transaction(true);
        assertTrue(tx.offer(identity, amount));
        tx.commit();
    }

    private static void awaitStart(final DistributedOutputState<String> state) {
        for (int tick = 1; tick < state.period(); tick++) assertFalse(state.tick());
        assertTrue(state.tick());
    }

    @Test
    public void visitedSnapshotPositionsReleaseDrainedIdentitiesBeforeRoundEnd() throws Exception {
        final DistributedOutputState<String> state = state();
        for (int i = 0; i < 17; i++) receive(state, "K" + i, 100);
        state.flush((identity, amount) -> null, () -> {});
        assertTrue(state.roundActive());
        final java.lang.reflect.Field field = DistributedOutputState.class.getDeclaredField("outputRound");
        field.setAccessible(true);
        final java.util.List<?> snapshot = (java.util.List<?>) field.get(state);
        assertEquals(17, snapshot.size());
        for (int i = 0; i < 16; i++) assertNull(snapshot.get(i));
        assertEquals("K16", snapshot.get(16));
        assertEquals(
            1,
            state.store()
                .identityCount());
        assertTrue(state.tick());
        state.flush((identity, amount) -> {
            assertEquals("K16", identity);
            return null;
        }, () -> {});
        assertFalse(state.roundActive());
        assertEquals(
            0,
            state.store()
                .identityCount());
        assertNull(field.get(state));
    }

    @Test
    public void allRoundSizesVisitEachIdentityOnceWithSixteenPerTick() {
        for (final int size : new int[] { 1, 16, 17, 32, 5000 }) {
            final DistributedOutputState<String> state = state();
            for (int i = 0; i < size; i++) receive(state, "K" + i, 100);
            final Set<String> visited = new HashSet<>();
            awaitStart(state);
            int ticks = 0;
            do {
                if (ticks != 0) assertTrue(state.tick());
                final AtomicInteger calls = new AtomicInteger();
                state.flush((identity, amount) -> {
                    assertTrue("One attempt per identity per round", visited.add(identity));
                    calls.incrementAndGet();
                    return new DistributedOutputState.Remainder<>(identity, amount);
                }, () -> {});
                assertEquals(Math.min(16, size - ticks * 16), calls.get());
                ticks++;
            } while (state.roundActive());
            assertEquals((size + 15) / 16, ticks);
            assertEquals(size, visited.size());
            assertEquals(
                size * 100L,
                state.store()
                    .totalQuantity()
                    .longValueExact());
            for (int tick = 1; tick < 40; tick++) assertFalse(state.tick());
            assertTrue(state.tick());
        }
    }

    @Test
    public void snapshotDefersNewIdentitiesAndSkipsDeletedOnes() {
        final DistributedOutputState<String> state = state();
        for (int i = 0; i < 32; i++) receive(state, "K" + i, 100);
        state.flush((identity, amount) -> null, () -> {});
        receive(state, "NEW", 100);
        state.store()
            .remove("K16", 100);
        final Set<String> visited = new HashSet<>();
        assertTrue(state.tick());
        state.flush((identity, amount) -> {
            assertTrue(visited.add(identity));
            return null;
        }, () -> {});
        assertEquals(15, visited.size());
        assertFalse(visited.contains("K16"));
        assertFalse(visited.contains("NEW"));
        assertFalse(state.roundActive());
        assertEquals(
            100,
            state.store()
                .quantity("NEW"));
        awaitStart(state);
        state.flush((identity, amount) -> {
            assertEquals("NEW", identity);
            return null;
        }, () -> {});
        assertEquals(
            0,
            state.store()
                .identityCount());
    }

    @Test
    public void hugePartialAndRefusedIdentitiesDoNotBlockLaterWork() {
        final DistributedOutputState<String> state = state();
        receive(state, "huge", 5000000000L);
        receive(state, "partial", 100);
        receive(state, "reject", 100);
        receive(state, "accept", 100);
        final AtomicInteger calls = new AtomicInteger();
        state.flush((identity, amount) -> {
            calls.incrementAndGet();
            if (identity.equals("huge")) {
                assertEquals(Integer.MAX_VALUE, amount);
                return null;
            }
            if (identity.equals("partial")) return new DistributedOutputState.Remainder<>(identity, 30);
            if (identity.equals("reject")) return new DistributedOutputState.Remainder<>(identity, amount);
            return null;
        }, () -> {});
        assertEquals(4, calls.get());
        assertEquals(
            5000000000L - Integer.MAX_VALUE,
            state.store()
                .quantity("huge"));
        assertEquals(
            30,
            state.store()
                .quantity("partial"));
        assertEquals(
            100,
            state.store()
                .quantity("reject"));
        assertEquals(
            0,
            state.store()
                .quantity("accept"));
        assertFalse(state.roundActive());
        for (int tick = 1; tick < 40; tick++) assertFalse(state.tick());
        assertTrue(state.tick());
    }

    @Test
    public void periodEditPreservesActiveRoundAndControlsTheFollowingWait() {
        final DistributedOutputState<String> state = state();
        for (int i = 0; i < 17; i++) receive(state, "K" + i, 100);
        awaitStart(state);
        final AtomicInteger calls = new AtomicInteger();
        state.flush((identity, amount) -> {
            calls.incrementAndGet();
            return new DistributedOutputState.Remainder<>(identity, amount);
        }, () -> {});
        state.setPeriod(DistributedOutputState.ticksFromSeconds(3));
        assertTrue(state.roundActive());
        assertTrue(state.tick());
        state.flush((identity, amount) -> {
            calls.incrementAndGet();
            return new DistributedOutputState.Remainder<>(identity, amount);
        }, () -> {});
        assertEquals(17, calls.get());
        assertFalse(state.roundActive());
        for (int tick = 1; tick < 60; tick++) assertFalse(state.tick());
        assertTrue(state.tick());
    }

    @Test
    public void reloadStartsAtFirstUnvisitedIdentityWithoutResendingConfirmedStock() {
        final DistributedOutputState<String> state = state();
        for (int i = 0; i < 32; i++) receive(state, "K" + i, 100);
        state.flush((identity, amount) -> null, () -> {});
        final NBTTagCompound saved = state.write();
        assertEquals(
            5,
            saved.func_150296_c()
                .size()); // No serialized duplicate work queue.
        final DistributedOutputState<String> restored = state();
        restored.readState(saved, null);
        assertFalse(restored.roundActive());
        assertEquals(
            "K16",
            restored.store()
                .outputCursor());
        awaitStart(restored);
        restored.flush((identity, amount) -> {
            assertTrue(Integer.parseInt(identity.substring(1)) >= 16);
            return null;
        }, () -> {});
        assertEquals(
            0,
            restored.store()
                .identityCount());
    }

    @Test
    public void reloadAfterRefusalsRetainsFairnessAndEventuallyVisitsEveryIdentity() {
        final DistributedOutputState<String> state = state();
        for (int i = 0; i < 32; i++) receive(state, "K" + i, 100);
        state.flush((identity, amount) -> new DistributedOutputState.Remainder<>(identity, amount), () -> {});
        final DistributedOutputState<String> restored = state();
        restored.readState(state.write(), null);
        assertEquals(
            "K16",
            restored.store()
                .outputCursor());
        awaitStart(restored);
        final Set<String> visited = new HashSet<>();
        do {
            restored.flush((identity, amount) -> {
                assertTrue(visited.add(identity));
                return new DistributedOutputState.Remainder<>(identity, amount);
            }, () -> {});
        } while (restored.roundActive() && restored.tick());
        assertEquals(32, visited.size());
    }

    @Test
    public void uncertainDeliveryStillStopsTheRoundAndNeverReplaysOnReload() {
        final DistributedOutputState<String> state = state();
        for (int i = 0; i < 17; i++) receive(state, "K" + i, 100);
        final AtomicInteger calls = new AtomicInteger();
        state.flush((identity, amount) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("unknown side effect");
        }, () -> {});
        assertTrue(state.locked());
        assertFalse(state.roundActive());
        assertEquals(1, calls.get());
        final DistributedOutputState<String> restored = state();
        restored.readState(state.write(), null);
        assertTrue(restored.locked());
        assertFalse(restored.tick());
        assertFalse(restored.flush((identity, amount) -> {
            fail("No replay");
            return null;
        }, () -> {}));
        assertEquals(
            1700,
            restored.store()
                .totalQuantity()
                .longValueExact());
    }

    @Test
    public void secondsRoundTripEveryLegacyTickBoundaryWithoutOverflow() {
        final DistributedOutputState<String> state = state();
        assertEquals(2, state.periodSeconds(), 0);
        assertEquals(40, DistributedOutputState.ticksFromSeconds(2));
        for (final int ticks : new int[] { 40, 41, 59, 60, 2147483640, Integer.MAX_VALUE }) {
            state.setPeriod(ticks);
            assertEquals(ticks, DistributedOutputState.ticksFromSeconds(state.periodSeconds()));
            final DistributedOutputState<String> restored = state();
            restored.readState(state.write(), null);
            assertEquals(ticks, restored.period());
        }
        assertEquals(
            Integer.MAX_VALUE,
            DistributedOutputState.ticksFromSeconds(DistributedOutputState.MAX_PERIOD_SECONDS));
        for (final double invalid : new double[] { 0, 1.99, Double.NaN, Double.NEGATIVE_INFINITY,
            Double.POSITIVE_INFINITY, DistributedOutputState.MAX_PERIOD_SECONDS + 0.05 })
            assertThrows(IllegalArgumentException.class, () -> DistributedOutputState.ticksFromSeconds(invalid));
    }
}
