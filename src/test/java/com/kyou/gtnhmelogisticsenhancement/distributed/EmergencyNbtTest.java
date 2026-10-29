package com.kyou.gtnhmelogisticsenhancement.distributed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.junit.Test;

public class EmergencyNbtTest {

    @Test
    public void versionTwoPreservesMoreThanSixteenIdentitiesAndExactNormalPositions() {
        final PartitionStore<String> store = new PartitionStore<>(16, 1600);
        final PartitionStore<String>.Transaction tx = store.transaction(true);
        for (int i = 0; i < 100; i++) tx.offer("K" + i, Long.MAX_VALUE);
        tx.commit();
        store.configure(1, 0);
        final NBTTagCompound saved = PartitionNbt.write(store, PartitionNbtTest.CODEC);
        assertEquals(2, saved.getInteger("version"));
        final PartitionNbt.Loaded<String> restored = PartitionNbt.read(saved, PartitionNbtTest.CODEC);
        assertFalse(restored.isolated());
        assertEquals(
            100,
            restored.store()
                .identityCount());
        assertEquals(
            84,
            restored.store()
                .overflowIdentityCount());
        assertEquals(
            store.totalQuantity(),
            restored.store()
                .totalQuantity());
        assertEquals(saved, PartitionNbt.write(restored, PartitionNbtTest.CODEC));
        assertFalse(
            restored.store()
                .transaction()
                .offer("new", 1));
    }

    @Test
    public void actualLegacyVersionOneMigratesWithoutClippingShrunkStockOrChangingSlots() {
        final PartitionStore<String> store = new PartitionStore<>(16, 1600);
        for (int i = 0; i < 16; i++) PartitionStoreTest.add(store, "K" + i, 1000 + i);
        store.remove("K7", 1007);
        store.configure(1, 10);
        final NBTTagCompound legacy = PartitionNbt.write(store, PartitionNbtTest.CODEC);
        legacy.setInteger("version", 1);
        legacy.removeTag("failure");
        final NBTTagList entries = legacy.getTagList("entries", 10);
        for (int i = 0; i < entries.tagCount(); i++) entries.getCompoundTagAt(i)
            .removeTag("emergency");
        final PartitionNbt.Loaded<String> restored = PartitionNbt.read(legacy, PartitionNbtTest.CODEC);
        assertFalse(restored.isolated());
        assertEquals(
            store.totalQuantity(),
            restored.store()
                .totalQuantity());
        assertEquals(
            15,
            restored.store()
                .normalEntries()
                .size());
        assertEquals(
            8,
            restored.store()
                .entries()
                .get(7).slot);
        assertEquals(
            2,
            PartitionNbt.write(restored, PartitionNbtTest.CODEC)
                .getInteger("version"));
    }

    @Test
    public void corruptEmergencyFlagsSlotsAndDuplicateNormalOrEmergencyIdentitiesAreIsolated() {
        for (int type = 0; type < 5; type++) {
            final NBTTagCompound saved = this.emergencyStock();
            final NBTTagList entries = saved.getTagList("entries", 10);
            final NBTTagCompound emergency = entries.getCompoundTagAt(1);
            if (type == 0) emergency.setBoolean("emergency", false);
            if (type == 1) emergency.setInteger("slot", -2);
            if (type == 2) emergency.setByte("emergency", (byte) 2);
            if (type == 3) emergency.setTag(
                "identity",
                entries.getCompoundTagAt(0)
                    .getTag("identity")
                    .copy());
            if (type == 4) entries.appendTag(emergency.copy());
            this.assertIsolated(saved);
        }
    }

