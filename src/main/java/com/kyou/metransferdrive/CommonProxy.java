package com.kyou.metransferdrive;

import com.kyou.metransferdrive.block.BlockMETransferDrive;
import com.kyou.metransferdrive.tile.TileMETransferDrive;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.registry.GameRegistry;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        Config.synchronizeConfiguration(event.getSuggestedConfigurationFile());

        METransferDriveMod.meTransferDrive = new BlockMETransferDrive();
        GameRegistry.registerBlock(METransferDriveMod.meTransferDrive, "meTransferDrive");
        GameRegistry.registerTileEntity(TileMETransferDrive.class, METransferDriveMod.MODID + ":meTransferDrive");

        METransferDriveMod.LOG.info("ME Transfer Drive version {}", Tags.VERSION);
    }

    public void init(FMLInitializationEvent event) {}

    public void postInit(FMLPostInitializationEvent event) {}

    public void serverStarting(FMLServerStartingEvent event) {}
}
