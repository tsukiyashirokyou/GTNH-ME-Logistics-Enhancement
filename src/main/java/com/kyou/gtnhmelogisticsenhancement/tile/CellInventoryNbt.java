package com.kyou.gtnhmelogisticsenhancement.tile;

import java.util.function.Function;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;

/** A damaged inventory remains evidence, never both raw stock and materialized cells. */
public final class CellInventoryNbt {

    public static final String QUARANTINE = "invalidCellInventory";

    public static final class Loaded {

        public final ItemStack[] cells = new ItemStack[DriveSlotLayout.TOTAL_SLOTS];
        public NBTBase quarantine;
    }

    private CellInventoryNbt() {}

    public static Loaded read(final NBTTagCompound root, final Function<NBTTagCompound, ItemStack> loader) {
        final Loaded loaded = new Loaded();
        if (root.hasKey(QUARANTINE)) {
            loaded.quarantine = root.getTag(QUARANTINE)
                .copy();
            return loaded;
        }
        if (!root.hasKey("inv")) {
            return loaded;
        }
        final NBTBase raw = root.getTag("inv");
        try {
            if (!(raw instanceof NBTTagCompound)) {
                throw new IllegalArgumentException("Cell inventory is not a compound");
            }
            final NBTTagCompound inventory = (NBTTagCompound) raw;
            if (inventory.func_150296_c()
                .size() > loaded.cells.length) {
                throw new IllegalArgumentException("Too many cell entries");
            }
            for (final Object key : inventory.func_150296_c()) {
                final String name = (String) key;
                boolean known = false;
                for (int slot = 0; slot < loaded.cells.length; slot++) {
                    known |= name.equals("item" + slot);
                }
                if (!known || !inventory.hasKey(name, 10)) {
                    throw new IllegalArgumentException("Unknown or malformed cell entry");
                }
            }
            for (int slot = 0; slot < loaded.cells.length; slot++) {
                final NBTTagCompound item = inventory.getCompoundTag("item" + slot);
                if (item.hasNoTags()) {
                    continue;
                }
                final ItemStack cell = loader.apply((NBTTagCompound) item.copy());
                if (cell == null || cell.getItem() == null || cell.stackSize != 1) {
                    throw new IllegalArgumentException("Unreadable or stacked cell");
                }
                loaded.cells[slot] = cell;
            }
            return loaded;
        } catch (RuntimeException invalid) {
            final Loaded isolated = new Loaded();
            isolated.quarantine = raw.copy();
            return isolated;
        }
    }

    public static void write(final NBTTagCompound root, final ItemStack[] cells, final NBTBase quarantine) {
        final NBTTagCompound inventory = new NBTTagCompound();
        if (quarantine != null) {
            root.setTag(QUARANTINE, quarantine.copy());
        } else {
            root.removeTag(QUARANTINE);
            for (int slot = 0; slot < cells.length; slot++) {
                final NBTTagCompound item = new NBTTagCompound();
                if (cells[slot] != null) {
                    cells[slot].writeToNBT(item);
                }
                inventory.setTag("item" + slot, item);
            }
        }
        root.setTag("inv", inventory);
    }
}
