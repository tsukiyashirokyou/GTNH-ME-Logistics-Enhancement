package com.kyou.gtnhmelogisticsenhancement.tile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagString;

import org.junit.Test;

/** Native NBT is real; item registry lookup is a stand-in because the mod pack is not bootstrapped here. */
public class CellInventoryNbtTest {

    private final Item cell = new Item();

    @Test
    public void validSixtyFourSlotInventoryKeepsPhysicalLocationsAndNestedCellData() {
        final NBTTagCompound root = new NBTTagCompound();
        final ItemStack[] cells = new ItemStack[64];
        for (int slot = 0; slot < cells.length; slot++) {
            cells[slot] = new ItemStack(this.cell, 1, slot);
            final NBTTagCompound nativeData = new NBTTagCompound();
            nativeData.setLong("stored", 1000000L + slot);
            cells[slot].setTagCompound(nativeData);
        }
        CellInventoryNbt.write(root, cells, null);
        final CellInventoryNbt.Loaded loaded = CellInventoryNbt.read(root, this::decode);
        assertNull(loaded.quarantine);
        for (int slot = 0; slot < cells.length; slot++) {
            assertEquals(slot, loaded.cells[slot].getItemDamage());
            assertEquals(cells[slot].getTagCompound(), loaded.cells[slot].getTagCompound());
            assertNotSame(cells[slot].getTagCompound(), loaded.cells[slot].getTagCompound());
        }
    }

    @Test
    public void missingInventoryAndEmptyLegacySlotsRemainEmpty() {
        final CellInventoryNbt.Loaded loaded = CellInventoryNbt.read(new NBTTagCompound(), this::decode);
        assertNull(loaded.quarantine);
        for (ItemStack item : loaded.cells) {
            assertNull(item);
        }
    }

    @Test
    public void wrongInventoryTypeIsPreservedInsteadOfCrashingOrBecomingStock() {
        final NBTTagCompound root = new NBTTagCompound();
        root.setString("inv", "raw evidence");
        final CellInventoryNbt.Loaded loaded = CellInventoryNbt.read(root, this::decode);
        assertEquals(root.getTag("inv"), loaded.quarantine);
        assertNotSame(root.getTag("inv"), loaded.quarantine);
        assertNull(loaded.cells[0]);
    }

    @Test
    public void malformedSlotAndUnknownExtraSlotQuarantineTheWholeInventory() {
        for (String key : new String[] { "item0", "item64" }) {
            final NBTTagCompound root = new NBTTagCompound();
            final NBTTagCompound inventory = new NBTTagCompound();
            inventory.setString(key, "damaged slot");
            root.setTag("inv", inventory);
            assertEquals(inventory, CellInventoryNbt.read(root, this::decode).quarantine);
        }
    }

    @Test
    public void missingModItemsAndThrowingDecodersNeverSilentlyDeleteTheirOriginalTags() {
        final NBTTagCompound root = this.oneCell(1);
        assertEquals(root.getTag("inv"), CellInventoryNbt.read(root, ignored -> null).quarantine);
        assertEquals(
            root.getTag("inv"),
            CellInventoryNbt
                .read(root, ignored -> { throw new IllegalStateException("Missing mod item"); }).quarantine);
    }

    @Test
    public void negativeZeroAndStackedCellsAreNotMadeIntoNativeHandlers() {
        for (int count : new int[] { -1, 0, 2, 64 }) {
            final NBTTagCompound root = this.oneCell(count);
            final CellInventoryNbt.Loaded loaded = CellInventoryNbt.read(root, this::decode);
            assertEquals(root.getTag("inv"), loaded.quarantine);
            assertNull(loaded.cells[0]);
        }
    }

    @Test
    public void aFailureAfterAValidSlotNeverLeavesBothCellsAndRawInventoryAvailable() {
        final NBTTagCompound root = this.oneCell(1);
        root.getCompoundTag("inv")
            .setString("item1", "broken");
        final CellInventoryNbt.Loaded loaded = CellInventoryNbt.read(root, this::decode);
        assertNull(loaded.cells[0]);
        final NBTTagCompound saved = new NBTTagCompound();
        CellInventoryNbt.write(saved, loaded.cells, loaded.quarantine);
        assertTrue(
            saved.getCompoundTag("inv")
                .hasNoTags());
        assertEquals(root.getTag("inv"), saved.getTag(CellInventoryNbt.QUARANTINE));
    }

    @Test
    public void quarantinedStateSurvivesRepeatedSavesWithoutAutomaticMaterialization() {
        final NBTTagCompound root = new NBTTagCompound();
        root.setTag(CellInventoryNbt.QUARANTINE, new NBTTagString("damaged original"));
        for (int reload = 0; reload < 3; reload++) {
            final CellInventoryNbt.Loaded loaded = CellInventoryNbt.read(
                root,
                ignored -> {
                    throw new AssertionError("An isolated record must never be decoded again automatically");
                });
            CellInventoryNbt.write(root, loaded.cells, loaded.quarantine);
        }
        assertEquals(new NBTTagString("damaged original"), root.getTag(CellInventoryNbt.QUARANTINE));
    }

    private NBTTagCompound oneCell(final int count) {
        final NBTTagCompound root = new NBTTagCompound();
        final NBTTagCompound inventory = new NBTTagCompound();
        final NBTTagCompound item = new NBTTagCompound();
        item.setByte("Count", (byte) count);
        item.setShort("Damage", (short) 4);
        inventory.setTag("item0", item);
        root.setTag("inv", inventory);
        return root;
    }

    private ItemStack decode(final NBTTagCompound tag) {
        final ItemStack stack = new ItemStack(this.cell, tag.getByte("Count"), tag.getShort("Damage"));
        if (tag.hasKey("tag", 10)) {
            stack.setTagCompound(
                (NBTTagCompound) tag.getCompoundTag("tag")
                    .copy());
        }
        return stack;
    }
}
