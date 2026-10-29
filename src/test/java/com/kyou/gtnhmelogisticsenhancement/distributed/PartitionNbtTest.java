package com.kyou.gtnhmelogisticsenhancement.distributed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.function.Consumer;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import org.junit.Test;

/** Real Minecraft NBT; string identities isolate registry bootstrap from the pure recovery rules. */
public class PartitionNbtTest {

    private static final PartitionNbt.IdentityCodec<String> CODEC = new PartitionNbt.IdentityCodec<String>() {

        @Override
        public String type() {
            return "test";
        }

        @Override
        public NBTTagCompound write(final String identity) {
            final NBTTagCompound tag = new NBTTagCompound();
            tag.setString("key", identity);
            return tag;
        }

        @Override
        public String read(final NBTTagCompound tag) {
            if (!tag.hasKey("key", 8) || tag.getString("key")
                .isEmpty()) throw new IllegalArgumentException();
            return tag.getString("key");
        }
    };

    @Test
    public void roundTripPreservesLongCountsVersionsAndShrunkLegacySlots() {
        final PartitionStore<String> original = new PartitionStore<>(16, Long.MAX_VALUE);
        for (int i = 0; i < 16; i++) PartitionStoreTest.add(original, "K" + i, Long.MAX_VALUE);
        original.remove("K3", Long.MAX_VALUE);
        original.configure(1, 10);
        final NBTTagCompound saved = PartitionNbt.write(original, CODEC);
        final PartitionNbt.Loaded<String> loaded = PartitionNbt.read(saved, CODEC);
        assertFalse(loaded.isolated());
        assertEquals(
            original.totalQuantity(),
            loaded.store()
                .totalQuantity());
        assertEquals(
            original.configurationVersion(),
            loaded.store()
                .configurationVersion());
        assertEquals(
            original.cacheVersion(),
            loaded.store()
                .cacheVersion());
        assertEquals(saved, PartitionNbt.write(loaded, CODEC));
        assertEquals(
            4,
            loaded.store()
                .entries()
                .get(3).slot);
        assertFalse(
            loaded.store()
                .transaction()
                .offer("new", 1));
    }

    @Test
    public void emptyValidPayloadRestoresWithoutQuarantine() {
        final PartitionStore<String> store = new PartitionStore<>(1600);
        final PartitionNbt.Loaded<String> loaded = PartitionNbt.read(PartitionNbt.write(store, CODEC), CODEC);
        assertFalse(loaded.isolated());
        assertTrue(
            loaded.store()
                .entries()
                .isEmpty());
    }

    @Test
    public void missingWrongTypedAndUnknownConfigurationFieldsRemainRawEvidence() {
        final Iterable<Consumer<NBTTagCompound>> mutations = Arrays.asList(
            tag -> tag.removeTag("version"),
            tag -> tag.setInteger("version", 99),
            tag -> tag.setString("type", "fluid"),
            tag -> tag.setString("capacity", "1600"),
            tag -> tag.setInteger("partitions", 0),
            tag -> tag.setInteger("partitions", 17),
            tag -> tag.setLong("capacity", -1),
            tag -> tag.setLong("configurationVersion", -1),
            tag -> tag.setLong("cacheVersion", -1),
            tag -> tag.setString("entries", "damaged"));
        for (final Consumer<NBTTagCompound> mutation : mutations) {
            final NBTTagCompound raw = this.one();
            mutation.accept(raw);
            this.assertIsolated(raw);
        }
        this.assertIsolated(new NBTTagCompound());
        this.assertIsolated(new NBTTagString("not a compound"));
        final NBTTagCompound unknown = this.one();
        unknown.setString("unrecognizedStock", "preserve this evidence");
        this.assertIsolated(unknown);
    }

    @Test
    public void invalidCountsSlotsIdentitiesAndIncompleteEntriesCannotMaterializePartialStock() {
        final Iterable<Consumer<NBTTagCompound>> mutations = Arrays.asList(
            tag -> tag.setLong("quantity", 0),
            tag -> tag.setLong("quantity", -1),
            tag -> tag.setInteger("quantity", 1),
            tag -> tag.setInteger("slot", -1),
            tag -> tag.setInteger("slot", 16),
            tag -> tag.removeTag("identity"),
            tag -> tag.getCompoundTag("identity")
                .removeTag("key"));
        for (final Consumer<NBTTagCompound> mutation : mutations) {
            final NBTTagCompound raw = this.one();
            mutation.accept(
                raw.getTagList("entries", 10)
                    .getCompoundTagAt(0));
            this.assertIsolated(raw);
        }
    }

