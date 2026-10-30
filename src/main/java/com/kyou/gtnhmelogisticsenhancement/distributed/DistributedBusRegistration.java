package com.kyou.gtnhmelogisticsenhancement.distributed;

import net.minecraft.item.ItemStack;

import com.kyou.gtnhmelogisticsenhancement.GTNHMELogisticsEnhancementMod;

import appeng.api.AEApi;
import cpw.mods.fml.common.registry.GameRegistry;
import gregtech.api.GregTechAPI;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.Textures;
import gregtech.api.interfaces.IIconContainer;

/** Fixed candidate, not a dynamic allocator. Full-pack addon-ID collision acceptance remains mandatory. */
public final class DistributedBusRegistration {

    public static final int ITEM_BUS_ID = 32550;
    public static final int FLUID_HATCH_ID = 32551;
    public static final IIconContainer PARTITION_OVERLAY = Textures.BlockIcons
        .custom(GTNHMELogisticsEnhancementMod.MODID, "distributed_output_bus");
    public static final IIconContainer FLUID_OVERLAY = Textures.BlockIcons
        .custom(GTNHMELogisticsEnhancementMod.MODID, "distributed_output_hatch");
    private static ItemStack itemBus;
    private static ItemStack fluidHatch;

    private DistributedBusRegistration() {}

    public static void register() {
        if (itemBus != null || GregTechAPI.METATILEENTITIES[ITEM_BUS_ID] != null) throw new IllegalStateException(
            "Distributed ME Output Bus MTE ID 32550 already occupied; refusing overwrite");
        if (fluidHatch != null || GregTechAPI.METATILEENTITIES[FLUID_HATCH_ID] != null) throw new IllegalStateException(
            "Distributed ME Output Hatch MTE ID 32551 already occupied; refusing overwrite");
        itemBus = new MTEDistributedMEOutputBus(
            ITEM_BUS_ID,
            "gtnhmelogisticsenhancement.distributed_me_output_bus",
            "Distributed ME Output Bus").getStackForm(1);
        fluidHatch = new MTEDistributedMEOutputHatch(
            FLUID_HATCH_ID,
            "gtnhmelogisticsenhancement.distributed_me_output_hatch",
            "Distributed ME Output Hatch").getStackForm(1);
    }

    public static ItemStack item() {
        if (itemBus == null) throw new IllegalStateException("Distributed output bus has not been registered");
        return itemBus.copy();
    }

    public static ItemStack hatch() {
        if (fluidHatch == null) throw new IllegalStateException("Distributed output hatch has not been registered");
        return fluidHatch.copy();
    }

    public static void registerRecipe() {
        final ItemStack nativeBus = ItemList.Hatch_Output_Bus_ME.get(1);
        final ItemStack distribution = AEApi.instance()
            .definitions()
            .materials()
            .cardDistribution()
            .maybeStack(1)
            .orNull();
        if (nativeBus == null || distribution == null)
            throw new IllegalStateException("Missing distributed bus ingredient");
        GameRegistry.addRecipe(new SafeDistributedBusRecipe(item(), nativeBus, distribution));
        final ItemStack nativeHatch = ItemList.Hatch_Output_ME.get(1);
        if (nativeHatch == null) throw new IllegalStateException("Missing native ME output hatch ingredient");
        GameRegistry.addRecipe(new SafeDistributedBusRecipe(hatch(), nativeHatch, distribution));
    }
}
