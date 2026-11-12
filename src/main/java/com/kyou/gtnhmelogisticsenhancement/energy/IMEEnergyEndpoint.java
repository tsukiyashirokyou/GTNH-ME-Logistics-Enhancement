package com.kyou.gtnhmelogisticsenhancement.energy;

import appeng.api.implementations.IPowerChannelState;
import appeng.api.networking.events.MENetworkChannelsChanged;
import appeng.api.networking.events.MENetworkEventSubscribe;
import appeng.api.networking.security.IActionHost;
import appeng.me.helpers.IGridProxyable;
import gregtech.api.interfaces.IMEConnectable;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;

/** Main-thread GT/AE facade. Local stock remains readable independently of network availability. */
public interface IMEEnergyEndpoint extends IGridProxyable, IActionHost, IPowerChannelState, IMEConnectable {

    MetaTileEntity machine();

    MEEnergyEndpointState energyState();

    MEEnergyNodeSupport nodeSupport();

    @MENetworkEventSubscribe
    default void meChannelsChanged(final MENetworkChannelsChanged event) {
        nodeSupport().statusChanged();
    }

    default MEEnergySpec spec() {
        return energyState().spec();
    }

    /** GT paint -1 maps to isolated default group 0; GT paint 0..15 maps to business groups 1..16. */
    default int businessColor() {
        final IGregTechTileEntity base = machine().getBaseMetaTileEntity();
        if (base == null) throw new IllegalStateException("Endpoint is detached");
        return base.getColorization() + 1;
    }

    default boolean isEndpointValid() {
        final MetaTileEntity machine = machine();
        final IGregTechTileEntity base = machine.getBaseMetaTileEntity();
        return base != null && base.getMetaTileEntity() == machine
            && !base.isDead()
            && !energyState().isStopped()
            && energyState().diagnostic()
                .isEmpty();
    }

    /** Actual native stock, even when the network or endpoint is offline; never a capped/hidden view. */
    default long readEU() {
        final MetaTileEntity machine = machine();
        final IGregTechTileEntity base = machine.getBaseMetaTileEntity();
        if (base == null || base.getMetaTileEntity() != machine) {
            throw new IllegalStateException("Endpoint is detached or replaced");
        }
        return machine.getEUVar();
    }

    /**
     * Native setter bridge, not a transfer operation. The transfer layer must validate both ends first.
     * A source may retain legitimate stock above C; only target admission is capacity-limited there.
     */
    default void writeEU(final long value) {
        if (value < 0) throw new IllegalArgumentException("Negative EU stock");
        if (!isEndpointValid()) throw new IllegalStateException("Endpoint is not available for ME writes");
        machine().setEUVar(value);
    }
}
