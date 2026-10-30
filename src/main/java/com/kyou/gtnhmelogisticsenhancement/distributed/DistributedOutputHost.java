package com.kyou.gtnhmelogisticsenhancement.distributed;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import appeng.api.implementations.IPowerChannelState;
import appeng.api.networking.security.IActionHost;
import appeng.me.helpers.IGridProxyable;
import gregtech.api.interfaces.IMEConnectable;
import gregtech.api.metatileentity.MetaTileEntity;

/** Common facade; actual GT inheritance remains item-bus or fluid-hatch specific. */
public interface DistributedOutputHost<K> extends IGridProxyable, IActionHost, IPowerChannelState, IMEConnectable {

    MetaTileEntity machine();

    DistributedOutputState<K> state();

    default String diagnostic() {
        return this.state()
            .fault();
    }

    boolean canConfigure(EntityPlayer player);

    // Keep this facade distinct from IInventory methods, which are renamed during reobfuscation.
    boolean acceptsCapacityCard(ItemStack card);

    void configurePartitions(int value);

    void configurePeriod(int value);
}
