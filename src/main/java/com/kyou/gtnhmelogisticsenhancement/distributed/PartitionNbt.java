package com.kyou.gtnhmelogisticsenhancement.distributed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Strict current-format restoration. Emergency stock is real stock; failed plans are evidence only. */
public final class PartitionNbt {

    private static final int VERSION = 2;
    private static final String QUARANTINE = "quarantine";

    private PartitionNbt() {}

    public interface IdentityCodec<K> {

        String type();

        NBTTagCompound write(K identity);

        K read(NBTTagCompound tag);
    }

    public static final class Loaded<K> {

        private final PartitionStore<K> store;
        private final NBTBase evidence;

        private Loaded(final PartitionStore<K> store, final NBTBase evidence) {
            this.store = store;
            this.evidence = evidence;
        }

        public boolean isolated() {
            return this.evidence != null;
        }

        public NBTBase evidence() {
            return this.evidence == null ? null : this.evidence.copy();
        }

        public PartitionStore<K> store() {
            if (this.isolated()) throw new IllegalStateException("Partition stock requires manual recovery");
            return this.store;
        }
    }

    public static <K> NBTTagCompound write(final PartitionStore<K> store, final IdentityCodec<K> codec) {
        final NBTTagCompound root = new NBTTagCompound();
        root.setInteger("version", VERSION);
        root.setString("type", codec.type());
        root.setInteger("partitions", store.partitions());
        root.setLong("capacity", store.capacity());
        root.setLong("configurationVersion", store.configurationVersion());
        root.setLong("cacheVersion", store.cacheVersion());
        final NBTTagList entries = new NBTTagList();
        for (final PartitionStore.Entry<K> entry : store.entries()) {
            final NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("slot", entry.slot);
            tag.setLong("quantity", entry.quantity);
            tag.setBoolean("emergency", entry.emergency);
            tag.setTag("identity", codec.write(entry.identity));
            entries.appendTag(tag);
        }
        root.setTag("entries", entries);
        root.setTag("failure", writeFailure(store.failure(), codec));
        return root;
    }

    public static <K> NBTTagCompound write(final Loaded<K> loaded, final IdentityCodec<K> codec) {
        if (!loaded.isolated()) return write(loaded.store(), codec);
        final NBTTagCompound root = new NBTTagCompound();
        root.setTag(QUARANTINE, loaded.evidence());
        return root;
    }

    public static <K> Loaded<K> read(final NBTBase raw, final IdentityCodec<K> codec) {
        if (raw == null) throw new IllegalArgumentException("Missing partition payload");
        if (raw instanceof NBTTagCompound && ((NBTTagCompound) raw).hasKey(QUARANTINE)) {
            final NBTTagCompound envelope = (NBTTagCompound) raw;
            return new Loaded<>(
                null,
                envelope.func_150296_c()
                    .size() == 1 ? envelope.getTag(QUARANTINE)
                        .copy() : raw.copy());
        }
        try {
            if (!(raw instanceof NBTTagCompound)) throw new IllegalArgumentException("Expected compound");
            final NBTTagCompound root = (NBTTagCompound) raw;
            require(root, "version", 3);
            if (root.getInteger("version") != VERSION || root.func_150296_c()
                .size() != 8) {
                throw new IllegalArgumentException("Unknown partition format/fields");
            }
            require(root, "type", 8);
            require(root, "partitions", 3);
            require(root, "capacity", 4);
            require(root, "configurationVersion", 4);
            require(root, "cacheVersion", 4);
            require(root, "entries", 9);
            if (!codec.type()
                .equals(root.getString("type"))) throw new IllegalArgumentException("Wrong storage type");
            final NBTTagList tags = list(root, "entries");
            final List<PartitionStore.Entry<K>> entries = new ArrayList<>();
            for (int i = 0; i < tags.tagCount(); i++) {
                final NBTTagCompound tag = tags.getCompoundTagAt(i);
                if (tag.func_150296_c()
                    .size() != 4) {
                    throw new IllegalArgumentException("Unknown entry fields");
                }
                require(tag, "slot", 3);
                require(tag, "quantity", 4);
                require(tag, "identity", 10);
                require(tag, "emergency", 1);
                if (tag.getByte("emergency") != 0 && tag.getByte("emergency") != 1) {
                    throw new IllegalArgumentException("Invalid emergency flag");
                }
                final int slot = tag.getInteger("slot");
                entries.add(
                    new PartitionStore.Entry<>(
                        slot,
                        codec.read(copy(tag.getCompoundTag("identity"))),
                        tag.getLong("quantity"),
                        tag.getBoolean("emergency")));
            }
            require(root, "failure", 10);
            final PartitionStore.Failure<K> failure = readFailure(root.getCompoundTag("failure"), codec);
            return new Loaded<>(
                PartitionStore.restore(
                    root.getInteger("partitions"),
                    root.getLong("capacity"),
                    root.getLong("configurationVersion"),
                    root.getLong("cacheVersion"),
                    entries,
                    failure),
                null);
        } catch (RuntimeException invalid) {
            return new Loaded<>(null, raw.copy());
        }
    }

