package com.kyou.gtnhmelogisticsenhancement.distributed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

public class EmergencyPartitionTest {

    @Test
    public void protectedSharedLastPartitionReceivesWithoutOpeningFutureAdmission() {
        final PartitionStore<String> store = new PartitionStore<>(1, 400);
        final PartitionStore<String>.Transaction first = store.transaction(true);
        final PartitionStore<String>.Transaction second = store.transaction(true);
        first.offer("A", 1000);
        second.offer("B", 50);
        first.commit();
        second.commit();
        assertEquals(1000, store.quantity("A"));
        assertEquals(50, store.quantity("B"));
        assertEquals(2, store.identityCount());
        assertEquals(
            1,
            store.normalEntries()
                .size());
        assertEquals(1, store.overflowIdentityCount());
        assertFalse(
            store.transaction()
                .offer("B", 1));
        assertFalse(
            store.transaction()
                .offer("C", 1));
    }

    @Test
    public void sameIdentityEmergencyMergesAndIndependentProtectedTransactionsCountExactlyOnce() {
        final PartitionStore<String> store = new PartitionStore<>(1, 400);
        PartitionStoreTest.add(store, "A", 350);
        final PartitionStore<String>.Transaction first = store.transaction(true);
        final PartitionStore<String>.Transaction second = store.transaction(true);
        first.offer("A", 1000);
        second.offer("A", 1000);
        first.commit();
        second.commit();
        assertEquals(2350, store.quantity("A"));
        assertEquals(1, store.identityCount());
        assertEquals(0, store.overflowIdentityCount());
        assertTrue(store.hasEmergencyStock());
        assertFalse(
            store.transaction()
                .offer("A", 1));
        assertThrows(IllegalStateException.class, second::commit);
        assertEquals(2350, store.quantity("A"));
    }

    @Test
    public void hundredsOfEmergencyTypesDoNotConsumeOrInventNormalDisplaySlots() {
        final PartitionStore<String> store = new PartitionStore<>(16, 1600);
        final PartitionStore<String>.Transaction output = store.transaction(true);
        for (int i = 0; i < 300; i++) assertTrue(output.offer("K" + i, i + 1));
        output.commit();
        assertEquals(300, store.identityCount());
        assertEquals(
            16,
            store.normalEntries()
                .size());
        assertEquals(284, store.overflowIdentityCount());
        assertEquals(BigInteger.valueOf(300L * 301 / 2), store.totalQuantity());
        assertFalse(
            store.transaction()
                .offer("new", 1));
        assertFalse(
            store.transaction()
                .offer("K299", 1));
    }

    @Test
    public void drainingPromotesOneIdentityWithoutDuplicatingItsQuantity() {
        final PartitionStore<String> store = new PartitionStore<>(1, 400);
        emergency(store, "A", 1000);
        emergency(store, "B", 50);
        emergency(store, "C", 80);
        store.remove("A", 999);
        assertEquals(2, store.overflowIdentityCount());
        store.remove("A", 1);
        assertEquals(1, store.overflowIdentityCount());
        assertEquals(
            "B",
            store.normalEntries()
                .get(0).identity);
        assertEquals(50, store.quantity("B"));
        assertTrue(
            store.transaction()
                .offer("B", 1));
        assertFalse(
            store.transaction()
                .offer("new", 1));
        assertFalse(
            store.transaction()
                .offer("C", 1));
        store.remove("B", 50);
        assertEquals(
            "C",
            store.normalEntries()
                .get(0).identity);
        assertEquals(0, store.overflowIdentityCount());
        assertFalse(store.hasEmergencyStock());
        store.remove("C", 80);
        assertTrue(
            store.transaction()
                .offer("new", 1000));
    }

    @Test
    public void quotaEmergencyWarningClearsOnlyAfterItsQuantityDropsBelowQuota() {
        final PartitionStore<String> store = new PartitionStore<>(1, 400);
        PartitionStoreTest.add(store, "A", 1350);
        emergency(store, "A", 1000);
        store.remove("A", 1950);
        assertTrue(store.hasEmergencyStock());
        assertFalse(
            store.transaction()
                .offer("A", 1));
        store.remove("A", 1);
        assertFalse(store.hasEmergencyStock());
        assertTrue(
            store.transaction()
                .offer("A", 1000));
    }

    @Test
    public void shrinkingAndRemovingCapacityBetweenPlanAndCommitRebuildsProtectedPlan() {
        final PartitionStore<String> store = new PartitionStore<>(4, 1600);
        final PartitionStore<String>.Transaction output = store.transaction(true);
        output.offer("A", 1000);
        output.offer("B", 2000);
        store.configure(1, 0);
        store.setAdmissionFilter(key -> false);
        output.commit();
        assertEquals(
            3000,
            store.totalQuantity()
                .longValueExact());
        assertEquals(
            1,
            store.normalEntries()
                .size());
        assertEquals(1, store.overflowIdentityCount());
        assertFalse(
            store.transaction()
                .offer("A", 1));
        assertFalse(
            store.transaction()
                .offer("C", 1));
        store.configure(4, 1600);
        store.setAdmissionFilter(key -> true);
        assertEquals(
            2,
            store.normalEntries()
                .size());
        assertEquals(0, store.overflowIdentityCount());
        assertEquals(
            3000,
            store.totalQuantity()
                .longValueExact());
    }

