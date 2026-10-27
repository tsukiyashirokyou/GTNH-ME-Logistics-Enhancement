package com.kyou.metransferdrive.transfer;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;

/** Preserves arbitrary damaged recovery tags without interpreting them as recoverable inventory. */
public final class RecoveryEvidence {

    private RecoveryEvidence() {}

    public static NBTBase read(final NBTTagCompound root) {
        return root.hasKey("transferRecovery") ? root.getTag("transferRecovery")
            .copy() : null;
    }

    public static void write(final NBTTagCompound root, final NBTBase evidence) {
        root.setTag("transferRecovery", evidence.copy());
    }

    public static NBTTagCompound packedState(final NBTBase raw) {
        if (raw instanceof NBTTagCompound) {
            return (NBTTagCompound) raw;
        }
        final NBTTagCompound evidence = new NBTTagCompound();
        evidence.setTag("invalidPackedState", raw.copy());
        final NBTTagCompound state = new NBTTagCompound();
        state.setTag("transferRecovery", evidence);
        return state;
    }

    public static ItemStack pack(final ItemStack machine, final NBTTagCompound state) {
        final NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("metransferdriveRecovery", state.copy());
        machine.setTagCompound(tag);
        return machine;
    }
}
