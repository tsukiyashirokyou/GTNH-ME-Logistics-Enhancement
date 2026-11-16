package com.kyou.gtnhmelogisticsenhancement.energy;

import java.util.List;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import appeng.api.networking.IGridNode;
import appeng.api.util.AECableType;
import appeng.api.util.DimensionalCoord;
import appeng.me.helpers.AENetworkProxy;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchDynamo;
import mcp.mobius.waila.api.IWailaConfigHandler;
import mcp.mobius.waila.api.IWailaDataAccessor;

/** Native MTEHatchDynamo identity with one native EU stock and immutable ME capability. */
@IMetaTileEntity.SkipGenerateDescription
@IMetaTileEntity.SkipGenerateName
public final class MTEHatchMEDynamoStandard extends MTEHatchDynamo implements IMEEnergyEndpoint {

    private final MEEnergyEndpointState energyState;
    private final MEEnergyNodeSupport nodeSupport;

    public MTEHatchMEDynamoStandard(final int id, final String name, final String regional, final MEEnergySpec spec) {
        super(
            id,
            name,
            regional,
            MEEnergyEndpointState.tier(spec, MEEnergySpec.Role.DYNAMO, MEEnergySpec.Family.STANDARD));
        this.energyState = new MEEnergyEndpointState(spec);
        this.nodeSupport = new MEEnergyNodeSupport(this);
    }

    public MTEHatchMEDynamoStandard(final String name, final MEEnergySpec spec, final String[] description,
        final ITexture[][][] textures) {
        super(
            name,
            MEEnergyEndpointState.tier(spec, MEEnergySpec.Role.DYNAMO, MEEnergySpec.Family.STANDARD),
            description,
            textures);
        this.energyState = new MEEnergyEndpointState(spec);
        this.nodeSupport = new MEEnergyNodeSupport(this);
    }

    @Override
    public MetaTileEntity newMetaEntity(final IGregTechTileEntity base) {
        return new MTEHatchMEDynamoStandard(this.mName, spec(), this.mDescriptionArray, this.mTextures);
    }

    @Override
    public MetaTileEntity machine() {
        return this;
    }

    @Override
    public MEEnergyEndpointState energyState() {
        return this.energyState;
    }

    @Override
    public MEEnergyNodeSupport nodeSupport() {
        return this.nodeSupport;
    }

    @Override
    public AENetworkProxy getProxy() {
        return this.nodeSupport.proxy();
    }

    @Override
    public IGridNode getGridNode(final ForgeDirection side) {
        return this.nodeSupport.node(side);
    }

    @Override
    public IGridNode getActionableNode() {
        return this.nodeSupport.actionableNode();
    }

    @Override
    public DimensionalCoord getLocation() {
        return this.nodeSupport.location();
    }

    @Override
    public AECableType getCableConnectionType(final ForgeDirection side) {
        return this.nodeSupport.cableType(side);
    }

    @Override
    public void gridChanged() {
        this.nodeSupport.statusChanged();
    }

    @Override
    public boolean isPowered() {
        return this.nodeSupport.isPowered();
    }

    @Override
    public boolean isActive() {
        return this.nodeSupport.isActive();
    }

    @Override
    public boolean isBooting() {
        return this.nodeSupport.isBooting();
    }

    @Override
    public void securityBreak() {
        this.nodeSupport.securityBreak();
    }

    @Override
    public boolean connectsToAllSides() {
        return this.nodeSupport.connectsToAllSides();
    }

    @Override
    public void setConnectsToAllSides(final boolean value) {
        this.nodeSupport.setConnectsToAllSides(value);
    }

    @Override
    public boolean onWireCutterRightClick(final ForgeDirection side, final ForgeDirection wrenchingSide,
        final EntityPlayer player, final float x, final float y, final float z,
        final net.minecraft.item.ItemStack tool) {
        if (!this.nodeSupport.canConfigure(player)) return false;
        this.nodeSupport.setConnectsToAllSides(!connectsToAllSides());
        return true;
    }

    @Override
    public void onFacingChange() {
        this.nodeSupport.facingChanged();
    }

    @Override
    public long maxEUStore() {
        return spec().capacity();
    }

    @Override
    public long maxEUOutput() {
        return spec().voltage();
    }

    @Override
    public long maxAmperesOut() {
        return spec().amperage();
    }

    @Override
    public long getMinimumStoredEU() {
        return spec().nativeReserve();
    }