    private static <K> NBTTagCompound writeFailure(final PartitionStore.Failure<K> failure,
        final IdentityCodec<K> codec) {
        final NBTTagCompound tag = new NBTTagCompound();
        if (failure == null) return tag;
        tag.setString("reason", failure.reason);
        tag.setLong("configurationVersion", failure.configurationVersion);
        tag.setLong("cacheVersion", failure.cacheVersion);
        tag.setLong("attemptedAmount", failure.attemptedAmount);
        tag.setTag(
            "attemptedIdentity",
            failure.attemptedIdentity == null ? new NBTTagCompound() : codec.write(failure.attemptedIdentity));
        final NBTTagList pending = new NBTTagList();
        for (final Map.Entry<K, Long> delta : failure.pending.entrySet()) {
            final NBTTagCompound record = new NBTTagCompound();
            record.setTag("identity", codec.write(delta.getKey()));
            record.setLong("quantity", delta.getValue());
            pending.appendTag(record);
        }
        tag.setTag("pending", pending);
        return tag;
    }

    private static <K> PartitionStore.Failure<K> readFailure(final NBTTagCompound tag, final IdentityCodec<K> codec) {
        if (tag.hasNoTags()) return null;
        if (tag.func_150296_c()
            .size() != 6) throw new IllegalArgumentException("Unknown failure fields");
        require(tag, "reason", 8);
        require(tag, "configurationVersion", 4);
        require(tag, "cacheVersion", 4);
        require(tag, "attemptedAmount", 4);
        require(tag, "attemptedIdentity", 10);
        final NBTTagList records = list(tag, "pending");
        final String reason = tag.getString("reason");
        if (reason.isEmpty() || reason.length() > 128
            || tag.getLong("configurationVersion") < 0
            || tag.getLong("cacheVersion") < 0) throw new IllegalArgumentException("Invalid failure header");
        final Map<K, Long> pending = new LinkedHashMap<>();
        for (int i = 0; i < records.tagCount(); i++) {
            final NBTTagCompound record = records.getCompoundTagAt(i);
            if (record.func_150296_c()
                .size() != 2) throw new IllegalArgumentException("Invalid pending evidence");
            require(record, "quantity", 4);
            require(record, "identity", 10);
            final K identity = codec.read(copy(record.getCompoundTag("identity")));
            final long quantity = record.getLong("quantity");
            if (identity == null || quantity <= 0 || pending.put(identity, quantity) != null) {
                throw new IllegalArgumentException("Duplicate/invalid pending evidence");
            }
        }
        final NBTTagCompound attempted = tag.getCompoundTag("attemptedIdentity");
        final K identity = attempted.hasNoTags() ? null : codec.read(copy(attempted));
        if (!attempted.hasNoTags() && identity == null)
            throw new IllegalArgumentException("Invalid attempted identity");
        return new PartitionStore.Failure<>(
            reason,
            tag.getLong("configurationVersion"),
            tag.getLong("cacheVersion"),
            pending,
            identity,
            tag.getLong("attemptedAmount"));
    }

    private static NBTTagList list(final NBTTagCompound tag, final String key) {
        require(tag, key, 9);
        final NBTTagList list = (NBTTagList) tag.getTag(key);
        if (list.tagCount() != 0 && list.func_150303_d() != 10) throw new IllegalArgumentException("Invalid list");
        return list;
    }

    private static NBTTagCompound copy(final NBTTagCompound tag) {
        return (NBTTagCompound) tag.copy();
    }

    private static void require(final NBTTagCompound root, final String key, final int type) {
        if (!root.hasKey(key, type)) throw new IllegalArgumentException("Invalid partition field: " + key);
    }
}