    @Test
    public void forcedReceiptFailurePersistsAsNonStockEvidenceAndCannotAutomaticallyReplay() {
        final PartitionStore<String> store = new PartitionStore<>(1, 400);
        EmergencyPartitionTest.emergency(store, "A", Long.MAX_VALUE);
        final PartitionStore<String>.Transaction output = store.transaction(true);
        output.offer("B", 7);
        assertThrows(ArithmeticException.class, () -> output.offer("A", 1));
        final NBTTagCompound saved = PartitionNbt.write(store, PartitionNbtTest.CODEC);
        final PartitionNbt.Loaded<String> result = PartitionNbt.read(saved, PartitionNbtTest.CODEC);
        assertFalse(result.isolated());
        assertNotNull(
            result.store()
                .failure());
        assertEquals(
            Long.valueOf(7),
            result.store()
                .failure().pending.get("B"));
        assertEquals(
            "A",
            result.store()
                .failure().attemptedIdentity);
        assertEquals(
            1,
            result.store()
                .failure().attemptedAmount);
        assertEquals(
            Long.MAX_VALUE,
            result.store()
                .quantity("A"));
        assertEquals(
            0,
            result.store()
                .quantity("B"));
        assertThrows(
            IllegalStateException.class,
            () -> result.store()
                .transaction(true));
        assertEquals(saved, PartitionNbt.write(result, PartitionNbtTest.CODEC));
    }

    @Test
    public void malformedFailureEvidenceNeverBecomesAnUnlockedUsableInventory() {
        final PartitionStore<String> store = new PartitionStore<>(1, 400);
        EmergencyPartitionTest.emergency(store, "A", Long.MAX_VALUE);
        final PartitionStore<String>.Transaction output = store.transaction(true);
        output.offer("B", 7);
        assertThrows(ArithmeticException.class, () -> output.offer("A", 1));
        for (int type = 0; type < 4; type++) {
            final NBTTagCompound raw = PartitionNbt.write(store, PartitionNbtTest.CODEC);
            final NBTTagCompound failure = raw.getCompoundTag("failure");
            if (type == 0) failure.removeTag("reason");
            if (type == 1) failure.getTagList("pending", 10)
                .getCompoundTagAt(0)
                .setLong("quantity", -1);
            if (type == 2) failure.setLong("cacheVersion", -1);
            if (type == 3) {
                final NBTTagList pending = failure.getTagList("pending", 10);
                pending.appendTag(
                    pending.getCompoundTagAt(0)
                        .copy());
            }
            this.assertIsolated(raw);
        }
    }

    @Test
    public void repeatedReloadAndDrainNeverLosesOrCopiesEmergencyStock() {
        PartitionNbt.Loaded<String> loaded = PartitionNbt.read(this.emergencyStock(), PartitionNbtTest.CODEC);
        for (int i = 0; i < 20; i++) {
            final PartitionStore<String> store = loaded.store();
            store.remove("B", 1);
            loaded = PartitionNbt.read(PartitionNbt.write(store, PartitionNbtTest.CODEC), PartitionNbtTest.CODEC);
            assertEquals(
                1000,
                loaded.store()
                    .quantity("A"));
            assertEquals(
                30 - i - 1,
                loaded.store()
                    .quantity("B"));
            assertEquals(
                1,
                loaded.store()
                    .overflowIdentityCount());
        }
        loaded.store()
            .remove("B", 10);
        assertEquals(
            0,
            loaded.store()
                .overflowIdentityCount());
        assertEquals(
            1,
            loaded.store()
                .identityCount());
    }

    private NBTTagCompound emergencyStock() {
        final PartitionStore<String> store = new PartitionStore<>(1, 400);
        EmergencyPartitionTest.emergency(store, "A", 1000);
        EmergencyPartitionTest.emergency(store, "B", 30);
        return PartitionNbt.write(store, PartitionNbtTest.CODEC);
    }

    private void assertIsolated(final NBTTagCompound raw) {
        final PartitionNbt.Loaded<String> result = PartitionNbt.read(raw, PartitionNbtTest.CODEC);
        assertTrue(result.isolated());
        assertEquals(raw, result.evidence());
        assertThrows(IllegalStateException.class, result::store);
    }
}
