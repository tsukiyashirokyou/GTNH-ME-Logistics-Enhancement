package com.kyou.metransferdrive;

import net.minecraft.block.Block;
import net.minecraft.item.Item;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.kyou.metransferdrive.gui.DriveGuiNetwork;
import com.kyou.metransferdrive.transfer.TransferScheduler;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;

@Mod(
    modid = METransferDriveMod.MODID,
    version = Tags.VERSION,
    name = "ME Transfer Drive",
    dependencies = "required-after:appliedenergistics2",
    acceptedMinecraftVersions = "[1.7.10]")
public class METransferDriveMod {

    public static final String MODID = "metransferdrive";
    public static final Logger LOG = LogManager.getLogger(MODID);
    public static Block meTransferDrive;
    public static Item meNetworkBindingTool;

    @Mod.Instance(MODID)
    public static METransferDriveMod instance;

    @SidedProxy(
        clientSide = "com.kyou.metransferdrive.ClientProxy",
        serverSide = "com.kyou.metransferdrive.CommonProxy")
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
