package com.kyou.gtnhmelogisticsenhancement.energy;

import java.util.EnumSet;
import java.util.Objects;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;

import com.mojang.authlib.GameProfile;

import appeng.api.config.SecurityPermissions;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridNode;
import appeng.api.util.AECableType;
import appeng.api.util.AEColor;
import appeng.api.util.DimensionalCoord;
import appeng.core.worlddata.WorldData;
import appeng.me.GridAccessException;
import appeng.me.helpers.AENetworkProxy;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** One lazy AE node per native hatch; all lifecycle methods run on the server thread. Never stores EU. */
public final class MEEnergyNodeSupport {

    static final String NODE_DATA = "meEnergyAE";
    static final String ALL_SIDES = "meEnergyAllSides";
    private final IMEEnergyEndpoint host;
    private AENetworkProxy proxy;
    private NBTBase savedNode;
    private NBTBase invalidSides;
    private String diagnostic = "";
    private MEEnergyGridCache.Member member;
    private boolean allSides;
    private boolean ready;
    private boolean destroyed;
    private boolean stopping;

    public MEEnergyNodeSupport(final IMEEnergyEndpoint host) {
        this.host = Objects.requireNonNull(host, "host");
        MEEnergyNetwork.track(this);
    }

    public AENetworkProxy proxy() {
        if (this.proxy == null) {
            this.proxy = new EnergyProxy();
            this.proxy.setFlags(GridFlags.REQUIRE_CHANNEL);
            this.proxy.setIdlePowerUsage(1);
            updateSides();
            if (this.savedNode instanceof NBTTagCompound && this.diagnostic.isEmpty()) {
                final NBTTagCompound data = new NBTTagCompound();
                data.setTag(NODE_DATA, this.savedNode.copy());
                this.proxy.readFromNBT(data);
            }
        }
        return this.proxy;
    }

    /** Does not create or ready a proxy/node; safe during AE add/remove callbacks and after unload. */
    IGridNode peekNode() {
        return this.proxy == null ? null : this.proxy.getNode();
    }

    public void onReady() {
        final IGregTechTileEntity base = this.host.machine()
            .getBaseMetaTileEntity();
        if (base == null || base.getWorld() == null
            || !base.isServerSide()
            || !this.host.isEndpointValid()
            || this.destroyed
            || !this.diagnostic.isEmpty()
            || MEEnergyNetwork.isStopping()) return;
        MEEnergyNetwork.track(this);
        this.ready = true;
        proxy().onReady();
        statusChanged();
    }

    void bind(final MEEnergyGridCache.Member value) {
        if (this.member == value) return;
        if (this.member != null) this.member.cache.detach(this.member);
        this.member = value;
        resetBudget();
        this.host.energyState()
            .configurationChanged();
    }

    void unbind(final MEEnergyGridCache.Member value) {
        if (this.member != value) return;
        this.member = null;
        final IGregTechTileEntity base = this.host.machine()
            .getBaseMetaTileEntity();
        if (base != null && base.isActive()) base.setActive(false);
        resetBudget();
        this.host.energyState()
            .configurationChanged();
    }

    private void resetBudget() {
        if (this.host.spec()
            .role() == MEEnergySpec.Role.DYNAMO) {
            this.host.energyState()
                .rateBudget()
                .reset(MEEnergyTickClock.now());
        }
    }

    boolean liveIn(final MEEnergyGridCache cache, final IGridNode node) {
        return channelEligibleIn(cache, node) && node.isActive();
    }

    boolean channelEligibleIn(final MEEnergyGridCache cache, final IGridNode node) {
        return this.ready && !this.destroyed
            && !this.stopping
            && this.diagnostic.isEmpty()
            && this.host.isEndpointValid()
            && this.member != null
            && this.member.cache == cache
            && this.member.node == node
            && peekNode() == node
            && node.getGrid() == cache.grid()
            && node.meetsChannelRequirements();
    }

    /** Sleeping full targets check only their own native field, never AE, and wake at most once. */
    void pollDemandWake(final long tick) {
        final MEEnergyGridCache.Member current = this.member;
        if (current != null && current.dormant && ((tick + current.probePhase) & 3) == 0) {
            current.cache.noteLocalDemandProbe();
            if (this.host.readEU() < this.host.spec()
                .capacity()) current.cache.wakeTarget(current);
        }
    }

    public boolean isActive() {
        return this.member != null && liveIn(this.member.cache, this.member.node);
    }

