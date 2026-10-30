package com.kyou.gtnhmelogisticsenhancement.distributed;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** GT restores Inventory before MTE.loadNBTData; retain any entry that could not be restored exactly. */
final class DistributedMachineNbt {

    private DistributedMachineNbt() {}

    static NBTBase cardEvidence(final NBTTagCompound tag, final ItemStack card) {
        if (tag.hasKey("distributedCardEvidence")) return tag.getTag("distributedCardEvidence")
            .copy();
        if (!tag.hasKey("Inventory")) return null;
        final NBTBase raw = tag.getTag("Inventory");
        final NBTTagList inventory = tag.getTagList("Inventory", 10);
        boolean valid = raw instanceof NBTTagList && inventory.tagCount() <= 1;
        if (raw instanceof NBTTagList && ((NBTTagList) raw).tagCount() != inventory.tagCount()) valid = false;
        if (inventory.tagCount() == 1) {
            final NBTTagCompound entry = inventory.getCompoundTagAt(0);
            final ItemStack decoded = ItemStack.loadItemStackFromNBT(entry);
            valid &= entry.hasKey("IntSlot", 3) && entry.getInteger("IntSlot") == 0
                && decoded != null
                && decoded.stackSize == 1
                && ItemStack.areItemStacksEqual(decoded, card);
        }
        return valid ? null : raw.copy();
    }

    static void writeCard(final NBTTagCompound tag, final ItemStack card) {
        final NBTTagList inventory = new NBTTagList();
        if (card != null) {
            final NBTTagCompound entry = new NBTTagCompound();
            card.writeToNBT(entry);
            entry.setInteger("IntSlot", 0);
            inventory.appendTag(entry);
        }
        tag.setTag("Inventory", inventory);
    }
}
