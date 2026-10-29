package com.kyou.gtnhmelogisticsenhancement.distributed;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Strict all-or-isolated restoration: malformed stock is evidence, never an empty usable inventory. */
public final class PartitionNbt {

    private static final int VERSION = 1;
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
            tag.setTag("identity", codec.write(entry.identity));
            entries.appendTag(tag);
        }
        root.setTag("entries", entries);
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
            // A canonical envelope does not grow a new nesting level on every save/reload.
            return new Loaded<>(
                null,
                envelope.func_150296_c()
                    .size() == 1 ? envelope.getTag(QUARANTINE)
                        .copy() : raw.copy());
        }
        try {
            if (!(raw instanceof NBTTagCompound)) throw new IllegalArgumentException("Expected compound");
            final NBTTagCompound root = (NBTTagCompound) raw;
            if (root.func_150296_c()
                .size() != 7) throw new IllegalArgumentException("Unknown partition fields");
            require(root, "version", 3);
            require(root, "type", 8);
            require(root, "partitions", 3);
            require(root, "capacity", 4);
            require(root, "configurationVersion", 4);
            require(root, "cacheVersion", 4);
            require(root, "entries", 9);
            if (root.getInteger("version") != VERSION || !codec.type()
                .equals(root.getString("type"))) {
                throw new IllegalArgumentException("Unknown partition format/type");
            }
            final NBTTagList tags = (NBTTagList) root.getTag("entries");
            if (tags.tagCount() > PartitionStore.MAX_PARTITIONS
                || (tags.tagCount() != 0 && tags.func_150303_d() != 10)) {
                throw new IllegalArgumentException("Invalid partition list");
            }
            final List<PartitionStore.Entry<K>> entries = new ArrayList<>();
            for (int i = 0; i < tags.tagCount(); i++) {
                final NBTTagCompound tag = tags.getCompoundTagAt(i);
                if (tag.func_150296_c()
                    .size() != 3) throw new IllegalArgumentException("Unknown entry fields");
                require(tag, "slot", 3);
                require(tag, "quantity", 4);
                require(tag, "identity", 10);
                entries.add(
                    new PartitionStore.Entry<>(
                        tag.getInteger("slot"),
                        codec.read(
                            (NBTTagCompound) tag.getCompoundTag("identity")
                                .copy()),
                        tag.getLong("quantity")));
            }
            return new Loaded<>(
                PartitionStore.restore(
                    root.getInteger("partitions"),
                    root.getLong("capacity"),
                    root.getLong("configurationVersion"),
                    root.getLong("cacheVersion"),
                    entries),
                null);
        } catch (RuntimeException invalid) {
            return new Loaded<>(null, raw.copy());
        }
    }

    private static void require(final NBTTagCompound root, final String key, final int type) {
        if (!root.hasKey(key, type)) throw new IllegalArgumentException("Invalid partition field: " + key);
    }
}