    public boolean isPowered() {
        return this.ready && !this.destroyed && this.proxy != null && this.proxy.isPowered();
    }

    public boolean isBooting() {
        if (!this.ready || this.destroyed || this.proxy == null) return false;
        try {
            return this.proxy.getPath()
                .isNetworkBooting();
        } catch (GridAccessException offline) {
            return false;
        }
    }

    public void statusChanged() {
        if (this.member != null) this.member.cache.statusChanged(this.member);
    }

    /** View-only diagnostics; no inventory query or Grid traversal. */
    public long groupServiceAge() {
        if (this.member == null) return -1;
        final MEEnergyGroupState group = this.member.cache.group(
            this.host.spec()
                .tier());
        return group == null || group.lastServiceTick() < 0 ? -1 : MEEnergyTickClock.now() - group.lastServiceTick();
    }

    public long expiredRounds() {
        if (this.member == null) return 0;
        final MEEnergyGroupState group = this.member.cache.group(
            this.host.spec()
                .tier());
        return group == null ? 0 : group.expirations();
    }

    public void configurationChanged() {
        if (this.member != null) this.member.cache.configurationChanged(this.member);
    }

    public boolean connectsToAllSides() {
        return this.allSides;
    }

    public void setConnectsToAllSides(final boolean value) {
        final IGregTechTileEntity base = this.host.machine()
            .getBaseMetaTileEntity();
        if (base == null || !base.isServerSide()
            || !this.host.isEndpointValid()
            || this.destroyed
            || !this.diagnostic.isEmpty()
            || this.allSides == value) return;
        this.allSides = value;
        this.host.energyState()
            .configurationChanged();
        configurationChanged();
        updateSides();
        this.host.machine()
            .markDirty();
    }

    public void facingChanged() {
        this.host.energyState()
            .configurationChanged();
        configurationChanged();
        updateSides();
    }

    private void updateSides() {
        if (this.proxy == null) return;
        final IGregTechTileEntity base = this.host.machine()
            .getBaseMetaTileEntity();
        final EnumSet<ForgeDirection> sides = base == null ? EnumSet.noneOf(ForgeDirection.class)
            : this.allSides ? EnumSet.range(ForgeDirection.DOWN, ForgeDirection.EAST)
                : base.getFrontFacing() == ForgeDirection.UNKNOWN ? EnumSet.noneOf(ForgeDirection.class)
                    : EnumSet.of(base.getFrontFacing());
        if (!this.proxy.getConnectableSides()
            .equals(sides)) this.proxy.setValidSides(sides);
    }

    public IGridNode node(final ForgeDirection side) {
        return side != null && side != ForgeDirection.UNKNOWN && (this.allSides || facing(side)) ? peekNode() : null;
    }

    private boolean facing(final ForgeDirection side) {
        final IGregTechTileEntity base = this.host.machine()
            .getBaseMetaTileEntity();
        return base != null && side == base.getFrontFacing();
    }

    public IGridNode actionableNode() {
        return peekNode();
    }

    public AECableType cableType(final ForgeDirection side) {
        return !this.destroyed && side != null && side != ForgeDirection.UNKNOWN && (this.allSides || facing(side))
            ? AECableType.SMART
            : AECableType.NONE;
    }

    public DimensionalCoord location() {
        final IGregTechTileEntity base = this.host.machine()
            .getBaseMetaTileEntity();
        if (base == null) throw new IllegalStateException("Endpoint is detached");
        return new DimensionalCoord(base.getWorld(), base.getXCoord(), base.getYCoord(), base.getZCoord());
    }

    public boolean canConfigure(final EntityPlayer player) {
        final IGregTechTileEntity base = this.host.machine()
            .getBaseMetaTileEntity();
        if (player == null || base == null
            || !base.isServerSide()
            || !this.host.isEndpointValid()
            || !this.diagnostic.isEmpty()
            || this.destroyed
            || player.worldObj != base.getWorld()
            || player.getDistanceSq(base.getXCoord() + 0.5, base.getYCoord() + 0.5, base.getZCoord() + 0.5) > 64
            || !base.isUseableByPlayer(player)) return false;
        if (!isActive()) return player.getUniqueID()
            .equals(base.getOwnerUuid());
        try {
            return this.proxy.getSecurity()
                .hasPermission(player, SecurityPermissions.BUILD);
        } catch (GridAccessException unavailableSecurity) {
            return false;
        }
    }

