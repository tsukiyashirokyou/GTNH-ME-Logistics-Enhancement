package com.kyou.gtnhmelogisticsenhancement.energy;

import net.minecraft.item.ItemStack;

import com.kyou.gtnhmelogisticsenhancement.GTNHMELogisticsEnhancementMod;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;

/** Fixed save identities. Preflight the complete range before constructing any hatch. */
public final class MEEnergyRegistration {

    private static final IMetaTileEntity[] REGISTERED = new IMetaTileEntity[162];
    private static boolean registered;

    private MEEnergyRegistration() {}

    public static void preflight() {
        for (final MEEnergyCatalog.Entry entry : MEEnergyCatalog.entries()) {
            final int id = entry.candidateMteId();
            if (id >= GregTechAPI.METATILEENTITIES.length || GregTechAPI.METATILEENTITIES[id] != null)
                throw new IllegalStateException("ME EU MTE ID " + id + " occupied; refusing all 162 registrations");
        }
    }

    public static void register() {
        if (registered) throw new IllegalStateException("ME EU hatches already registered");
        preflight();
        for (final MEEnergyCatalog.Entry entry : MEEnergyCatalog.entries()) {
            final MEEnergySpec spec = entry.spec();
            final int id = entry.candidateMteId();
            final String name = name(spec);
            final String regional = spec.tierName() + " "
                + spec.amperage()
                + "A ME "
                + (spec.role() == MEEnergySpec.Role.ENERGY ? "Energy Hatch" : "Dynamo Hatch");
            final MetaTileEntity machine;
            if (spec.family() == MEEnergySpec.Family.STANDARD) {
                machine = spec.role() == MEEnergySpec.Role.ENERGY
                    ? new MTEHatchMEEnergyStandard(id, name, regional, spec)
                    : new MTEHatchMEDynamoStandard(id, name, regional, spec);
            } else {
                machine = spec.role() == MEEnergySpec.Role.ENERGY ? new MTEHatchMEEnergyMulti(id, name, regional, spec)
                    : new MTEHatchMEDynamoMulti(id, name, regional, spec);
            }
            REGISTERED[id - MEEnergyCatalog.CANDIDATE_FIRST_ID] = machine;
        }
        registered = true;
        GTNHMELogisticsEnhancementMod.LOG.info("Registered 162 ME EU hatches at fixed MTE IDs 32552-32713");
    }

    public static String name(final MEEnergySpec spec) {
        return GTNHMELogisticsEnhancementMod.MODID + ".me_eu." + spec.key();
    }

    public static ItemStack stack(final MEEnergySpec spec) {
        final MEEnergyCatalog.Entry entry = MEEnergyCatalog.find(spec.key());
        if (!registered || entry == null)
            throw new IllegalStateException("ME EU hatch is not registered: " + spec.key());
        verify(entry);
        return REGISTERED[entry.candidateMteId() - MEEnergyCatalog.CANDIDATE_FIRST_ID].getStackForm(1);
    }

    /** Load-complete and server-start checks detect mods that registered after this addon. Never move IDs. */
    public static void verify() {
        if (!registered) throw new IllegalStateException("ME EU hatch registration missing");
        for (final MEEnergyCatalog.Entry entry : MEEnergyCatalog.entries()) verify(entry);
    }

    private static void verify(final MEEnergyCatalog.Entry entry) {
        final int id = entry.candidateMteId();
        if (GregTechAPI.METATILEENTITIES[id] != REGISTERED[id - MEEnergyCatalog.CANDIDATE_FIRST_ID])
            throw new IllegalStateException(
                "ME EU MTE ID " + id + " was replaced after registration; refusing startup");
    }
}
