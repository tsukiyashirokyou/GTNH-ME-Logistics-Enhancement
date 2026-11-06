package com.kyou.gtnhmelogisticsenhancement.item;

import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Player-facing setup instructions for the drive's inventory item. */
public final class ItemMETransferDrive extends ItemBlock {

    public ItemMETransferDrive(final Block block) {
        super(block);
    }

    @Override
    @SideOnly(Side.CLIENT)
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void addInformation(final ItemStack stack, final EntityPlayer player, final List lines,
        final boolean advanced) {
        for (final String key : new String[] { "description", "cells", "binding", "configure" }) {
            lines.add(StatCollector.translateToLocal("gtnhmelogisticsenhancement.drive." + key));
        }
    }
}
