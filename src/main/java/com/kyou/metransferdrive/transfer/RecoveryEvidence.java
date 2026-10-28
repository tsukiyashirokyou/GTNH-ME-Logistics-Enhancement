package com.kyou.metransferdrive.transfer;

import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;

import appeng.api.storage.data.IAEStack;

/** Preserves arbitrary damaged recovery tags without interpreting them as recoverable inventory. */
public final class RecoveryEvidence {

    private RecoveryEvidence() {}

    /** Serialize each registered type through its own native stack API; this record never authorizes a refund. */
    static NBTTagCompound snapshot(final TransferTransaction<IAEStack<?>> transaction, final int sourceSlot,
        final UUID targetId, final NBTTagCompound unexpectedReturn) {
        final NBTTagCompound recovery = new NBTTagCompound();
        final IAEStack<?> key = transaction.getKey();
        final NBTTagCompound payload = new NBTTagCompound();
        key.writeToNBT(payload);
        recovery.setTag("payload", payload);
        recovery.setString(
            "type",
            key.getStackType()
                .getId());
        recovery.setString(
            "phase",
            transaction.getPhase()
                .name());
        recovery.setString(
            "uncertainOperation",
            transaction.getUncertainOperation()
                .name());
        recovery.setInteger("slot", sourceSlot);
        recovery.setLong("requested", transaction.getRequested());
        recovery.setLong("held", transaction.getHeld());
        recovery.setLong("delivered", transaction.getDelivered());
        recovery.setLong("reportedAmount", transaction.getReportedAmount());
        recovery.setString("failure", transaction.getFailureDescription());
        if (targetId != null) {
            recovery.setString("targetId", targetId.toString());
        }
        if (unexpectedReturn != null) {
            recovery.setTag("unexpectedReturn", unexpectedReturn.copy());
        }
        return recovery;
    }

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
