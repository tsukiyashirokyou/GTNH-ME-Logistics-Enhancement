package com.kyou.metransferdrive.gui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

/** Exercises native ItemStack splitting and slot mutation in a plain JVM, without a Minecraft world. */
public class CellSlotTransferTest {

    private final InventoryBasic inventory = new InventoryBasic("cells", false, 64);
    private final List<Slot> slots = new ArrayList<>();
    private final Item cellItem = new Item();
    private boolean accepts = true;

    public CellSlotTransferTest() {
        for (int slot = 0; slot < 64; slot++) {
            this.slots.add(new Slot(this.inventory, slot, 0, 0) {

                @Override
                public boolean isItemValid(ItemStack stack) {
                    return accepts && stack.getItem() == cellItem;
                }
            });
        }
    }

    @Test
    public void shiftInsertionMovesOnlyIntoTheSelectedPhysicalGroup() {
        final ItemStack source = new ItemStack(this.cellItem, 10, 7);
        ContainerTransferDrive.insertCells(source, 6, this.slots);
        assertEquals(2, source.stackSize);
        for (int slot = 0; slot < 64; slot++) {
            if (slot >= 48 && slot < 56) {
                assertEquals(1, this.inventory.getStackInSlot(slot).stackSize);
                assertEquals(
                    7,
                    this.inventory.getStackInSlot(slot)
                        .getItemDamage());
            } else {
                assertNull(this.inventory.getStackInSlot(slot));
            }
        }
    }

    @Test
    public void filledCellsKeepIndependentNativeNbtIncludingTheirContents() {
        final ItemStack source = new ItemStack(this.cellItem, 2);
        final NBTTagCompound nbt = new NBTTagCompound();
        final NBTTagCompound content = new NBTTagCompound();
        content.setLong("storedAmount", 123456789L);
        nbt.setTag("nativeCellData", content);
        source.setTagCompound(nbt);
        ContainerTransferDrive.insertCells(source, 0, this.slots);
        final ItemStack first = this.inventory.getStackInSlot(0);
        final ItemStack second = this.inventory.getStackInSlot(1);
        assertEquals(nbt, first.getTagCompound());
        assertEquals(nbt, second.getTagCompound());
        assertNotSame(nbt, first.getTagCompound());
        assertNotSame(first.getTagCompound(), second.getTagCompound());
        first.getTagCompound()
            .getCompoundTag("nativeCellData")
            .setLong("storedAmount", 1);
        assertEquals(
            123456789L,
            second.getTagCompound()
                .getCompoundTag("nativeCellData")
                .getLong("storedAmount"));
        assertEquals(
            123456789L,
            nbt.getCompoundTag("nativeCellData")
                .getLong("storedAmount"));
    }

    @Test
    public void existingCellIsNeverMergedOrOverwritten() {
        final ItemStack installed = new ItemStack(this.cellItem);
        this.inventory.setInventorySlotContents(0, installed);
        final ItemStack source = new ItemStack(this.cellItem, 8);
        ContainerTransferDrive.insertCells(source, 0, this.slots);
        assertSame(installed, this.inventory.getStackInSlot(0));
        assertEquals(1, installed.stackSize);
        assertEquals(1, source.stackSize);
    }

    @Test
    public void lockedOrRejectedSlotsDoNotConsumeThePlayersStack() {
        this.accepts = false;
        final ItemStack source = new ItemStack(this.cellItem, 3);
        ContainerTransferDrive.insertCells(source, 0, this.slots);
        assertEquals(3, source.stackSize);
        assertNull(this.inventory.getStackInSlot(0));
    }

    @Test
    public void nonCellItemsAreRejectedAndRepeatedExecutionCannotDuplicateCells() {
        final ItemStack invalid = new ItemStack(new Item(), 3);
        ContainerTransferDrive.insertCells(invalid, 0, this.slots);
        assertEquals(3, invalid.stackSize);
        final ItemStack source = new ItemStack(this.cellItem, 2);
        ContainerTransferDrive.insertCells(source, 0, this.slots);
        ContainerTransferDrive.insertCells(source, 0, this.slots);
        assertEquals(0, source.stackSize);
        assertEquals(1, this.inventory.getStackInSlot(0).stackSize);
        assertEquals(1, this.inventory.getStackInSlot(1).stackSize);
        assertNull(this.inventory.getStackInSlot(2));
        assertFalse(
            this.inventory.getStackInSlot(0)
                .hasTagCompound());
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidPagesAreRejectedBeforeTouchingInventory() {
        ContainerTransferDrive.insertCells(new ItemStack(this.cellItem), 8, this.slots);
    }
}
