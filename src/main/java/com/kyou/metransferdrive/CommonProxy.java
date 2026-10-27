package com.kyou.metransferdrive;

import net.minecraft.entity.player.EntityPlayer;

import com.kyou.metransferdrive.block.BlockMETransferDrive;
import com.kyou.metransferdrive.gui.DriveGuiHandler;
import com.kyou.metransferdrive.gui.DriveGuiNetwork;
import com.kyou.metransferdrive.item.ItemNetworkBindingTool;
import com.kyou.metransferdrive.tile.TileMETransferDrive;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.registry.GameRegistry;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        Config.synchronizeConfiguration(event.getSuggestedConfigurationFile());

        METransferDriveMod.meTransferDrive = new BlockMETransferDrive();
        GameRegistry.registerBlock(METransferDriveMod.meTransferDrive, "meTransferDrive");
        GameRegistry.registerTileEntity(TileMETransferDrive.class, METransferDriveMod.MODID + ":meTransferDrive");

        METransferDriveMod.meNetworkBindingTool = new ItemNetworkBindingTool();
        GameRegistry.registerItem(METransferDriveMod.meNetworkBindingTool, "meNetworkBindingTool");

        METransferDriveMod.LOG.info("ME Transfer Drive version {}", Tags.VERSION);
    }

    public void init(FMLInitializationEvent event) {
        DriveGuiNetwork.init();
        NetworkRegistry.INSTANCE.registerGuiHandler(METransferDriveMod.instance, new DriveGuiHandler());
    }

    public Object createDriveGui(EntityPlayer player, TileMETransferDrive tile) {
        return null;
    }

    public void receiveGuiState(DriveGuiNetwork.StateMessage message) {}

    public void postInit(FMLPostInitializationEvent event) {
        RecipeLoader.register();
    }

    public void serverStarting(FMLServerStartingEvent event) {}
}
