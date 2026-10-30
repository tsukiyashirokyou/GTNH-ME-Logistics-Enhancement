package com.kyou.gtnhmelogisticsenhancement;

import net.minecraft.entity.player.EntityPlayer;

import com.kyou.gtnhmelogisticsenhancement.block.BlockMETransferDrive;
import com.kyou.gtnhmelogisticsenhancement.distributed.DistributedBusRegistration;
import com.kyou.gtnhmelogisticsenhancement.distributed.DistributedOutputEnergy;
import com.kyou.gtnhmelogisticsenhancement.gui.DriveGuiHandler;
import com.kyou.gtnhmelogisticsenhancement.gui.DriveGuiNetwork;
import com.kyou.gtnhmelogisticsenhancement.item.ItemNetworkBindingTool;
import com.kyou.gtnhmelogisticsenhancement.tile.TileMETransferDrive;
import com.kyou.gtnhmelogisticsenhancement.transfer.TransferScheduler;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.registry.GameRegistry;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        Config.synchronizeConfiguration(event.getSuggestedConfigurationFile());

        GTNHMELogisticsEnhancementMod.meTransferDrive = new BlockMETransferDrive();
        GameRegistry.registerBlock(GTNHMELogisticsEnhancementMod.meTransferDrive, "meTransferDrive");
        GameRegistry
            .registerTileEntity(TileMETransferDrive.class, GTNHMELogisticsEnhancementMod.MODID + ":meTransferDrive");

        GTNHMELogisticsEnhancementMod.meNetworkBindingTool = new ItemNetworkBindingTool();
        GameRegistry.registerItem(GTNHMELogisticsEnhancementMod.meNetworkBindingTool, "meNetworkBindingTool");

        GTNHMELogisticsEnhancementMod.LOG.info("{} version {}", GTNHMELogisticsEnhancementMod.MODNAME, Tags.VERSION);
    }

    public void init(FMLInitializationEvent event) {
        DistributedBusRegistration.register();
        DistributedOutputEnergy.init();
        TransferScheduler.init();
        DriveGuiNetwork.init();
        NetworkRegistry.INSTANCE.registerGuiHandler(GTNHMELogisticsEnhancementMod.instance, new DriveGuiHandler());
    }

    public Object createDriveGui(EntityPlayer player, TileMETransferDrive tile) {
        return null;
    }

    public void receiveGuiState(DriveGuiNetwork.StateMessage message) {}

    public void postInit(FMLPostInitializationEvent event) {
        RecipeLoader.register();
        DistributedBusRegistration.registerRecipe();
    }

    public void serverStarting(FMLServerStartingEvent event) {}
}
