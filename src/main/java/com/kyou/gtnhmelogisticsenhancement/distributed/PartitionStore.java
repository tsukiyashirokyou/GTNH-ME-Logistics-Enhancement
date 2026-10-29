package com.kyou.gtnhmelogisticsenhancement.distributed;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Main-thread-only virtual output stock. Keys must be immutable, quantity-independent identities. */
public final class PartitionStore<K> {

    public static final int MAX_PARTITIONS = 16;
    public static final long ITEM_CAPACITY = 1600;
    public static final long FLUID_CAPACITY = 128000;

    private final Map<K, Entry<K>> index = new HashMap<>();
    private final List<Entry<K>> slots = new ArrayList<>(Collections.nCopies(MAX_PARTITIONS, null));
    private int partitions;
    private long capacity;
    private long configurationVersion;
    private long cacheVersion;

    public PartitionStore(final long capacity) {
        this(4, capacity);
    }

    public PartitionStore(final int partitions, final long capacity) {
        validateConfiguration(partitions, capacity);
        this.partitions = partitions;
        this.capacity = capacity;
    }

    public int partitions() {
        return this.partitions;
    }

    public long capacity() {
        return this.capacity;
    }

    public long quota() {
        return this.capacity / this.partitions;
    }

    public long configurationVersion() {
        return this.configurationVersion;
    }

    public long cacheVersion() {
        return this.cacheVersion;
    }

    public long quantity(final K identity) {
        final Entry<K> entry = this.index.get(identity);
        return entry == null ? 0 : entry.quantity;
    }

    public List<Entry<K>> entries() {
        final List<Entry<K>> result = new ArrayList<>(this.index.size());
        for (final Entry<K> entry : this.slots) {
            if (entry != null) result.add(entry);
        }
        return Collections.unmodifiableList(result);
    }

    public BigInteger totalQuantity() {
        BigInteger total = BigInteger.ZERO;
        for (final Entry<K> entry : this.index.values()) total = total.add(BigInteger.valueOf(entry.quantity));
        return total;
    }

    /** Shrinking changes future admission, never existing stock or its display slots. */
    public void configure(final int partitions, final long capacity) {
        validateConfiguration(partitions, capacity);
        if (partitions == this.partitions && capacity == this.capacity) return;
        final long next = Math.incrementExact(this.configurationVersion);
        this.partitions = partitions;
        this.capacity = capacity;
        this.configurationVersion = next;
    }

    /** Caller supplies only a confirmed delivered quantity; no network operations occur here. */
    public void remove(final K identity, final long amount) {
        final Entry<K> entry = this.index.get(identity);
        if (entry == null || amount <= 0 || amount > entry.quantity) {
            throw new IllegalArgumentException("Invalid confirmed removal");
        }
        final long next = Math.incrementExact(this.cacheVersion);
        if (amount == entry.quantity) {
            this.index.remove(identity);
            this.slots.set(entry.slot, null);
        } else this.put(new Entry<>(entry.slot, identity, entry.quantity - amount));
        this.cacheVersion = next;
    }

    public Transaction transaction() {
        return new Transaction();
    }

    private void put(final Entry<K> entry) {
        this.index.put(entry.identity, entry);
        this.slots.set(entry.slot, entry);
    }

    static <K> PartitionStore<K> restore(final int partitions, final long capacity, final long configurationVersion,
        final long cacheVersion, final List<Entry<K>> entries) {
        if (configurationVersion < 0 || cacheVersion < 0 || entries.size() > MAX_PARTITIONS) {
            throw new IllegalArgumentException("Invalid saved version or partition count");
        }
        final PartitionStore<K> store = new PartitionStore<>(partitions, capacity);
        for (final Entry<K> entry : entries) {
            if (entry.slot < 0 || entry.slot >= MAX_PARTITIONS
                || entry.quantity <= 0
                || entry.identity == null
                || store.index.containsKey(entry.identity)
                || store.slots.get(entry.slot) != null) {
                throw new IllegalArgumentException("Invalid or duplicate saved partition");
            }
            store.put(entry);
        }
        store.configurationVersion = configurationVersion;
        store.cacheVersion = cacheVersion;
        return store;
    }

    private static void validateConfiguration(final int partitions, final long capacity) {
        if (partitions < 1 || partitions > MAX_PARTITIONS || capacity < 0) {
            throw new IllegalArgumentException("Invalid partition configuration");
        }
    }

    public static final class Entry<K> {

        public final int slot;
        public final K identity;
        public final long quantity;

        Entry(final int slot, final K identity, final long quantity) {
            this.slot = slot;
            this.identity = identity;
            this.quantity = quantity;
        }
    }

