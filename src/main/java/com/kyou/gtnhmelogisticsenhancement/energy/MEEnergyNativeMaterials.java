package com.kyou.gtnhmelogisticsenhancement.energy;

import net.minecraft.item.ItemStack;

import gregtech.api.enums.ItemList;
import gregtech.api.interfaces.IItemContainer;
import tectech.thing.CustomItemList;

/** Explicit GT5U 5.09.54.265 LoaderMetaTileEntities / TT MachineLoader item definitions. */
final class MEEnergyNativeMaterials {

    private MEEnergyNativeMaterials() {}

    private static final ItemList[] ENERGY = { ItemList.Hatch_Energy_IV, ItemList.Hatch_Energy_LuV,
        ItemList.Hatch_Energy_ZPM, ItemList.Hatch_Energy_UV, ItemList.Hatch_Energy_UHV, ItemList.Hatch_Energy_UEV,
        ItemList.Hatch_Energy_UIV, ItemList.Hatch_Energy_UMV, ItemList.Hatch_Energy_UXV };
    private static final ItemList[] DYNAMO = { ItemList.Hatch_Dynamo_IV, ItemList.Hatch_Dynamo_LuV,
        ItemList.Hatch_Dynamo_ZPM, ItemList.Hatch_Dynamo_UV, ItemList.Hatch_Dynamo_UHV, ItemList.Hatch_Dynamo_UEV,
        ItemList.Hatch_Dynamo_UIV, ItemList.Hatch_Dynamo_UMV, ItemList.Hatch_Dynamo_UXV };
    private static final CustomItemList[][] ENERGY_MULTI = {
        { CustomItemList.eM_energyMulti4_IV, CustomItemList.eM_energyMulti16_IV, CustomItemList.eM_energyMulti64_IV },
        { CustomItemList.eM_energyMulti4_LuV, CustomItemList.eM_energyMulti16_LuV,
            CustomItemList.eM_energyMulti64_LuV },
        { CustomItemList.eM_energyMulti4_ZPM, CustomItemList.eM_energyMulti16_ZPM,
            CustomItemList.eM_energyMulti64_ZPM },
        { CustomItemList.eM_energyMulti4_UV, CustomItemList.eM_energyMulti16_UV, CustomItemList.eM_energyMulti64_UV },
        { CustomItemList.eM_energyMulti4_UHV, CustomItemList.eM_energyMulti16_UHV,
            CustomItemList.eM_energyMulti64_UHV },
        { CustomItemList.eM_energyMulti4_UEV, CustomItemList.eM_energyMulti16_UEV,
            CustomItemList.eM_energyMulti64_UEV },
        { CustomItemList.eM_energyMulti4_UIV, CustomItemList.eM_energyMulti16_UIV,
            CustomItemList.eM_energyMulti64_UIV },
        { CustomItemList.eM_energyMulti4_UMV, CustomItemList.eM_energyMulti16_UMV,
            CustomItemList.eM_energyMulti64_UMV },
        { CustomItemList.eM_energyMulti4_UXV, CustomItemList.eM_energyMulti16_UXV,
            CustomItemList.eM_energyMulti64_UXV } };
    private static final CustomItemList[][] ENERGY_LASER = { { CustomItemList.eM_energyTunnel1_IV },
        { CustomItemList.eM_energyTunnel1_LuV, CustomItemList.eM_energyTunnel2_LuV },
        { CustomItemList.eM_energyTunnel1_ZPM, CustomItemList.eM_energyTunnel2_ZPM,
            CustomItemList.eM_energyTunnel3_ZPM },
        { CustomItemList.eM_energyTunnel1_UV, CustomItemList.eM_energyTunnel2_UV, CustomItemList.eM_energyTunnel3_UV,
            CustomItemList.eM_energyTunnel4_UV },
        { CustomItemList.eM_energyTunnel1_UHV, CustomItemList.eM_energyTunnel2_UHV, CustomItemList.eM_energyTunnel3_UHV,
            CustomItemList.eM_energyTunnel4_UHV, CustomItemList.eM_energyTunnel5_UHV },
        { CustomItemList.eM_energyTunnel1_UEV, CustomItemList.eM_energyTunnel2_UEV, CustomItemList.eM_energyTunnel3_UEV,
            CustomItemList.eM_energyTunnel4_UEV, CustomItemList.eM_energyTunnel5_UEV,
            CustomItemList.eM_energyTunnel6_UEV },
        { CustomItemList.eM_energyTunnel1_UIV, CustomItemList.eM_energyTunnel2_UIV, CustomItemList.eM_energyTunnel3_UIV,
            CustomItemList.eM_energyTunnel4_UIV, CustomItemList.eM_energyTunnel5_UIV,
            CustomItemList.eM_energyTunnel6_UIV, CustomItemList.eM_energyTunnel7_UIV },
        { CustomItemList.eM_energyTunnel1_UMV, CustomItemList.eM_energyTunnel2_UMV, CustomItemList.eM_energyTunnel3_UMV,
            CustomItemList.eM_energyTunnel4_UMV, CustomItemList.eM_energyTunnel5_UMV,
            CustomItemList.eM_energyTunnel6_UMV, CustomItemList.eM_energyTunnel7_UMV,
            CustomItemList.eM_energyTunnel8_UMV },
        { CustomItemList.eM_energyTunnel1_UXV, CustomItemList.eM_energyTunnel2_UXV, CustomItemList.eM_energyTunnel3_UXV,
            CustomItemList.eM_energyTunnel4_UXV, CustomItemList.eM_energyTunnel5_UXV,
            CustomItemList.eM_energyTunnel6_UXV, CustomItemList.eM_energyTunnel7_UXV,
            CustomItemList.eM_energyTunnel8_UXV, CustomItemList.eM_energyTunnel9_UXV } };
    private static final CustomItemList[][] DYNAMO_MULTI = {
        { CustomItemList.eM_dynamoMulti4_IV, CustomItemList.eM_dynamoMulti16_IV, CustomItemList.eM_dynamoMulti64_IV },
        { CustomItemList.eM_dynamoMulti4_LuV, CustomItemList.eM_dynamoMulti16_LuV,
            CustomItemList.eM_dynamoMulti64_LuV },
        { CustomItemList.eM_dynamoMulti4_ZPM, CustomItemList.eM_dynamoMulti16_ZPM,
            CustomItemList.eM_dynamoMulti64_ZPM },
        { CustomItemList.eM_dynamoMulti4_UV, CustomItemList.eM_dynamoMulti16_UV, CustomItemList.eM_dynamoMulti64_UV,
            CustomItemList.eM_dynamoMulti256_UV },
        { CustomItemList.eM_dynamoMulti4_UHV, CustomItemList.eM_dynamoMulti16_UHV, CustomItemList.eM_dynamoMulti64_UHV,
            CustomItemList.eM_dynamoMulti256_UHV },
        { CustomItemList.eM_dynamoMulti4_UEV, CustomItemList.eM_dynamoMulti16_UEV, CustomItemList.eM_dynamoMulti64_UEV,
            CustomItemList.eM_dynamoMulti256_UEV },
        { CustomItemList.eM_dynamoMulti4_UIV, CustomItemList.eM_dynamoMulti16_UIV, CustomItemList.eM_dynamoMulti64_UIV,
            CustomItemList.eM_dynamoMulti256_UIV },
        { CustomItemList.eM_dynamoMulti4_UMV, CustomItemList.eM_dynamoMulti16_UMV, CustomItemList.eM_dynamoMulti64_UMV,
            CustomItemList.eM_dynamoMulti256_UMV },
        { CustomItemList.eM_dynamoMulti4_UXV, CustomItemList.eM_dynamoMulti16_UXV, CustomItemList.eM_dynamoMulti64_UXV,
            CustomItemList.eM_dynamoMulti256_UXV } };
    private static final CustomItemList[][] DYNAMO_LASER = { { CustomItemList.eM_dynamoTunnel1_IV },
        { CustomItemList.eM_dynamoTunnel1_LuV, CustomItemList.eM_dynamoTunnel2_LuV },
        { CustomItemList.eM_dynamoTunnel1_ZPM, CustomItemList.eM_dynamoTunnel2_ZPM,
            CustomItemList.eM_dynamoTunnel3_ZPM },
        { CustomItemList.eM_dynamoTunnel1_UV, CustomItemList.eM_dynamoTunnel2_UV, CustomItemList.eM_dynamoTunnel3_UV,
            CustomItemList.eM_dynamoTunnel4_UV },
        { CustomItemList.eM_dynamoTunnel1_UHV, CustomItemList.eM_dynamoTunnel2_UHV, CustomItemList.eM_dynamoTunnel3_UHV,
            CustomItemList.eM_dynamoTunnel4_UHV, CustomItemList.eM_dynamoTunnel5_UHV },
        { CustomItemList.eM_dynamoTunnel1_UEV, CustomItemList.eM_dynamoTunnel2_UEV, CustomItemList.eM_dynamoTunnel3_UEV,
            CustomItemList.eM_dynamoTunnel4_UEV, CustomItemList.eM_dynamoTunnel5_UEV,
            CustomItemList.eM_dynamoTunnel6_UEV },
        { CustomItemList.eM_dynamoTunnel1_UIV, CustomItemList.eM_dynamoTunnel2_UIV, CustomItemList.eM_dynamoTunnel3_UIV,
            CustomItemList.eM_dynamoTunnel4_UIV, CustomItemList.eM_dynamoTunnel5_UIV,
            CustomItemList.eM_dynamoTunnel6_UIV, CustomItemList.eM_dynamoTunnel7_UIV },
        { CustomItemList.eM_dynamoTunnel1_UMV, CustomItemList.eM_dynamoTunnel2_UMV, CustomItemList.eM_dynamoTunnel3_UMV,
            CustomItemList.eM_dynamoTunnel4_UMV, CustomItemList.eM_dynamoTunnel5_UMV,
            CustomItemList.eM_dynamoTunnel6_UMV, CustomItemList.eM_dynamoTunnel7_UMV,
            CustomItemList.eM_dynamoTunnel8_UMV },
        { CustomItemList.eM_dynamoTunnel1_UXV, CustomItemList.eM_dynamoTunnel2_UXV, CustomItemList.eM_dynamoTunnel3_UXV,
            CustomItemList.eM_dynamoTunnel4_UXV, CustomItemList.eM_dynamoTunnel5_UXV,
            CustomItemList.eM_dynamoTunnel6_UXV, CustomItemList.eM_dynamoTunnel7_UXV,
            CustomItemList.eM_dynamoTunnel8_UXV, CustomItemList.eM_dynamoTunnel9_UXV } };

