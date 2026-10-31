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
import java.util.function.Predicate;

/** Main-thread-only stock. Identities, including their NBT, must remain immutable after insertion. */
public final class PartitionStore<K> {

    public static final int MAX_PARTITIONS = 16;
    public static final long ITEM_CAPACITY = 1600;
    public static final long FLUID_CAPACITY = 128000;

    private final Map<K, Entry<K>> index = new LinkedHashMap<>();
    private final Map<K, Entry<K>> overflow = new LinkedHashMap<>();
    private final List<Entry<K>> slots = new ArrayList<>(Collections.nCopies(MAX_PARTITIONS, null));
    private int occupied;
    private int emergencyCount;
    private int partitions;
    private int typeLimit = MAX_PARTITIONS;
    private BigInteger total = BigInteger.ZERO;
    private BigInteger emergencyTotal = BigInteger.ZERO;
    private long capacity;
    private long configurationVersion;
    private long cacheVersion;
    private Predicate<? super K> filter = key -> true;
    private Failure<K> failure;

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
        final int effective = this.effectivePartitions();
        return effective == 0 ? 0 : this.capacity / effective;
    }

    public int effectivePartitions() {
        return Math.min(this.partitions, this.typeLimit);
    }

    public long configurationVersion() {
        return this.configurationVersion;
    }

    public long cacheVersion() {
        return this.cacheVersion;
    }

    public int identityCount() {
        return this.index.size();
    }

    public int overflowIdentityCount() {
        return this.overflow.size();
    }

    public int emergencyIdentityCount() {
        return this.emergencyCount;
    }

    public boolean hasEmergencyStock() {
        return this.emergencyCount != 0;
    }

    public BigInteger emergencyQuantity() {
        return this.emergencyTotal;
    }

    public Failure<K> failure() {
        return this.failure;
    }

    public long quantity(final K identity) {
        final Entry<K> entry = this.index.get(identity);
        return entry == null ? 0 : entry.quantity;
    }

    /** Full diagnostic/save snapshot. Use nextOutputBatch for bounded future network work. */
    public List<Entry<K>> entries() {
        final List<Entry<K>> result = new ArrayList<>(this.index.size());
        result.addAll(this.normalEntries());
        result.addAll(this.overflow.values());
        return Collections.unmodifiableList(result);
    }

    /** At most sixteen stable display positions; emergency identities remain available through the common index. */
    public List<Entry<K>> normalEntries() {
        final List<Entry<K>> result = new ArrayList<>(MAX_PARTITIONS);
        for (final Entry<K> entry : this.slots) if (entry != null) result.add(entry);
        return Collections.unmodifiableList(result);
    }

    /** O(limit) round-robin snapshot including overflow; changing iteration order does not change stock/version. */
    public List<Entry<K>> nextOutputBatch(final int maximum) {
        if (maximum <= 0) throw new IllegalArgumentException("Non-positive output budget");
        final int count = Math.min(maximum, this.index.size());
        final List<Entry<K>> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final Entry<K> entry = this.index.values()
                .iterator()
                .next();
            this.index.remove(entry.identity);
            this.index.put(entry.identity, entry);
            result.add(entry);
        }
        return Collections.unmodifiableList(result);
    }

    /** Stable identity-only work list; no live iterator or duplicate quantities are retained across ticks. */
    List<K> outputSnapshot() {
        return new ArrayList<>(this.index.keySet());
    }

    /** Persist fairness after each visited identity, including refused identities, without changing stock. */
    void advanceOutputCursor(final K identity) {
        final Entry<K> entry = this.index.remove(identity);
        if (entry != null) this.index.put(identity, entry);
    }

    public BigInteger totalQuantity() {
        return this.total;
    }

    /** Persist only one next-identity cursor, never a duplicate inventory list. */
    public K outputCursor() {
        return this.index.isEmpty() ? null
            : this.index.values()
                .iterator()
                .next().identity;
    }

    /** Load-time O(identities) positioning; no handler calls or inventory mutations. */
    public void restoreOutputCursor(final K identity) {
        if (!this.index.containsKey(identity)) return;
        for (int step = 0, size = this.index.size(); step < size; step++) {
            final Entry<K> entry = this.index.values()
                .iterator()
                .next();
            if (entry.identity.equals(identity)) return;
            this.index.remove(entry.identity);
            this.index.put(entry.identity, entry);
        }
    }

    /** Runtime capacity-card limit; existing and protected stock is never trimmed. */
    public void setTypeLimit(final int limit) {
        if (limit < 0 || limit > MAX_PARTITIONS) throw new IllegalArgumentException("Invalid type limit");
        if (this.typeLimit == limit) return;
        final long next = Math.incrementExact(this.configurationVersion);
        this.typeLimit = limit;
        this.configurationVersion = next;
    }

    /** Diagnostic page only. Never used in the input or periodic output hot path. */
    public List<Entry<K>> emergencyPage(final int offset, final int maximum) {
        if (offset < 0 || maximum < 1 || maximum > MAX_PARTITIONS) throw new IllegalArgumentException("Invalid page");
        final List<Entry<K>> result = new ArrayList<>(maximum);
        int skipped = 0;
        for (final Entry<K> entry : this.normalEntries()) {
            if (entry.emergency && skipped++ >= offset) result.add(entry);
        }
        for (final Entry<K> entry : this.overflow.values()) {
            if (result.size() >= maximum) break;
            if (skipped++ >= offset) result.add(entry);
        }
        return Collections.unmodifiableList(result.subList(0, Math.min(maximum, result.size())));
    }

    /** Shrink without clipping stock. Only admission and normal-position promotion change. */
    public void configure(final int partitions, final long capacity) {
        validateConfiguration(partitions, capacity);
        if (partitions == this.partitions && capacity == this.capacity) return;
        final long next = Math.incrementExact(this.configurationVersion);
        this.partitions = partitions;
        this.capacity = capacity;
        this.configurationVersion = next;
        this.reconcilePositions();
    }

    /** Runtime policy, later derived from the real capacity card. Lambdas are not serialized as inventory. */
    public void setAdmissionFilter(final Predicate<? super K> filter) {
        Objects.requireNonNull(filter, "filter");
        final long next = Math.incrementExact(this.configurationVersion);
        this.filter = filter;
        this.configurationVersion = next;
    }

    /** Remove only confirmed delivered stock. Known stock may drain even while a failed input is isolated. */
    public void remove(final K identity, final long amount) {
        final Entry<K> entry = this.index.get(identity);
        if (entry == null || amount <= 0 || amount > entry.quantity) {
            throw new IllegalArgumentException("Invalid confirmed removal");
        }
        final long next = Math.incrementExact(this.cacheVersion);
        if (amount == entry.quantity) {
            this.index.remove(identity);
            this.total = this.total.subtract(BigInteger.valueOf(amount));
            if (entry.emergency) this.emergencyTotal = this.emergencyTotal.subtract(BigInteger.valueOf(amount));
            if (entry.emergency) this.emergencyCount--;
            if (entry.slot >= 0) {
                this.slots.set(entry.slot, null);
                this.occupied &= ~(1 << entry.slot);
            } else this.overflow.remove(identity);
        } else {
            final long remaining = entry.quantity - amount;
            this.put(
                new Entry<>(
                    entry.slot,
                    identity,
                    remaining,
                    entry.emergency && (entry.slot < 0 || remaining >= this.quota())));
        }
        this.reconcilePositions();
        this.cacheVersion = next;
    }

    public Transaction transaction() {
        return this.transaction(false);
    }

    /** Only a caller with explicit actual-output AND protection evidence may request this capability. */
    public Transaction transaction(final boolean protectedActualOutput) {
        this.requireUsable();
        return new Transaction(protectedActualOutput);
    }

    private void put(final Entry<K> entry) {
        final Entry<K> previous = this.index.put(entry.identity, entry);
        this.total = this.total.add(BigInteger.valueOf(entry.quantity - (previous == null ? 0 : previous.quantity)));
        if (previous != null && previous.emergency)
            this.emergencyTotal = this.emergencyTotal.subtract(BigInteger.valueOf(previous.quantity));
        if (entry.emergency) this.emergencyTotal = this.emergencyTotal.add(BigInteger.valueOf(entry.quantity));
        if (previous != null && previous.emergency) this.emergencyCount--;
        if (entry.emergency) this.emergencyCount++;
        if (previous != null && previous.slot < 0 && entry.slot >= 0) this.overflow.remove(entry.identity);
        if (entry.slot >= 0) {
            this.slots.set(entry.slot, entry);
            this.occupied |= 1 << entry.slot;
        } else this.overflow.put(entry.identity, entry);
    }

    /** At most sixteen promotions/normal updates, independent of the overflow queue length. */
    private void reconcilePositions() {
        while (!this.overflow.isEmpty() && Integer.bitCount(this.occupied) < this.partitions) {
            final Entry<K> entry = this.overflow.values()
                .iterator()
                .next();
            final int slot = Integer.numberOfTrailingZeros(~this.occupied & 0xffff);
            this.put(new Entry<>(slot, entry.identity, entry.quantity, entry.quantity >= this.quota()));
        }
        for (int slot = 0; slot < MAX_PARTITIONS; slot++) {
            final Entry<K> entry = this.slots.get(slot);
            if (entry != null && entry.emergency && entry.quantity < this.quota()) {
                this.put(new Entry<>(slot, entry.identity, entry.quantity, false));
            }
        }
    }

    static <K> PartitionStore<K> restore(final int partitions, final long capacity, final long configurationVersion,
        final long cacheVersion, final List<Entry<K>> entries, final Failure<K> failure) {
        if (configurationVersion < 0 || cacheVersion < 0) throw new IllegalArgumentException("Invalid saved version");
        final PartitionStore<K> store = new PartitionStore<>(partitions, capacity);
        for (final Entry<K> entry : entries) {
            if (entry.slot < -1 || entry.slot >= MAX_PARTITIONS
                || entry.quantity <= 0
                || entry.identity == null
                || (entry.slot == -1 && !entry.emergency)
                || store.index.containsKey(entry.identity)
                || (entry.slot >= 0 && store.slots.get(entry.slot) != null)) {
                throw new IllegalArgumentException("Invalid or duplicate saved identity");
            }
            store.put(entry);
        }
        store.configurationVersion = configurationVersion;
        store.cacheVersion = cacheVersion;
        store.failure = failure;
        return store;
    }

    private static void validateConfiguration(final int partitions, final long capacity) {
        if (partitions < 1 || partitions > MAX_PARTITIONS || capacity < 0) {
            throw new IllegalArgumentException("Invalid partition configuration");
        }
    }

    private void requireUsable() {
        if (this.failure != null) throw new IllegalStateException("Failed protected output requires manual review");
    }

    public static final class Entry<K> {

        /** -1 means emergency identity without a normal display position. */
        public final int slot;
        public final K identity;
        public final long quantity;
        /** Marks forced receipt, not a second stock balance or an entitlement to future admission. */
        public final boolean emergency;

        Entry(final int slot, final K identity, final long quantity) {
            this(slot, identity, quantity, false);
        }

        Entry(final int slot, final K identity, final long quantity, final boolean emergency) {
            this.slot = slot;
            this.identity = identity;
            this.quantity = quantity;
            this.emergency = emergency;
        }
    }

    /** Failed protected input evidence, never materialized stock and never automatically replayed/refunded. */
    public static final class Failure<K> {

        public final String reason;
        public final long configurationVersion;
        public final long cacheVersion;
        public final Map<K, Long> pending;
        public final K attemptedIdentity;
        public final long attemptedAmount;

        Failure(final String reason, final long configurationVersion, final long cacheVersion,
            final Map<K, Long> pending, final K attemptedIdentity, final long attemptedAmount) {
            this.reason = reason;
            this.configurationVersion = configurationVersion;
            this.cacheVersion = cacheVersion;
            this.pending = Collections.unmodifiableMap(new LinkedHashMap<>(pending));
            this.attemptedIdentity = attemptedIdentity;
            this.attemptedAmount = attemptedAmount;
        }
    }

    public static final class CommitConflictException extends IllegalStateException {

        private static final long serialVersionUID = 1L;
        private final Map<?, Long> pending;

        CommitConflictException(final Map<?, Long> pending) {
            super("Partition plan cannot be committed completely; retain delivery evidence");
            this.pending = Collections.unmodifiableMap(new LinkedHashMap<>(pending));
        }

        public Map<?, Long> pending() {
            return this.pending;
        }
    }

    public final class Transaction {

        private Plan plan = new Plan();
        private final Set<K> completed = new HashSet<>();
        private boolean emergencyAllowed;
        private boolean protectAcceptedOutput;
        private boolean started;
        private boolean active = true;

        private Transaction(final boolean emergencyAllowed) {
            this.emergencyAllowed = emergencyAllowed;
        }

        void setProtectAcceptedOutput(final boolean protect) {
            this.requireActive();
            if (this.started) throw new IllegalStateException("Output phase flags are frozen after first use");
            this.protectAcceptedOutput = protect;
        }

        public Map<K, Long> pending() {
            return Collections.unmodifiableMap(this.plan.pending);
        }

        public boolean hasAvailableSpace() {
            this.requireActive();
            this.started = true;
            try {
                this.refreshPlan();
                return this.emergencyAllowed || this.plan.hasAvailableSpace();
            } catch (RuntimeException failure) {
                this.active = false;
                this.fail(failure, null, 0);
                throw failure;
            }
        }

        public boolean offer(final K identity, final long amount) {
            this.requireActive();
            this.started = true;
            try {
                Objects.requireNonNull(identity, "identity");
                if (amount <= 0) throw new IllegalArgumentException("Non-positive output quantity");
                if (this.completed.contains(identity)) throw new IllegalStateException("Identity already completed");
                this.refreshPlan();
                final boolean accepted = this.plan.offer(identity, amount);
                if (!this.plan.current()) throw new CommitConflictException(this.plan.pending);
                return accepted;
            } catch (RuntimeException failure) {
                this.fail(failure, identity, amount);
                throw failure;
            }
        }

        public void complete(final K identity) {
            this.requireActive();
            this.started = true;
            this.completed.add(Objects.requireNonNull(identity, "identity"));
        }

        /** Rebuild only the touched identities. Validate all deltas before publishing any stock mutation. */
        public void commit() {
            this.requireActive();
            this.started = true;
            // GT may have other normal destinations. Never enable emergency admission while it is routing.
            // At commit only previously accepted deltas remain, so rebasing them cannot steal a later bus's output.
            this.emergencyAllowed |= this.protectAcceptedOutput;
            try {
                this.refreshPlan();
                if (!this.plan.pending.isEmpty()) {
                    final long next = Math.incrementExact(PartitionStore.this.cacheVersion);
                    final List<Entry<K>> changes = new ArrayList<>(this.plan.pending.size());
                    for (final K key : this.plan.pending.keySet()) {
                        final Entry<K> original = this.plan.observed.get(key);
                        final int slot = original == null ? this.plan.allocated.get(key) : original.slot;
                        changes.add(
                            new Entry<>(
                                slot,
                                key,
                                this.plan.currentQuantity(key),
                                this.plan.forced.contains(key) || (original != null && original.emergency)));
                    }
                    if (!this.plan.current()) throw new CommitConflictException(this.plan.pending);
                    for (final Entry<K> entry : changes) PartitionStore.this.put(entry);
                    PartitionStore.this.cacheVersion = next;
                }
                this.active = false;
            } catch (RuntimeException failure) {
                this.active = false;
                this.fail(failure, null, 0);
                throw failure;
            }
        }

        private void refreshPlan() {
            PartitionStore.this.requireUsable();
            if (this.plan.current()) return;
            final Plan rebuilt = new Plan();
            for (final Map.Entry<K, Long> delta : this.plan.pending.entrySet()) {
                if (!rebuilt.offer(delta.getKey(), delta.getValue()))
                    throw new CommitConflictException(this.plan.pending);
            }
            if (!rebuilt.current()) throw new CommitConflictException(this.plan.pending);
            this.plan = rebuilt;
        }

        private void fail(final RuntimeException problem, final K identity, final long amount) {
            // Strict callers retain their exception/pending plan. A failed protected receipt also persists a lock.
            if ((this.emergencyAllowed || this.protectAcceptedOutput) && PartitionStore.this.failure == null) {
                final String reason = problem instanceof ArithmeticException ? "ARITHMETIC"
                    : problem instanceof CommitConflictException ? "PLAN_CONFLICT" : "INVALID_OR_CALLBACK";
                PartitionStore.this.failure = new Failure<>(
                    reason,
                    PartitionStore.this.configurationVersion,
                    PartitionStore.this.cacheVersion,
                    this.plan.pending,
                    identity,
                    amount);
            }
            if (this.emergencyAllowed || this.protectAcceptedOutput || problem instanceof CommitConflictException)
                this.active = false;
        }

        private void requireActive() {
            if (!this.active) throw new IllegalStateException("Partition transaction is spent");
        }

        private final class Plan {

            private final long configuration = PartitionStore.this.configurationVersion;
            private final long version = PartitionStore.this.cacheVersion;
            private final long quota = PartitionStore.this.quota();
            private final int limit = PartitionStore.this.effectivePartitions();
            private final int identityCount = PartitionStore.this.index.size();
            private final Predicate<? super K> admissionFilter = PartitionStore.this.filter;
            private final Map<K, Entry<K>> observed = new HashMap<>();
            private final List<Entry<K>> normal = new ArrayList<>(MAX_PARTITIONS);
            private final Map<K, Long> pending = new LinkedHashMap<>();
            private final Map<K, Integer> allocated = new HashMap<>();
            private final Set<K> forced = new HashSet<>();
            private int used = PartitionStore.this.occupied;

            private Plan() {
                // Copy only the <=16 normal entries. Overflow identities are observed lazily at the same version.
                for (final Entry<K> entry : PartitionStore.this.slots) {
                    if (entry != null) {
                        this.observed.put(entry.identity, entry);
                        this.normal.add(entry);
                    }
                }
            }

            private boolean current() {
                return this.configuration == PartitionStore.this.configurationVersion
                    && this.version == PartitionStore.this.cacheVersion
                    && PartitionStore.this.failure == null;
            }

            private boolean hasAvailableSpace() {
                for (final K key : this.pending.keySet()) {
                    if (!Transaction.this.completed.contains(key) && this.currentQuantity(key) < Long.MAX_VALUE)
                        return true;
                }
                if (this.quota == 0) return false;
                if (this.identityCount + (long) this.allocated.size() < this.limit) return true;
                for (final Entry<K> entry : this.normal) {
                    if (entry != null && entry.slot >= 0
                        && !Transaction.this.completed.contains(entry.identity)
                        && !this.pending.containsKey(entry.identity)
                        && entry.quantity < this.quota) return true;
                }
                return false;
            }

            private boolean offer(final K identity, final long amount) {
                if (!this.observed.containsKey(identity))
                    this.observed.put(identity, PartitionStore.this.index.get(identity));
                final Entry<K> existing = this.observed.get(identity);
                final boolean admitted = this.pending.containsKey(identity);
                final boolean room = this.identityCount + (long) this.allocated.size() < this.limit;
                if (!admitted) {
                    final boolean normal = this.quota > 0
                        && (existing == null ? room : existing.slot >= 0 && existing.quantity < this.quota)
                        && this.admissionFilter.test(identity);
                    if (!normal && !Transaction.this.emergencyAllowed) return false;
                    if (!normal) this.forced.add(identity);
                }
                Math.addExact(this.currentQuantity(identity), amount);
                final long sum = Math.addExact(this.pending.getOrDefault(identity, 0L), amount);
                if (!admitted && existing == null) {
                    final int free = ~this.used & 0xffff;
                    final int slot = room && free != 0 ? Integer.numberOfTrailingZeros(free) : -1;
                    this.allocated.put(identity, slot);
                    if (slot >= 0) this.used |= 1 << slot;
                }
                this.pending.put(identity, sum);
                return true;
            }

            private long currentQuantity(final K identity) {
                final Entry<K> original = this.observed.get(identity);
                return Math.addExact(original == null ? 0 : original.quantity, this.pending.getOrDefault(identity, 0L));
            }
        }
    }
}