    @Test
    public void duplicateIdentityEvenWithoutOverflowAndDuplicateSlotsAreNotSilentlyMerged() {
        for (final boolean sameIdentity : new boolean[] { true, false }) {
            final NBTTagCompound raw = this.one();
            final NBTTagList entries = raw.getTagList("entries", 10);
            final NBTTagCompound duplicate = (NBTTagCompound) entries.getCompoundTagAt(0)
                .copy();
            if (sameIdentity) duplicate.setInteger("slot", 1);
            else duplicate.setTag("identity", CODEC.write("different"));
            entries.appendTag(duplicate);
            this.assertIsolated(raw);
        }
    }

    @Test
    public void oversizedOrWrongElementListIsIsolated() {
        final NBTTagCompound raw = this.one();
        final NBTTagList entries = raw.getTagList("entries", 10);
        for (int i = 1; i <= 16; i++) entries.appendTag(
            entries.getCompoundTagAt(0)
                .copy());
        this.assertIsolated(raw);
        final NBTTagList wrong = new NBTTagList();
        wrong.appendTag(new NBTTagString("wrong element type"));
        raw.setTag("entries", wrong);
        this.assertIsolated(raw);
    }

    @Test
    public void quarantineEvidenceIsDefensiveAndStableAcrossRepeatedReloads() {
        final NBTTagCompound original = this.one();
        original.setLong("capacity", -1);
        PartitionNbt.Loaded<String> loaded = PartitionNbt.read(original, CODEC);
        ((NBTTagCompound) loaded.evidence()).setLong("capacity", 99);
        assertEquals(-1, ((NBTTagCompound) loaded.evidence()).getLong("capacity"));
        for (int i = 0; i < 100; i++) loaded = PartitionNbt.read(PartitionNbt.write(loaded, CODEC), CODEC);
        assertEquals(original, loaded.evidence());
    }

    @Test
    public void decoderFailureKeepsTheWholeOriginalInsteadOfValidPrefix() {
        final NBTTagCompound raw = this.one();
        final NBTTagCompound extra = (NBTTagCompound) raw.getTagList("entries", 10)
            .getCompoundTagAt(0)
            .copy();
        extra.setInteger("slot", 1);
        extra.setTag("identity", new NBTTagCompound());
        raw.getTagList("entries", 10)
            .appendTag(extra);
        this.assertIsolated(raw);
    }

    @Test
    public void versionExhaustionStopsMutationWithoutQuantityLoss() {
        final NBTTagCompound raw = this.one();
        raw.setLong("cacheVersion", Long.MAX_VALUE);
        final PartitionStore<String> store = PartitionNbt.read(raw, CODEC)
            .store();
        assertThrows(ArithmeticException.class, () -> store.remove("A", 1));
        final PartitionStore<String>.Transaction tx = store.transaction();
        tx.offer("B", 100);
        assertThrows(ArithmeticException.class, tx::commit);
        assertEquals(Long.MAX_VALUE, store.quantity("A"));
        assertEquals(0, store.quantity("B"));
        raw.setLong("configurationVersion", Long.MAX_VALUE);
        final PartitionStore<String> full = PartitionNbt.read(raw, CODEC)
            .store();
        assertThrows(ArithmeticException.class, () -> full.configure(2, 500));
        assertEquals(4, full.partitions());
    }

    private NBTTagCompound one() {
        final PartitionStore<String> store = new PartitionStore<>(1600);
        PartitionStoreTest.add(store, "A", Long.MAX_VALUE);
        return PartitionNbt.write(store, CODEC);
    }

    private void assertIsolated(final NBTBase raw) {
        final PartitionNbt.Loaded<String> loaded = PartitionNbt.read(raw, CODEC);
        assertTrue(loaded.isolated());
        assertEquals(raw, loaded.evidence());
        assertThrows(IllegalStateException.class, loaded::store);
    }
}
