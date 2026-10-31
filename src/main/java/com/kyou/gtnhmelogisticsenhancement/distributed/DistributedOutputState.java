package com.kyou.gtnhmelogisticsenhancement.distributed;

import java.util.List;
import java.util.function.Function;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;

/** Per-machine main-thread state. The flight journal is evidence, never a second inventory or a refund. */
public class DistributedOutputState<K> {

    public static final int MIN_PERIOD = 40;
    public static final int TICKS_PER_SECOND = 20;
    public static final double MAX_PERIOD_SECONDS = Integer.MAX_VALUE / (double) TICKS_PER_SECOND;
    public static final int OUTPUT_BUDGET = 16;
    protected PartitionStore<K> store;
    private CapacityPolicy<K> policy;
    private final PartitionNbt.IdentityCodec<K> codec;
    private final Function<ItemStack, CapacityPolicy<K>> inspect;

    protected DistributedOutputState(final long capacity, final PartitionNbt.IdentityCodec<K> codec,
        final Function<ItemStack, CapacityPolicy<K>> inspect) {
        this.store = new PartitionStore<>(capacity);
        this.codec = codec;
        this.inspect = inspect;
        this.policy = inspect.apply(null);
    }

    public interface CapacityPolicy<K> {

        boolean valid();

        int types();

        long capacity(int partitions);

        boolean accepts(K identity);

        default boolean filtered() {
            return false;
        }
    }

    private int period = MIN_PERIOD;
    private int elapsed;
    private List<K> outputRound;
    private int roundPosition;
    private boolean stopped;
    private boolean busy;
    private String fault = "";
    private NBTBase quarantined;
    private NBTTagCompound flight;

    public PartitionStore<K> store() {
        return this.store;
    }

    public int period() {
        return this.period;
    }

    public double periodSeconds() {
        return this.period / (double) TICKS_PER_SECOND;
    }

    /** GUI seconds resolve to the nearest game tick; every existing int tick period round-trips exactly. */
    public static int ticksFromSeconds(final double seconds) {
        if (Double.isNaN(seconds) || seconds < MIN_PERIOD / (double) TICKS_PER_SECOND || seconds > MAX_PERIOD_SECONDS)
            throw new IllegalArgumentException("Invalid output period in seconds");
        return (int) Math.round(seconds * TICKS_PER_SECOND);
    }

    public boolean roundActive() {
        return this.outputRound != null;
    }

    public String fault() {
        if (!this.fault.isEmpty()) return this.fault;
        return this.store.failure() == null ? "" : "INPUT_" + this.store.failure().reason;
    }

    public boolean locked() {
        return this.stopped || this.busy
            || this.quarantined != null
            || this.flight != null
            || !this.fault()
                .isEmpty();
    }

    public boolean validCard() {
        return this.policy.valid();
    }

    public boolean filtered() {
        return this.policy.filtered();
    }

    public boolean accepts(final K identity) {
        return this.policy.accepts(identity);
    }

    public void stop() {
        this.stopped = true;
        this.outputRound = null;
    }

    public void isolate(final String reason) {
        this.fault = reason;
        this.outputRound = null;
    }

    public void setPeriod(final int value) {
        if (value < MIN_PERIOD) throw new IllegalArgumentException("Output period below 40 ticks");
        if (this.locked()) throw new IllegalStateException("Bus locked");
        this.period = value;
        if (!this.roundActive()) this.elapsed = 0;
    }

    public void setPartitions(final int value) {
        if (this.locked()) throw new IllegalStateException("Bus locked");
        this.store.configure(value, this.policy.capacity(value));
    }

    /** Rebuild on card events/load, before permitting input. Never called from the output tick loop. */
    public void updateCard(final ItemStack card) {
        this.policy = this.inspect.apply(card);
        this.store.setAdmissionFilter(this.policy::accepts);
        this.store.setTypeLimit(this.policy.types());
        this.store.configure(this.store.partitions(), this.policy.capacity(this.store.partitions()));
    }

    /** Waiting does integer timing only. An active round resumes each tick, without another period delay. */
    public boolean tick() {
        if (this.locked()) return false;
        if (this.roundActive()) return true;
        if (++this.elapsed < this.period) return false;
        this.elapsed = 0;
        return !this.locked() && this.store.identityCount() != 0;
    }

    public interface Insertion<K> {

        Remainder<K> insert(K identity, int amount);
    }

    public static class Remainder<K> {

        public final K identity;
        public final long quantity;

        public Remainder(final K identity, final long quantity) {
            this.identity = identity;
            this.quantity = quantity;
        }
    }

