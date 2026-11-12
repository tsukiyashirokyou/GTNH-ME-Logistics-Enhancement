package com.kyou.gtnhmelogisticsenhancement.energy;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;

/** World metadata only. BaseMetaTileEntity alone serializes the real mStoredEnergy field. */
public final class MEEnergyNbt {

    static final String DATA = "meEnergy";
    static final int SCHEMA = 1;

    private MEEnergyNbt() {}

    public static void writeWorld(final NBTTagCompound root, final MEEnergyEndpointState state) {
        if (state.invalidData() != null) {
            root.setTag(
                DATA,
                state.invalidData()
                    .copy());
            return; // Keep unsupported/corrupt metadata isolated across saves instead of silently repairing it.
        }
        final NBTTagCompound data = new NBTTagCompound();
        data.setInteger("schema", SCHEMA);
        data.setString(
            "spec",
            state.spec()
                .key());
        if (state.spec()
            .role() == MEEnergySpec.Role.DYNAMO) {
            data.setInteger(
                "credit",
                state.lossAccount()
                    .credit());
        }
        root.setTag(DATA, data);
    }

    public static void readWorld(final NBTTagCompound root, final MEEnergyEndpointState state) {
        if (!root.hasKey(DATA)) {
            state.restore(0, "", null); // Fresh placement (possibly with native paint/cover tags).
            return;
        }
        final NBTBase payload = root.getTag(DATA);
        String fault = "";
        int credit = 0;
        if (!(payload instanceof NBTTagCompound)) {
            fault = "INVALID_ME_ENERGY_NBT";
        } else {
            final NBTTagCompound data = (NBTTagCompound) payload;
            if (!data.hasKey("schema", 3) || data.getInteger("schema") != SCHEMA) {
                fault = "UNSUPPORTED_ME_ENERGY_SCHEMA";
            } else if (!data.hasKey("spec", 8) || !state.spec()
                .key()
                .equals(data.getString("spec"))) {
                    fault = "ME_ENERGY_SPEC_MISMATCH";
                } else if (state.spec()
                    .role() == MEEnergySpec.Role.DYNAMO) {
                        credit = data.getInteger("credit");
                        if (!data.hasKey("credit", 3) || credit < 0 || credit >= 19) {
                            fault = "INVALID_ME_ENERGY_CREDIT";
                        }
                    } else if (data.hasKey("credit")) {
                        fault = "UNEXPECTED_ME_ENERGY_CREDIT";
                    }
        }
        state.restore(fault.isEmpty() ? credit : 0, fault, fault.isEmpty() ? null : payload);
    }

    /** Only native covers/paint may remain. Never export EU, credit or runtime/network identity. */
    public static void stripItemEnergy(final NBTTagCompound item) {
        item.removeTag(DATA);
        item.removeTag("mStoredEnergy");
        item.removeTag("mStoredSteam");
    }
}