    @Override
    public boolean isElectric() {
        return true;
    }

    @Override
    public boolean isEnetInput() {
        return false;
    }

    @Override
    public boolean isEnetOutput() {
        return false;
    }

    @Override
    public boolean isInputFacing(final ForgeDirection side) {
        return false;
    }

    @Override
    public boolean isOutputFacing(final ForgeDirection side) {
        return false;
    }

    @Override
    public int getCapacity() {
        return 0;
    }

    @Override
    public String[] getDescription() {
        return MEEnergyPresentation.description(spec());
    }

    @Override
    public String getLocalName() {
        return MEEnergyPresentation.name(this);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerIcons(final IIconRegister iconRegister) {
        super.registerIcons(iconRegister);
        MEEnergyPresentation.registerIcons();
    }

    @Override
    public ITexture[] getTexture(final IGregTechTileEntity base, final ForgeDirection side, final ForgeDirection facing,
        final int color, final boolean active, final boolean redstone) {
        return MEEnergyPresentation.withColor(super.getTexture(base, side, facing, color, active, redstone), color);
    }

    @Override
    public void getWailaNBTData(final EntityPlayerMP player, final TileEntity tile, final NBTTagCompound tag,
        final World world, final int x, final int y, final int z) {
        super.getWailaNBTData(player, tile, tag, world, x, y, z);
        MEEnergyWaila.write(this, tag);
    }

    @Override
    public void getWailaBody(final ItemStack item, final List<String> tooltip, final IWailaDataAccessor accessor,
        final IWailaConfigHandler config) {
        super.getWailaBody(item, tooltip, accessor, config);
        MEEnergyWaila.body(accessor.getNBTData(), tooltip);
    }

    @Override
    public ITexture[] getTexturesActive(final ITexture base) {
        return MEEnergyPresentation.textures(base, spec(), true);
    }

    @Override
    public ITexture[] getTexturesInactive(final ITexture base) {
        return MEEnergyPresentation.textures(base, spec(), false);
    }

    @Override
    protected boolean forceUseMui2() {
        return true;
    }

    @Override
    public com.cleanroommc.modularui.screen.ModularPanel buildUI(
        final com.cleanroommc.modularui.factory.PosGuiData data,
        final com.cleanroommc.modularui.value.sync.PanelSyncManager sync,
        final com.cleanroommc.modularui.screen.UISettings settings) {
        return MEEnergyGui.build(this, sync, settings);
    }

    @Override
    public boolean onRightclick(final IGregTechTileEntity base, final EntityPlayer player) {
        if (base == null || player == null) return false;
        if (base.isServerSide() && this.nodeSupport.canConfigure(player)) openGui(player);
        return true;
    }

    @Override
    public boolean onRightclick(final IGregTechTileEntity base, final EntityPlayer player, final ForgeDirection side,
        final float x, final float y, final float z) {
        return onRightclick(base, player);
    }

    @Override
    public void onFirstTick(final IGregTechTileEntity base) {
        super.onFirstTick(base);
        if (base.isServerSide()) {
            this.energyState.start();
            this.nodeSupport.onReady();
        }
    }

    @Override
    public void onColorChangeServer(final byte color) {
        // No physical EU/laser/data connections: do not scan six neighbors or rebuild their pipe networks.
        this.energyState.configurationChanged();
        this.nodeSupport.configurationChanged();
    }

    @Override
    public void onUnload() {
        this.nodeSupport.stop(true);
        super.onUnload();
    }

    @Override
    public void onRemoval() {
        this.nodeSupport.stop(false);
        super.onRemoval();
    }

    @Override
    public void onBlockDestroyed() {
        this.nodeSupport.destroyed();
        super.onBlockDestroyed();
    }

    @Override
    public void saveNBTData(final NBTTagCompound tag) {
        super.saveNBTData(tag);
        MEEnergyNbt.writeWorld(tag, this.energyState);
        this.nodeSupport.writeWorld(tag);
    }

    @Override
    public void loadNBTData(final NBTTagCompound tag) {
        super.loadNBTData(tag);
        MEEnergyNbt.readWorld(tag, this.energyState);
        this.nodeSupport.readWorld(tag);
    }

    @Override
    public void setItemNBT(final NBTTagCompound tag) {
        super.setItemNBT(tag);
        MEEnergyNbt.stripItemEnergy(tag);
        this.nodeSupport.writeItem(tag);
    }
}
