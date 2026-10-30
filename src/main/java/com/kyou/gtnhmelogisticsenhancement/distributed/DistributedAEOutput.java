package com.kyou.gtnhmelogisticsenhancement.distributed;

import java.util.function.BooleanSupplier;

import net.minecraft.nbt.NBTTagCompound;

import com.mojang.authlib.GameProfile;

import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEStackType;
import appeng.core.worlddata.WorldData;
import appeng.me.GridAccessException;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;

/** Shared native AE node creation and powered insertion. GT lifecycle/security remain on each host. */
final class DistributedAEOutput {

    private DistributedAEOutput() {}

    static AENetworkProxy createProxy(final IGridProxyable host, final MetaTileEntity machine,
        final BooleanSupplier saved) {
        final AENetworkProxy proxy = new AENetworkProxy(host, "distributedAE", machine.getStackForm(1), true) {

            @Override
            public void readFromNBT(final NBTTagCompound tag) {
                super.readFromNBT(tag);
                // AE invokes this hook after creating the node, before the first node.updateState()/grid join.
                final IGregTechTileEntity base = machine.getBaseMetaTileEntity();
                if (!saved.getAsBoolean() && base != null
                    && base.isServerSide()
                    && base.getOwnerUuid() != null
                    && this.getNode() != null)
                    this.getNode()
                        .setPlayerID(
                            WorldData.instance()
                                .playerData()
                                .getPlayerID(new GameProfile(base.getOwnerUuid(), base.getOwnerName())));
            }
        };
        proxy.setFlags(GridFlags.REQUIRE_CHANNEL);
        proxy.setIdlePowerUsage(1);
        return proxy;
    }

    static <T extends IAEStack<T>> T insert(final AENetworkProxy proxy, final T offered, final IAEStackType<T> type,
        final BaseActionSource source) {
        if (!proxy.isActive()) return offered;
        try {
            // Resolve before each identity, allowing synchronous callbacks to split/merge the Grid.
            final IGrid grid = proxy.getGrid();
            final appeng.api.networking.storage.IStorageGrid storage = proxy.getStorage();
            final IEnergyGrid energy = proxy.getEnergy();
            final IMEMonitor<?> raw = storage.getMEMonitor(type);
            if (raw == null || raw.getStackType() != type)
                throw new IllegalStateException("Unexpected AE monitor type");
            // AE's wildcard API is narrowed only after checking its registered storage type.
            @SuppressWarnings("unchecked")
            final IMEMonitor<T> monitor = (IMEMonitor<T>) raw;
            return DistributedOutputEnergy.insert(grid, energy, () -> {
                try {
                    return proxy.isActive() && proxy.getGrid() == grid
                        && proxy.getEnergy() == energy
                        && proxy.getStorage() == storage;
                } catch (GridAccessException offline) {
                    return false;
                }
            }, monitor, offered, type, source);
        } catch (GridAccessException offline) {
            return offered; // No injection has started.
        }
    }
}
