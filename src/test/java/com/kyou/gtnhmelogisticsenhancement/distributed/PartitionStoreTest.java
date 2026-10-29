package com.kyou.gtnhmelogisticsenhancement.distributed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.Test;

public class PartitionStoreTest {

    @Test
    public void defaultsAndAllPartitionCountsHaveIndependentQuotas() {
        assertEquals(400, new PartitionStore<String>(PartitionStore.ITEM_CAPACITY).quota());
        assertEquals(32000, new PartitionStore<String>(PartitionStore.FLUID_CAPACITY).quota());
        for (int n = 1; n <= 16; n++) {
            final PartitionStore<String> store = new PartitionStore<>(n, 1600);
            final PartitionStore<String>.Transaction tx = store.transaction();
            for (int i = 0; i < n; i++) assertTrue(tx.offer("key" + i, 1000000));
            assertFalse(tx.offer("extra", 1));
            tx.commit();
            assertEquals(
                n,
                store.entries()
                    .size());
            assertEquals(BigInteger.valueOf(n * 1000000L), store.totalQuantity());
        }
    }

    @Test
    public void fullLegalOutputOverfillsOnlyItsIdentity() {
        final PartitionStore<String> store = new PartitionStore<>(1600);
        add(store, "A", 350);
        add(store, "A", 1000);
        assertEquals(1350, store.quantity("A"));
        final PartitionStore<String>.Transaction next = store.transaction();
        assertFalse(next.offer("A", 1));
        assertTrue(next.offer("B", 1000));
        assertTrue(next.offer("C", 1000));
        next.commit();
        assertEquals(1000, store.quantity("B"));
        assertEquals(1000, store.quantity("C"));
    }

    @Test
    public void repeatedIdentityAndLargeFluidChunksRemainAuthorizedUntilComplete() {
        final PartitionStore<String> store = new PartitionStore<>(128000);
        final PartitionStore<String>.Transaction tx = store.transaction();
        assertTrue(tx.offer("fluid", Integer.MAX_VALUE));
        assertTrue(tx.hasAvailableSpace());
        assertTrue(tx.offer("fluid", Integer.MAX_VALUE));
        assertTrue(tx.offer("fluid", 17));
        tx.complete("fluid");
        assertThrows(IllegalStateException.class, () -> tx.offer("fluid", 1));
        tx.commit();
        assertEquals(Integer.MAX_VALUE * 2L + 17, store.quantity("fluid"));
        assertEquals(
            1,
            store.entries()
                .size());
    }

    @Test
    public void simulationsAndSameTickIndependentTransactionsNeverGrantPersistentAdmission() {
        final PartitionStore<String> store = new PartitionStore<>(1, 400);
        add(store, "A", 350);
        final PartitionStore<String>.Transaction simulation = store.transaction();
        assertTrue(simulation.offer("A", 1000));
        assertEquals(350, store.quantity("A"));
        add(store, "A", 1000);
        assertFalse(
            store.transaction()
                .offer("A", 1));
        assertThrows(PartitionStore.CommitConflictException.class, simulation::commit);
        assertEquals(1350, store.quantity("A"));
    }

    @Test
    public void staleCommitRetainsExactEvidenceAndCannotReplayOrOverwrite() {
        final PartitionStore<String> store = new PartitionStore<>(1, 400);
        final PartitionStore<String>.Transaction first = store.transaction();
        final PartitionStore<String>.Transaction second = store.transaction();
        first.offer("A", 1000);
        second.offer("B", 2000);
        first.commit();
        final PartitionStore.CommitConflictException failure = assertThrows(
            PartitionStore.CommitConflictException.class,
            second::commit);
        assertEquals(
            Long.valueOf(2000),
            failure.pending()
                .get("B"));
        assertEquals(
            Long.valueOf(2000),
            second.pending()
                .get("B"));
        assertThrows(
            UnsupportedOperationException.class,
            () -> failure.pending()
                .clear());
        assertThrows(IllegalStateException.class, second::commit);
        assertEquals(1000, store.quantity("A"));
        assertEquals(0, store.quantity("B"));
    }

    @Test
    public void changingConfigurationAndDrainingInvalidateSnapshots() {
        final PartitionStore<String> store = new PartitionStore<>(1600);
        add(store, "A", 100);
        final PartitionStore<String>.Transaction stale = store.transaction();
        stale.offer("A", 50);
        store.remove("A", 1);
        stale.commit();
        assertEquals(149, store.quantity("A"));
        final PartitionStore<String>.Transaction staleConfig = store.transaction();
        staleConfig.offer("B", 50);
        store.configure(1, 100);
        assertThrows(PartitionStore.CommitConflictException.class, staleConfig::commit);
        assertEquals(149, store.quantity("A"));
    }