    /** A conflict retains delivery evidence; it must not be caught and treated as success. */
    public static final class CommitConflictException extends IllegalStateException {

        private static final long serialVersionUID = 1L;
        private final Map<?, Long> pending;

        CommitConflictException(final Map<?, Long> pending) {
            super("Partition transaction changed before commit; producer must retain undelivered outputs");
            this.pending = Collections.unmodifiableMap(new LinkedHashMap<>(pending));
        }

        public Map<?, Long> pending() {
            return this.pending;
        }
    }

    public final class Transaction {

        private final long configuration = PartitionStore.this.configurationVersion;
        private final long version = PartitionStore.this.cacheVersion;
        private final long quota = PartitionStore.this.quota();
        private final int limit = PartitionStore.this.partitions;
        private final Map<K, Entry<K>> snapshot = new HashMap<>(PartitionStore.this.index);
        private final Map<K, Long> pending = new LinkedHashMap<>();
        private final Map<K, Integer> allocated = new HashMap<>();
        private final Set<K> completed = new HashSet<>();
        private boolean active = true;

        public Map<K, Long> pending() {
            return Collections.unmodifiableMap(this.pending);
        }

        public boolean hasAvailableSpace() {
            this.requireActive();
            for (final K key : this.pending.keySet()) {
                if (!this.completed.contains(key) && this.currentQuantity(key) < Long.MAX_VALUE) return true;
            }
            if (this.quota == 0) return false;
            if (this.snapshot.size() + this.allocated.size() < this.limit) return true;
            for (final Entry<K> entry : this.snapshot.values()) {
                if (!this.completed.contains(entry.identity) && !this.pending.containsKey(entry.identity)
                    && entry.quantity < this.quota) return true;
            }
            return false;
        }

        /** All-or-nothing per call. Quota authorizes the identity, not a remaining-space truncation. */
        public boolean offer(final K identity, final long amount) {
            this.requireActive();
            Objects.requireNonNull(identity, "identity");
            if (amount <= 0) throw new IllegalArgumentException("Non-positive output quantity");
            if (this.completed.contains(identity)) throw new IllegalStateException("Identity already completed");
            final boolean admitted = this.pending.containsKey(identity);
            final Entry<K> existing = this.snapshot.get(identity);
            if (!admitted && (this.quota == 0 || (existing != null && existing.quantity >= this.quota)
                || (existing == null && this.snapshot.size() + this.allocated.size() >= this.limit))) return false;
            Math.addExact(this.currentQuantity(identity), amount);
            final long sum = Math.addExact(this.pending.getOrDefault(identity, 0L), amount);
            if (!admitted && existing == null) this.allocated.put(identity, this.freeSlot());
            this.pending.put(identity, sum);
            return true;
        }

        public void complete(final K identity) {
            this.requireActive();
            this.completed.add(Objects.requireNonNull(identity, "identity"));
        }

        /** Validation precedes every mutation. No stale snapshot overwrite or automatic retry. */
        public void commit() {
            this.requireActive();
            this.active = false;
            if (this.configuration != PartitionStore.this.configurationVersion
                || this.version != PartitionStore.this.cacheVersion) throw new CommitConflictException(this.pending);
            if (this.pending.isEmpty()) return;
            final long next = Math.incrementExact(PartitionStore.this.cacheVersion);
            final List<Entry<K>> changes = new ArrayList<>(this.pending.size());
            for (final Map.Entry<K, Long> delta : this.pending.entrySet()) {
                final Entry<K> original = this.snapshot.get(delta.getKey());
                final int slot = original == null ? this.allocated.get(delta.getKey()) : original.slot;
                changes.add(new Entry<>(slot, delta.getKey(), this.currentQuantity(delta.getKey())));
            }
            for (final Entry<K> entry : changes) PartitionStore.this.put(entry);
            PartitionStore.this.cacheVersion = next;
        }

        private long currentQuantity(final K identity) {
            final Entry<K> original = this.snapshot.get(identity);
            return Math.addExact(original == null ? 0 : original.quantity, this.pending.getOrDefault(identity, 0L));
        }

        private int freeSlot() {
            // Snapshot occupancy, not live stock: intervening mutations cause an explicit commit conflict.
            for (int slot = 0; slot < MAX_PARTITIONS; slot++) {
                boolean used = this.allocated.containsValue(slot);
                for (final Entry<K> entry : this.snapshot.values()) used |= entry.slot == slot;
                if (!used) return slot;
            }
            throw new IllegalStateException("No partition display slot");
        }

        private void requireActive() {
            if (!this.active) throw new IllegalStateException("Partition transaction is spent");
        }
    }
}
