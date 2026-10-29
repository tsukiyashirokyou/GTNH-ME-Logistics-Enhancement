package com.kyou.gtnhmelogisticsenhancement;

import net.minecraft.block.Block;
import net.minecraft.item.Item;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.kyou.gtnhmelogisticsenhancement.gui.DriveGuiNetwork;
import com.kyou.gtnhmelogisticsenhancement.transfer.TransferScheduler;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;

@Mod(
    modid = GTNHMELogisticsEnhancementMod.MODID,
    version = Tags.VERSION,
    name = GTNHMELogisticsEnhancementMod.MODNAME,
    dependencies = "required-after:appliedenergistics2",
    acceptedMinecraftVersions = "[1.7.10]")
public class GTNHMELogisticsEnhancementMod {

    public static final String MODID = "gtnhmelogisticsenhancement";
    public static final String MODNAME = "GTNH ME Logistics Enhancement";
    public static final Logger LOG = LogManager.getLogger(MODID);
    public static Block meTransferDrive;
    public static Item meNetworkBindingTool;

    @Mod.Instance(MODID)
    public static GTNHMELogisticsEnhancementMod instance;

    @SidedProxy(
        clientSide = "com.kyou.gtnhmelogisticsenhancement.ClientProxy",
        serverSide = "com.kyou.gtnhmelogisticsenhancement.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        TransferScheduler.clear();
        DriveGuiNetwork.clearServerQueue();
    }
}