    @Test
    public void shrinkingPreservesSlotsAndAllLegacyOccupantsUntilNaturalDrain() {
        final PartitionStore<String> store = new PartitionStore<>(4, 1600);
        for (int i = 0; i < 4; i++) add(store, "K" + i, 1000);
        store.configure(1, 50);
        assertEquals(
            4,
            store.entries()
                .size());
        assertEquals(
            4000,
            store.totalQuantity()
                .longValueExact());
        assertFalse(
            store.transaction()
                .offer("new", 1));
        assertFalse(
            store.transaction()
                .offer("K0", 1));
        for (int i = 0; i < 4; i++) {
            assertEquals(
                i,
                store.entries()
                    .get(i).slot);
        }
        for (int i = 0; i < 4; i++) store.remove("K" + i, 1000);
        add(store, "new", 1000);
        assertEquals(
            0,
            store.entries()
                .get(0).slot);
    }

    @Test
    public void quotaRecoveryOnlyEnablesTheDrainedIdentity() {
        final PartitionStore<String> store = new PartitionStore<>(1, 400);
        add(store, "A", 1350);
        store.remove("A", 950);
        assertFalse(
            store.transaction()
                .offer("A", 1));
        store.remove("A", 1);
        add(store, "A", 1000);
        assertEquals(1399, store.quantity("A"));
    }

    @Test
    public void allLongArithmeticIsCheckedWithoutPartialMutation() {
        final PartitionStore<String> store = new PartitionStore<>(4, Long.MAX_VALUE);
        final PartitionStore<String>.Transaction tx = store.transaction();
        tx.offer("A", Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> tx.offer("A", 1));
        tx.offer("B", Long.MAX_VALUE);
        tx.commit();
        assertEquals(
            BigInteger.valueOf(Long.MAX_VALUE)
                .multiply(BigInteger.valueOf(2)),
            store.totalQuantity());
        assertThrows(IllegalArgumentException.class, () -> store.remove("A", -1));
        assertThrows(
            IllegalArgumentException.class,
            () -> store.transaction()
                .offer("C", -1));
        assertThrows(
            IllegalArgumentException.class,
            () -> store.transaction()
                .offer("C", 0));
        assertEquals(Long.MAX_VALUE, store.quantity("A"));
    }

    @Test
    public void zeroQuotaAndInvalidConfigurationsDoNotCreateStock() {
        final PartitionStore<String> store = new PartitionStore<>(16, 1);
        assertFalse(
            store.transaction()
                .hasAvailableSpace());
        assertFalse(
            store.transaction()
                .offer("A", 1));
        assertThrows(IllegalArgumentException.class, () -> store.configure(0, 1));
        assertThrows(IllegalArgumentException.class, () -> store.configure(17, 1));
        assertThrows(IllegalArgumentException.class, () -> store.configure(1, -1));
    }

    @Test
    public void completeRetainsAcceptedStockAndSpentTransactionsCannotBeReused() {
        final PartitionStore<String> store = new PartitionStore<>(1600);
        final PartitionStore<String>.Transaction tx = store.transaction();
        tx.offer("A", 1000);
        tx.complete("A");
        tx.commit();
        assertEquals(1000, store.quantity("A"));
        assertThrows(IllegalStateException.class, tx::commit);
        assertThrows(IllegalStateException.class, tx::hasAvailableSpace);
        assertThrows(IllegalStateException.class, () -> tx.complete("B"));
        assertThrows(IllegalStateException.class, () -> tx.offer("B", 1));
    }

    @Test
    public void seededAlternatingAdmissionsAndRemovalsConserveQuantities() {
        final PartitionStore<String> store = new PartitionStore<>(4, 1600);
        final Map<String, Long> expected = new HashMap<>();
        final Random random = new Random(20261029);
        for (int i = 0; i < 5000; i++) {
            final String key = "K" + random.nextInt(8);
            final long before = expected.getOrDefault(key, 0L);
            final int amount = random.nextInt(1000) + 1;
            if (random.nextBoolean() && before > 0) {
                final long removed = Math.min(before, amount);
                store.remove(key, removed);
                if (before == removed) expected.remove(key);
                else expected.put(key, before - removed);
            } else {
                final PartitionStore<String>.Transaction tx = store.transaction();
                final boolean allowed = before < 400 && (before > 0 || expected.size() < 4);
                assertEquals(allowed, tx.offer(key, amount));
                tx.commit();
                if (allowed) expected.put(key, before + amount);
            }
            for (int k = 0; k < 8; k++) assertEquals(
                expected.getOrDefault("K" + k, 0L)
                    .longValue(),
                store.quantity("K" + k));
            assertEquals(
                expected.size(),
                store.entries()
                    .size());
        }
    }

    static <K> void add(final PartitionStore<K> store, final K key, final long amount) {
        final PartitionStore<K>.Transaction tx = store.transaction();
        assertTrue(tx.offer(key, amount));
        tx.commit();
    }
}