    @Test
    public void changedFilterRejectsStrictInputsButNeverErasesOrBlocksDrainingOldStock() {
        final PartitionStore<String> store = new PartitionStore<>(1600);
        PartitionStoreTest.add(store, "A", 350);
        store.setAdmissionFilter(key -> false);
        assertFalse(
            store.transaction()
                .offer("A", 1));
        assertFalse(
            store.transaction()
                .offer("B", 1));
        emergency(store, "A", 1000);
        emergency(store, "B", 1000);
        assertEquals(
            2350,
            store.totalQuantity()
                .longValueExact());
        store.remove("A", 1350);
        store.remove("B", 1000);
        assertEquals(0, store.identityCount());
    }

    @Test
    public void staleStrictPlansRevalidateOnlyWhenEveryDeltaStillFits() {
        final PartitionStore<String> store = new PartitionStore<>(4, 1600);
        final PartitionStore<String>.Transaction first = store.transaction();
        final PartitionStore<String>.Transaction second = store.transaction();
        first.offer("A", 1000);
        second.offer("B", 2000);
        first.commit();
        second.commit();
        assertEquals(
            3000,
            store.totalQuantity()
                .longValueExact());
        final PartitionStore<String>.Transaction rejected = store.transaction();
        rejected.offer("C", 500);
        store.configure(1, 400);
        final PartitionStore.CommitConflictException problem = assertThrows(
            PartitionStore.CommitConflictException.class,
            rejected::commit);
        assertEquals(
            Long.valueOf(500),
            problem.pending()
                .get("C"));
        assertEquals(0, store.quantity("C"));
        assertNull(store.failure());
    }

    @Test
    public void revalidationOverflowNeverCommitsEarlierIdentitiesAndKeepsAllEvidence() {
        final PartitionStore<String> store = new PartitionStore<>(1, 400);
        PartitionStoreTest.add(store, "A", 350);
        final PartitionStore<String>.Transaction pending = store.transaction(true);
        pending.offer("B", 10);
        pending.offer("A", 1);
        emergency(store, "A", Long.MAX_VALUE - 350);
        assertThrows(ArithmeticException.class, pending::commit);
        assertEquals(Long.MAX_VALUE, store.quantity("A"));
        assertEquals(0, store.quantity("B"));
        assertNotNull(store.failure());
        assertEquals(Long.valueOf(10), store.failure().pending.get("B"));
        assertEquals(Long.valueOf(1), store.failure().pending.get("A"));
        assertThrows(IllegalStateException.class, store::transaction);
        assertThrows(IllegalStateException.class, pending::commit);
        store.remove("A", 1);
        assertEquals(Long.MAX_VALUE - 1, store.quantity("A"));
    }

    @Test
    public void synchronousFilterMutationCannotPublishAnOutdatedPlan() {
        final PartitionStore<String> store = new PartitionStore<>(1600);
        store.setAdmissionFilter(key -> {
            store.configure(1, 200);
            return true;
        });
        final PartitionStore<String>.Transaction output = store.transaction(true);
        assertThrows(PartitionStore.CommitConflictException.class, () -> output.offer("A", 1000));
        assertEquals(0, store.quantity("A"));
        assertNotNull(store.failure());
        assertEquals(Long.valueOf(1000), store.failure().pending.get("A"));
    }

    @Test
    public void boundedRoundRobinIncludesEveryEmergencyAndSurvivesMutationAndPromotion() {
        final PartitionStore<String> store = new PartitionStore<>(1, 400);
        final PartitionStore<String>.Transaction tx = store.transaction(true);
        for (int i = 0; i < 101; i++) tx.offer("K" + i, i + 1);
        tx.commit();
        final long version = store.cacheVersion();
        final Set<String> visited = new HashSet<>();
        for (int round = 0; round < 15; round++) {
            for (final PartitionStore.Entry<String> entry : store.nextOutputBatch(7)) visited.add(entry.identity);
        }
        assertEquals(101, visited.size());
        assertEquals(version, store.cacheVersion());
        long delivered = 0;
        while (store.identityCount() > 0) {
            for (final PartitionStore.Entry<String> entry : store.nextOutputBatch(3)) {
                delivered += entry.quantity;
                store.remove(entry.identity, entry.quantity);
            }
        }
        assertEquals(5151, delivered);
        assertEquals(0, store.overflowIdentityCount());
        assertFalse(store.hasEmergencyStock());
    }

    @Test
    public void transactionSetupAndOneReceiptDoNotHashEveryEmergencyIdentity() {
        final PartitionStore<CountedKey> store = new PartitionStore<>(1, 400);
        final PartitionStore<CountedKey>.Transaction bulk = store.transaction(true);
        for (int i = 0; i < 5000; i++) bulk.offer(new CountedKey(i), 1);
        bulk.commit();
        CountedKey.calls = 0;
        final PartitionStore<CountedKey>.Transaction next = store.transaction(true);
        assertTrue(next.hasAvailableSpace());
        next.offer(new CountedKey(5000), 1);
        next.commit();
        assertTrue(
            "Touched key operations must be bounded independently of 5000 stored identities",
            CountedKey.calls < 128);
        assertEquals(5001, store.identityCount());
    }

    private static final class CountedKey {

        private static int calls;
        private final int value;

        private CountedKey(final int value) {
            this.value = value;
        }

        @Override
        public int hashCode() {
            calls++;
            return this.value;
        }

        @Override
        public boolean equals(final Object other) {
            calls++;
            return other instanceof CountedKey && ((CountedKey) other).value == this.value;
        }
    }

    static <K> void emergency(final PartitionStore<K> store, final K identity, final long quantity) {
        final PartitionStore<K>.Transaction tx = store.transaction(true);
        assertTrue(tx.hasAvailableSpace());
        assertTrue(tx.offer(identity, quantity));
        tx.commit();
    }
}
