package com.kyou.gtnhmelogisticsenhancement.item;

import java.util.List;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import com.kyou.gtnhmelogisticsenhancement.GTNHMELogisticsEnhancementMod;
import com.kyou.gtnhmelogisticsenhancement.energy.MEEnergyMonitorGui;

import appeng.tile.networking.TileController;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** A read-only controller instrument. It neither owns an AE node nor stores EU. */
public final class ItemMEEnergyMonitor extends Item {

    public ItemMEEnergyMonitor() {
        setUnlocalizedName("meEnergyMonitor");
        setTextureName(GTNHMELogisticsEnhancementMod.MODID + ":me_energy_monitor");
        setCreativeTab(CreativeTabs.tabTools);
        setMaxStackSize(1);
    }

    @Override
    public boolean onItemUseFirst(final ItemStack stack, final EntityPlayer player, final World world, final int x,
        final int y, final int z, final int side, final float hitX, final float hitY, final float hitZ) {
        // The client must fall through so PlayerControllerMP sends the normal C08 right-click packet.
        return !world.isRemote && onItemUse(stack, player, world, x, y, z, side, hitX, hitY, hitZ);
    }

    @Override
    public boolean onItemUse(final ItemStack stack, final EntityPlayer player, final World world, final int x,
        final int y, final int z, final int side, final float hitX, final float hitY, final float hitZ) {
        if (!world.getChunkProvider()
            .chunkExists(x >> 4, z >> 4) || !(world.getTileEntity(x, y, z) instanceof TileController)) return false;
        if (!world.isRemote && !MEEnergyMonitorGui.open(player, x, y, z))
            player.addChatMessage(new ChatComponentTranslation("gtnhmelogisticsenhancement.monitor.denied"));
        return true;
    }

    @Override
    @SideOnly(Side.CLIENT)
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void addInformation(final ItemStack stack, final EntityPlayer player, final List lines,
        final boolean advanced) {
        lines.add(StatCollector.translateToLocal("gtnhmelogisticsenhancement.monitor.hint"));
        lines.add(StatCollector.translateToLocal("gtnhmelogisticsenhancement.monitor.note"));
    }
}