    /** One tick of a stable round: at most sixteen visits, one insertion per identity per round. */
    public boolean flush(final Insertion<K> destination, final Runnable markDirty) {
        if (this.locked()) return false;
        if (this.outputRound == null) {
            if (this.store.identityCount() == 0) return false;
            this.outputRound = this.store.outputSnapshot();
            this.roundPosition = 0;
        }
        boolean changed = false;
        this.busy = true;
        try {
            for (int visited = 0; visited < OUTPUT_BUDGET && this.roundPosition < this.outputRound.size(); visited++) {
                if (this.stopped || !this.fault.isEmpty()) break;
                // Completed positions are never read again; do not retain drained identities/NBT until round end.
                final K identity = this.outputRound.set(this.roundPosition++, null);
                this.store.advanceOutputCursor(identity);
                final long known = this.store.quantity(identity);
                if (known <= 0) continue;
                final int amount = (int) Math.min(Integer.MAX_VALUE, known);
                final long version = this.store.cacheVersion();
                this.flight = new NBTTagCompound();
                this.flight.setTag("identity", this.codec.write(identity));
                this.flight.setLong("offered", amount);
                this.flight.setLong("cacheVersion", version);
                markDirty.run();
                final Remainder<K> remaining = destination.insert(identity, amount);
                if (this.stopped || version != this.store.cacheVersion())
                    throw new IllegalStateException("LIFECYCLE_OR_STOCK_CHANGED");
                final long left = remaining == null ? 0 : remaining.quantity;
                if (left < 0 || left > amount || (remaining != null && !identity.equals(remaining.identity)))
                    throw new IllegalStateException("INVALID_REMAINDER");
                final long accepted = amount - left;
                if (accepted != 0) {
                    this.store.remove(identity, accepted);
                    changed = true;
                }
                this.flight = null;
                markDirty.run();
            }
        } catch (RuntimeException uncertain) {
            this.fault = "UNCERTAIN_OUTPUT";
            // Retain stock AND the offered-stack journal. Neither may be replayed without external evidence.
            markDirty.run();
        } finally {
            this.busy = false;
            if (this.locked() || this.roundPosition >= this.outputRound.size()) {
                this.outputRound = null;
                this.roundPosition = 0;
                this.elapsed = 0;
            }
        }
        return changed;
    }

    public NBTTagCompound write() {
        if (this.quarantined != null) {
            final NBTTagCompound preserved = new NBTTagCompound();
            preserved.setTag("quarantine", this.quarantined.copy());
            return preserved;
        }
        final NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("version", 1);
        tag.setInteger("period", this.period);
        tag.setTag("store", PartitionNbt.write(this.store, this.codec));
        tag.setString("fault", this.fault);
        if (this.store.outputCursor() != null) tag.setTag("cursor", this.codec.write(this.store.outputCursor()));
        if (this.flight != null) tag.setTag("flight", this.flight.copy());
        return tag;
    }

    protected final void readState(final NBTBase raw, final ItemStack card) {
        final DistributedOutputState<K> state = this;
        try {
            if (!(raw instanceof NBTTagCompound)) throw new IllegalArgumentException("Missing bus state");
            final NBTTagCompound tag = (NBTTagCompound) raw;
            if (tag.hasKey("quarantine") && tag.func_150296_c()
                .size() == 1) {
                state.quarantined = tag.getTag("quarantine")
                    .copy();
                state.fault = "INVALID_SAVED_DATA";
                return;
            }
            if (!tag.hasKey("version", 3) || tag.getInteger("version") != 1
                || !tag.hasKey("period", 3)
                || tag.getInteger("period") < MIN_PERIOD
                || !tag.hasKey("fault", 8)
                || tag.getString("fault")
                    .length() > 128
                || tag.hasKey("quarantine")
                || tag.func_150296_c()
                    .size() != 4 + (tag.hasKey("flight") ? 1 : 0) + (tag.hasKey("cursor") ? 1 : 0))
                throw new IllegalArgumentException("Invalid bus envelope");
            final PartitionNbt.Loaded<K> loaded = PartitionNbt.read(tag.getTag("store"), this.codec);
            if (loaded.isolated()) throw new IllegalArgumentException("Invalid bus stock");
            state.store = loaded.store();
            state.period = tag.getInteger("period");
            state.fault = tag.getString("fault");
            if (tag.hasKey("cursor")) {
                if (!tag.hasKey("cursor", 10)) throw new IllegalArgumentException("Invalid output cursor");
                state.store.restoreOutputCursor(this.codec.read(tag.getCompoundTag("cursor")));
            }
            if (tag.hasKey("flight")) {
                if (!tag.hasKey("flight", 10)) throw new IllegalArgumentException("Invalid output journal");
                state.flight = (NBTTagCompound) tag.getCompoundTag("flight")
                    .copy();
                if (state.flight.func_150296_c()
                    .size() != 3 || !state.flight.hasKey("identity", 10)
                    || !state.flight.hasKey("offered", 4)
                    || !state.flight.hasKey("cacheVersion", 4)
                    || state.flight.getLong("offered") < 1
                    || state.flight.getLong("offered") > Integer.MAX_VALUE
                    || state.flight.getLong("cacheVersion") < 0)
                    throw new IllegalArgumentException("Invalid output journal");
                this.codec.read(state.flight.getCompoundTag("identity"));
                state.fault = "UNCERTAIN_OUTPUT_RELOAD";
            }
            state.updateCard(card);
        } catch (RuntimeException damaged) {
            state.quarantined = raw == null ? new NBTTagCompound() : raw.copy();
            state.fault = "INVALID_SAVED_DATA";
        }
    }
}
