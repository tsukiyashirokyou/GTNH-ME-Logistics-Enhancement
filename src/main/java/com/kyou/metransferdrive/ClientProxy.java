package com.kyou.metransferdrive;

import java.util.concurrent.ArrayBlockingQueue;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

import com.kyou.metransferdrive.client.GuiTransferDrive;
import com.kyou.metransferdrive.gui.ContainerTransferDrive;
import com.kyou.metransferdrive.gui.DriveGuiNetwork;
import com.kyou.metransferdrive.tile.TileMETransferDrive;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

public class ClientProxy extends CommonProxy {

    private final ArrayBlockingQueue<DriveGuiNetwork.StateMessage> states = new ArrayBlockingQueue<>(256);

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        FMLCommonHandler.instance()
            .bus()
            .register(this);
    }

    @Override
    public Object createDriveGui(EntityPlayer player, TileMETransferDrive tile) {
        return new GuiTransferDrive(new ContainerTransferDrive(player, tile));
    }

    @Override
    public void receiveGuiState(DriveGuiNetwork.StateMessage message) {
        this.states.offer(message);
    }

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        final EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        DriveGuiNetwork.StateMessage message;
        while ((message = this.states.poll()) != null) {
            if (player != null && player.openContainer instanceof ContainerTransferDrive
                && player.openContainer.windowId == message.window
                && message.state != null) {
                ((ContainerTransferDrive) player.openContainer).receiveState(message.state);
            }
        }
    }
}
