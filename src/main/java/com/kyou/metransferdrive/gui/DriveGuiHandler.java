package com.kyou.metransferdrive.gui;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;

import com.kyou.metransferdrive.METransferDriveMod;
import com.kyou.metransferdrive.tile.TileMETransferDrive;

import cpw.mods.fml.common.network.IGuiHandler;

public final class DriveGuiHandler implements IGuiHandler {

    public static final int MACHINE = 0;

    private TileMETransferDrive drive(final int id, final World world, final int x, final int y, final int z) {
        if (id != MACHINE || !world.getChunkProvider()
            .chunkExists(x >> 4, z >> 4)) {
            return null;
        }
        final TileEntity tile = world.getTileEntity(x, y, z);
        return tile instanceof TileMETransferDrive && !tile.isInvalid() ? (TileMETransferDrive) tile : null;
    }

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        final TileMETransferDrive tile = this.drive(id, world, x, y, z);
        if (tile == null) {
            return null;
        }
        final ContainerTransferDrive container = new ContainerTransferDrive(player, tile);
        if (!container.canInteractWith(player)) {
            player.addChatMessage(new ChatComponentTranslation("metransferdrive.gui.denied"));
            return null;
        }
        return container;
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        final TileMETransferDrive tile = this.drive(id, world, x, y, z);
        return tile == null ? null : METransferDriveMod.proxy.createDriveGui(player, tile);
    }
}