    public void securityBreak() {
        if (this.destroyed) return;
        this.destroyed = true;
        stop(false);
        final IGregTechTileEntity base = this.host.machine()
            .getBaseMetaTileEntity();
        if (base != null && base.isServerSide()
            && base.getMetaTileEntity() == this.host.machine()
            && !base.isDead()
            && base.getWorld() != null) {
            base.getWorld()
                .func_147480_a(base.getXCoord(), base.getYCoord(), base.getZCoord(), true);
        }
    }

    public void destroyed() {
        this.destroyed = true;
        stop(false);
    }

    /** Drop all transient references before native invalidation can synchronously split/join a Grid. */
    public void stop(final boolean unload) {
        if (this.stopping) return;
        this.stopping = true;
        try {
            this.ready = false;
            preserveNodeData();
            if (this.member != null) this.member.cache.detach(this.member);
            final AENetworkProxy previous = this.proxy;
            this.proxy = null;
            this.host.energyState()
                .stop();
            // AE may already have discarded WorldData when our ServerStopped callback runs.
            // Its own shutdown owns those obsolete nodes; do not rebuild their graphs after teardown.
            if (previous != null && WorldData.instance() != null) {
                if (unload) previous.onChunkUnload();
                else previous.invalidate();
            }
        } finally {
            this.stopping = false;
        }
    }

    private void preserveNodeData() {
        if (this.proxy == null || !this.diagnostic.isEmpty()) return;
        final NBTTagCompound data = new NBTTagCompound();
        this.proxy.writeToNBT(data);
        if (data.hasKey(NODE_DATA, 10)) this.savedNode = data.getTag(NODE_DATA)
            .copy();
    }

    public void writeWorld(final NBTTagCompound root) {
        preserveNodeData();
        if (this.savedNode != null) root.setTag(NODE_DATA, this.savedNode.copy());
        if (this.invalidSides != null) root.setTag(ALL_SIDES, this.invalidSides.copy());
        else root.setBoolean(ALL_SIDES, this.allSides);
    }

    public void readWorld(final NBTTagCompound root) {
        stop(true);
        this.destroyed = false;
        this.diagnostic = "";
        this.savedNode = root.hasKey(NODE_DATA) ? root.getTag(NODE_DATA)
            .copy() : null;
        this.invalidSides = null;
        this.allSides = root.getBoolean(ALL_SIDES);
        if (root.hasKey(ALL_SIDES)
            && (!root.hasKey(ALL_SIDES, 1) || root.getByte(ALL_SIDES) < 0 || root.getByte(ALL_SIDES) > 1)) {
            this.invalidSides = root.getTag(ALL_SIDES)
                .copy();
            this.diagnostic = "INVALID_ME_ENERGY_SIDES";
            this.allSides = false;
        }
        if (this.savedNode != null && !validNode(this.savedNode)) this.diagnostic = "INVALID_ME_ENERGY_AE_NODE";
    }

    private static boolean validNode(final NBTBase data) {
        if (!(data instanceof NBTTagCompound)) return false;
        final NBTTagCompound node = (NBTTagCompound) data;
        return node.hasKey("p", 3) && node.hasKey("k", 4) && node.hasKey("g", 4);
    }

    public void writeItem(final NBTTagCompound root) {
        root.removeTag(NODE_DATA);
        root.setBoolean(ALL_SIDES, this.allSides);
    }

    public String diagnostic() {
        return this.diagnostic;
    }

    private final class EnergyProxy extends AENetworkProxy {

        EnergyProxy() {
            super(
                MEEnergyNodeSupport.this.host,
                NODE_DATA,
                MEEnergyNodeSupport.this.host.machine()
                    .getStackForm(1),
                true);
        }

        @Override
        public void setColor(final AEColor color) {
            if (color != AEColor.Transparent)
                throw new IllegalArgumentException("EU paint must not recolor the AE node");
            super.setColor(AEColor.Transparent);
        }

        @Override
        public void readFromNBT(final NBTTagCompound data) {
            super.readFromNBT(data);
            // Native proxy invokes this after node creation and before its first updateState/Grid join.
            final IGregTechTileEntity base = MEEnergyNodeSupport.this.host.machine()
                .getBaseMetaTileEntity();
            if (MEEnergyNodeSupport.this.savedNode == null && base != null
                && base.isServerSide()
                && base.getOwnerUuid() != null
                && getNode() != null) {
                getNode().setPlayerID(
                    WorldData.instance()
                        .playerData()
                        .getPlayerID(new GameProfile(base.getOwnerUuid(), base.getOwnerName())));
            }
        }
    }
}