    static IItemContainer baseDefinition(final MEEnergySpec spec) {
        final int tier = spec.tier() - MEEnergySpec.MIN_TIER;
        final boolean energy = spec.role() == MEEnergySpec.Role.ENERGY;
        if (spec.family() == MEEnergySpec.Family.STANDARD) return (energy ? ENERGY : DYNAMO)[tier];
        final int a = spec.nativeBaseAmperage();
        return (energy ? ENERGY_MULTI : DYNAMO_MULTI)[tier][a == 4 ? 0 : a == 16 ? 1 : a == 64 ? 2 : 3];
    }

    static IItemContainer laserDefinition(final MEEnergySpec spec) {
        if (!spec.requiresLaserIngredient())
            throw new IllegalArgumentException("No laser ingredient for " + spec.key());
        int index = 0;
        for (int a = 256; a < spec.amperage(); a *= 4) index++;
        return (spec.role() == MEEnergySpec.Role.ENERGY ? ENERGY_LASER : DYNAMO_LASER)[spec.tier()
            - MEEnergySpec.MIN_TIER][index];
    }

    static ItemStack base(final MEEnergySpec spec) {
        return resolve(baseDefinition(spec), spec.key());
    }

    static ItemStack laser(final MEEnergySpec spec) {
        return resolve(laserDefinition(spec), spec.key());
    }

    private static ItemStack resolve(final IItemContainer item, final String key) {
        if (!item.hasBeenSet()) throw new IllegalStateException("Native ME EU recipe material missing: " + key);
        final ItemStack result = item.get(1);
        if (result == null || result.getItem() == null || result.stackSize != 1)
            throw new IllegalStateException("Invalid native ME EU recipe material: " + key);
        return result.copy();
    }
}
