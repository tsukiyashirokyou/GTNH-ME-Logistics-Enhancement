package com.kyou.gtnhmelogisticsenhancement.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import org.junit.Test;

public class RecoveryEvidenceTest {

    @Test
    public void wrongTagTypesAndUnknownFieldsSurviveRoundTripExactly() {
        for (NBTBase evidence : new NBTBase[] { new NBTTagString("wrong type"), new NBTTagList(), record() }) {
            final NBTTagCompound root = new NBTTagCompound();
            root.setTag("transferRecovery", evidence);
            final NBTBase read = RecoveryEvidence.read(root);
            assertEquals(evidence, read);
            assertNotSame(evidence, read);
            final NBTTagCompound saved = new NBTTagCompound();
            RecoveryEvidence.write(saved, read);
            assertEquals(root, saved);
        }
    }

    @Test
    public void packedMachineCarriesOneIndependentSnapshotOfCellsAndTransactionEvidence() {
        final NBTTagCompound state = new NBTTagCompound();
        final NBTTagCompound inventory = new NBTTagCompound();
        inventory.setString("item17", "original cell NBT stand-in");
        state.setTag("inv", inventory);
        state.setTag("transferRecovery", record());
        final ItemStack packed = RecoveryEvidence.pack(new ItemStack(new Item()), state);
        final NBTTagCompound restored = RecoveryEvidence.packedState(
            packed.getTagCompound()
                .getTag("metransferdriveRecovery"));
        assertEquals(1, packed.stackSize);
        assertEquals(state, restored);
        assertNotSame(state, restored);
        inventory.removeTag("item17");
        assertTrue(
            restored.getCompoundTag("inv")
                .hasKey("item17"));
        assertEquals(
            17,
            restored.getCompoundTag("transferRecovery")
                .getInteger("slot"));
    }

    @Test
    public void invalidPackedItemIsKeptAsEvidenceInsteadOfBeingSilentlyAcceptedAsAnEmptyMachine() {
        final NBTTagString raw = new NBTTagString("malformed machine state");
        final NBTTagCompound restored = RecoveryEvidence.packedState(raw);
        assertEquals(
            raw,
            restored.getCompoundTag("transferRecovery")
                .getTag("invalidPackedState"));
    }

    @Test
    public void serializedNegativeAmountsRemainEvidenceAndAreNeverConvertedIntoRefunds() {
        final NBTTagCompound raw = record();
        raw.setLong("held", Long.MIN_VALUE);
        final NBTTagCompound root = new NBTTagCompound();
        root.setTag("transferRecovery", raw);
        assertEquals(Long.MIN_VALUE, ((NBTTagCompound) RecoveryEvidence.read(root)).getLong("held"));
    }

    private static NBTTagCompound record() {
        final NBTTagCompound record = new NBTTagCompound();
        record.setInteger("slot", 17);
        record.setString("phase", "UNCERTAIN");
        record.setString("unknownFutureEvidence", "keep");
        record.setLong("held", 30);
        record.setLong("delivered", 50);
        return record;
    }
}
