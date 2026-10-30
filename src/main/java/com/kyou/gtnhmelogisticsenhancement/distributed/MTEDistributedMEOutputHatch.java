package com.kyou.gtnhmelogisticsenhancement.distributed;

import static appeng.util.item.AEFluidStackType.FLUID_STACK_TYPE;

import java.util.EnumSet;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;

import com.cleanroommc.modularui.factory.PosGuiData;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;

import appeng.api.config.SecurityPermissions;
import appeng.api.networking.IGridNode;
import appeng.api.networking.events.MENetworkChannelsChanged;
import appeng.api.networking.events.MENetworkEventSubscribe;
import appeng.api.networking.events.MENetworkPowerStatusChange;
import appeng.api.networking.security.MachineSource;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.util.AECableType;
import appeng.api.util.AEColor;
import appeng.api.util.DimensionalCoord;
import appeng.me.GridAccessException;
import appeng.me.helpers.AENetworkProxy;
import gregtech.api.enums.Dyes;
import gregtech.api.enums.OutputHatchType;
import gregtech.api.enums.Textures;
import gregtech.api.interfaces.IOutputHatch;
import gregtech.api.interfaces.IOutputHatchTransaction;
import gregtech.api.interfaces.IOutputTransaction;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchOutput;
import gregtech.api.render.TextureFactory;
import gregtech.api.util.GTUtility.FluidId;

/** A standard GT output hatch whose sole product inventory is the shared partition store. */
@gregtech.api.interfaces.metatileentity.IMetaTileEntity.SkipGenerateDescription
@gregtech.api.interfaces.metatileentity.IMetaTileEntity.SkipGenerateName
public class MTEDistributedMEOutputHatch extends MTEHatchOutput implements DistributedOutputHost<FluidId> {

    private static final String DATA = "distributedFluidHatch";
    private DistributedFluidHatchState state = new DistributedFluidHatchState();
    private AENetworkProxy proxy;
    private NBTTagCompound networkData;
    private NBTBase cardEvidence;
    private NBTBase legacyTankEvidence;
    private NBTBase nodeEvidence;
    private boolean allSides;
    private boolean savedProxy;
    private boolean ready;
    private boolean policyReady;
    private boolean destroyed;
    private boolean fluidNbtBlocked;
    private final MachineSource source = new MachineSource(this);

    public MTEDistributedMEOutputHatch(final int id, final String name, final String regional) {
        super(id, name, regional, 4, null, 1);
    }

    public MTEDistributedMEOutputHatch(final String name, final int tier, final String[] description,
        final ITexture[][][] textures) {
        super(name, tier, 1, description, textures);
    }

    @Override
    public MetaTileEntity newMetaEntity(final IGregTechTileEntity base) {
        return new MTEDistributedMEOutputHatch(this.mName, this.mTier, this.mDescriptionArray, this.mTextures);
    }

    public DistributedFluidHatchState state() {
        return this.state;
    }

    @Override
    public String diagnostic() {
        final String fault = this.state.fault();
        return fault.isEmpty() && this.fluidNbtBlocked ? "UNSUPPORTED_FLUID_NBT" : fault;
    }

    @Override
    public ModularPanel buildUI(final PosGuiData data, final PanelSyncManager sync, final UISettings settings) {
        return DistributedOutputGui.build(
            this,
            data,
            sync,
            settings,
            true,
            identity -> DistributedOutputGui.DisplayIcon.fluid(identity.getFluidStack()));
    }

    @Override
    protected boolean forceUseMui2() {
        return true;
    }

    @Override
    public MetaTileEntity machine() {
        return this;
    }

    @Override
    public AENetworkProxy getProxy() {
        if (this.proxy == null) {
            this.proxy = DistributedAEOutput.createProxy(this, this, () -> this.savedProxy);
            this.updateSides();
            if (this.networkData != null) this.proxy.readFromNBT(this.networkData);
        }
        return this.proxy;
    }

    private void updateSides() {
        final IGregTechTileEntity base = this.getBaseMetaTileEntity();
        if (this.proxy != null) this.proxy.setValidSides(
            base == null ? EnumSet.noneOf(ForgeDirection.class)
                : this.allSides ? EnumSet.range(ForgeDirection.DOWN, ForgeDirection.EAST)
                    : EnumSet.of(base.getFrontFacing()));
    }

    @Override
    public void onFacingChange() {
        this.updateSides();
    }

    @Override
    public void onColorChangeServer(final byte color) {
        this.getProxy()
            .setColor(color < 0 ? AEColor.Transparent : AEColor.values()[Dyes.transformDyeIndex(color)]);
        final IGridNode node = this.proxy.getNode();
        if (node != null) node.updateState();
    }

    @Override
    public String getLocalName() {
        return StatCollector.translateToLocal("gtnhmelogisticsenhancement.hatch.name");
    }

    @Override
    public String[] getDescription() {
        return new String[] { StatCollector.translateToLocal("gtnhmelogisticsenhancement.hatch.description"),
            StatCollector.translateToLocal("gtnhmelogisticsenhancement.hatch.card_note"),
            StatCollector.translateToLocal("gtnhmelogisticsenhancement.hatch.emergency_note") };
    }

    @Override
    public ITexture[] getTexturesActive(final ITexture base) {
        return new ITexture[] { base, TextureFactory.of(Textures.BlockIcons.OVERLAY_ME_HATCH_ACTIVE),
            TextureFactory.of(DistributedBusRegistration.FLUID_OVERLAY) };
    }

    @Override
    public ITexture[] getTexturesInactive(final ITexture base) {
        return new ITexture[] { base, TextureFactory.of(Textures.BlockIcons.OVERLAY_ME_HATCH),
            TextureFactory.of(DistributedBusRegistration.FLUID_OVERLAY) };
    }

    @Override
    public void onFirstTick(final IGregTechTileEntity base) {
        super.onFirstTick(base);
        if (base.isServerSide() && !this.destroyed) {
            if (!this.policyReady) this.state.updateCard(this.mInventory[0]);
            this.policyReady = true;
            this.getProxy()
                .onReady();
            if (this.proxy.getNode() != null) this.networkData = null;
            this.onColorChangeServer(base.getColorization());
            this.updateSides();
            this.ready = true;
            this.updateActive();
        }
    }

    @Override
    public void onPostTick(final IGregTechTileEntity base, final long tick) {
        // No super: the parent ejects mFluid to neighbours and polls single-tank dirtiness.
        if (!base.isServerSide() || !this.ready
            || this.destroyed
            || !this.state.tick()
            || !this.getProxy()
                .isActive())
            return;
        this.fluidNbtBlocked = false;
        final boolean changed = this.state.flush((identity, amount) -> {
            final IAEFluidStack offered = DistributedFluidBridge.offer(identity, amount);
            if (offered == null) {
                this.fluidNbtBlocked = true;
                return new DistributedOutputState.Remainder<>(identity, amount);
            }
            final IAEFluidStack remaining = DistributedAEOutput
                .insert(this.proxy, offered, FLUID_STACK_TYPE, this.source);
            return remaining == null ? null
                : new DistributedOutputState.Remainder<>(
                    FluidId.create(remaining.getFluidStack()),
                    remaining.getStackSize());
        }, this::markDirty);
        if (changed) this.notifyWatchers();
    }

    private void updateActive() {
        final IGregTechTileEntity base = this.getBaseMetaTileEntity();
        if (this.ready && base != null && base.isServerSide() && !this.destroyed) base.setActive(this.isActive());
    }

    @MENetworkEventSubscribe
    public void channelsChanged(final MENetworkChannelsChanged event) {
        this.updateActive();
    }

    @MENetworkEventSubscribe
    public void powerChanged(final MENetworkPowerStatusChange event) {
        this.updateActive();
    }

    @Override
    public void gridChanged() {
        this.updateActive();
    }

    @Override
    public boolean isPowered() {
        return !this.destroyed && this.getProxy()
            .isPowered();
    }

    @Override
    public boolean isActive() {
        return !this.destroyed && this.getProxy()
            .isActive();
    }

    @Override
    public boolean isBooting() {
        try {
            return !this.destroyed && this.getProxy()
                .getPath()
                .isNetworkBooting();
        } catch (GridAccessException offline) {
            return false;
        }
    }

    @Override
    public IGridNode getGridNode(final ForgeDirection side) {
        return this.getProxy()
            .getNode();
    }

    @Override
    public IGridNode getActionableNode() {
        return this.getProxy()
            .getNode();
    }

    @Override
    public DimensionalCoord getLocation() {
        final IGregTechTileEntity base = this.getBaseMetaTileEntity();
        return new DimensionalCoord(base.getWorld(), base.getXCoord(), base.getYCoord(), base.getZCoord());
    }

    @Override
    public AECableType getCableConnectionType(final ForgeDirection side) {
        return side != ForgeDirection.UNKNOWN && (this.allSides || this.isOutputFacing(side)) ? AECableType.SMART
            : AECableType.NONE;
    }

    @Override
    public void securityBreak() {
        this.state.isolate("AE_SECURITY_BREAK");
        this.getProxy()
            .invalidate();
        this.markDirty();
    }

    @Override
    public boolean onRightclick(final IGregTechTileEntity base, final EntityPlayer player) {
        if (base.isServerSide() && this.canConfigure(player)) this.openGui(player);
        return true;
    }

    @Override
    public boolean onRightclick(final IGregTechTileEntity base, final EntityPlayer player, final ForgeDirection side,
        final float x, final float y, final float z) {
        return this.onRightclick(base, player);
    }

    @Override
    public boolean canConfigure(final EntityPlayer player) {
        final IGregTechTileEntity base = this.getBaseMetaTileEntity();
        if (player == null || base == null
            || this.destroyed
            || player.worldObj != base.getWorld()
            || base.getMetaTileEntity() != this
            || base.isDead()
            || player.getDistanceSq(base.getXCoord() + 0.5, base.getYCoord() + 0.5, base.getZCoord() + 0.5) > 64
            || !base.isUseableByPlayer(player)) return false;
        if (base.isClientSide()) return true;
        final AENetworkProxy current = this.getProxy();
        if (!current.isActive()) return player.getUniqueID()
            .equals(base.getOwnerUuid());
        try {
            return current.getSecurity()
                .hasPermission(player, SecurityPermissions.BUILD);
        } catch (GridAccessException offline) {
            return player.getUniqueID()
                .equals(base.getOwnerUuid());
        }
    }

    @Override
    public boolean onWireCutterRightClick(final ForgeDirection side, final ForgeDirection wrenchingSide,
        final EntityPlayer player, final float x, final float y, final float z, final ItemStack tool) {
        if (!this.getBaseMetaTileEntity()
            .isServerSide() || !this.canConfigure(player) || this.state.locked()) return false;
        this.setConnectsToAllSides(!this.allSides);
        return true;
    }

    @Override
    public boolean connectsToAllSides() {
        return this.allSides;
    }

    @Override
    public void setConnectsToAllSides(final boolean value) {
        if (this.state.locked()) return;
        this.allSides = value;
        this.updateSides();
        this.markDirty();
    }

    @Override
    public boolean isItemValidForSlot(final int slot, final ItemStack card) {
        return slot == 0 && this.acceptsCapacityCard(card);
    }

    @Override
    public boolean acceptsCapacityCard(final ItemStack card) {
        return card != null && !this.state.locked()
            && FluidCellCapacityPolicy.inspect(card)
                .valid();
    }

    @Override
    public void onContentsChanged(final int slot) {
        super.onContentsChanged(slot);
        if (slot == 0 && !this.destroyed) {
            this.state.updateCard(this.mInventory[0]);
            this.policyReady = true;
            this.notifyWatchers();
        }
    }

    @Override
    public void configurePartitions(final int value) {
        this.state.setPartitions(value);
        this.markDirty();
        this.notifyWatchers();
    }

    @Override
    public void configurePeriod(final int value) {
        this.state.setPeriod(value);
        this.markDirty();
    }

    private void preserveNetworkData() {
        if (this.proxy == null) return;
        final NBTTagCompound saved = new NBTTagCompound();
        this.proxy.writeToNBT(saved);
        if (saved.hasKey("distributedAE")) this.networkData = saved;
    }

    @Override
    public void onBlockDestroyed() {
        this.preserveNetworkData();
        this.destroyed = true;
        this.state.stop();
        super.onBlockDestroyed();
    }

    @Override
    public void onRemoval() {
        this.preserveNetworkData();
        this.destroyed = true;
        this.state.stop();
        if (this.proxy != null) this.proxy.invalidate();
        super.onRemoval();
    }

    private void writeHatch(final NBTTagCompound tag) {
        // No parent mFluid serialization: all real output lives in the partition store.
        if (this.mFluid != null) {
            this.legacyTankEvidence = this.mFluid.writeToNBT(new NBTTagCompound());
            this.state.isolate("UNEXPECTED_SINGLE_TANK_DATA");
        }
        tag.setTag(DATA, this.state.write());
        tag.setBoolean("distributedAllSides", this.allSides);
        if (this.cardEvidence != null) tag.setTag("distributedCardEvidence", this.cardEvidence.copy());
        if (this.legacyTankEvidence != null) tag.setTag("mFluid", this.legacyTankEvidence.copy());
        if (this.nodeEvidence != null) tag.setTag("distributedNodeEvidence", this.nodeEvidence.copy());
        if (this.proxy != null) this.proxy.writeToNBT(tag);
        if (!tag.hasKey("distributedAE") && this.networkData != null && this.networkData.hasKey("distributedAE"))
            tag.setTag(
                "distributedAE",
                this.networkData.getTag("distributedAE")
                    .copy());
    }

    @Override
    public void saveNBTData(final NBTTagCompound tag) {
        this.writeHatch(tag);
    }

    @Override
    public void loadNBTData(final NBTTagCompound tag) {
        // GT has restored the one real card slot. Never import a foreign single-tank payload into stock.
        this.mFluid = null;
        this.lockedFluid = null;
        this.mMode = 0;
        this.state = tag.hasKey(DATA) ? DistributedFluidHatchState.read(tag.getTag(DATA), this.mInventory[0])
            : new DistributedFluidHatchState();
        if (!tag.hasKey(DATA)) this.state.updateCard(this.mInventory[0]);
        this.policyReady = true;
        this.legacyTankEvidence = tag.hasKey("mFluid") ? tag.getTag("mFluid")
            .copy() : null;
        if (this.legacyTankEvidence != null) this.state.isolate("UNEXPECTED_SINGLE_TANK_DATA");
        this.cardEvidence = DistributedMachineNbt.cardEvidence(tag, this.mInventory[0]);
        if (this.cardEvidence != null) this.state.isolate("INVALID_CARD_INVENTORY");
        this.nodeEvidence = tag.hasKey("distributedNodeEvidence") ? tag.getTag("distributedNodeEvidence")
            .copy() : null;
        if (tag.hasKey("distributedAE") && !tag.hasKey("distributedAE", 10))
            this.nodeEvidence = tag.getTag("distributedAE")
                .copy();
        if (this.nodeEvidence != null) this.state.isolate("INVALID_AE_NODE");
        this.allSides = tag.getBoolean("distributedAllSides");
        this.savedProxy = tag.hasKey("distributedAE", 10);
        this.networkData = new NBTTagCompound();
        if (this.savedProxy) this.networkData.setTag(
            "distributedAE",
            tag.getTag("distributedAE")
                .copy());
        if (this.proxy != null) this.proxy.readFromNBT(this.networkData);
    }

    @Override
    public void setItemNBT(final NBTTagCompound tag) {
        super.setItemNBT(tag);
        this.writeHatch(tag);
        DistributedMachineNbt.writeCard(tag, this.mInventory[0]);
        this.destroyed = true;
        this.state.stop();
    }

    @Override
    public boolean isFiltered() {
        return false;
    }

    @Override
    public boolean isFilteredToFluid(final FluidId identity) {
        return false;
    }

    @Override
    public OutputHatchType getHatchType() {
        return OutputHatchType.MECacheUnfiltered;
    }

    // Disable every inherited single-tank/container/adjacent-eject entry point. The card is the only real item slot.
    @Override
    public void onPreTick(final IGregTechTileEntity base, final long tick) {}

    @Override
    public boolean doesFillContainers() {
        return false;
    }

    @Override
    public boolean doesEmptyContainers() {
        return false;
    }

    @Override
    public boolean canTankBeFilled() {
        return false;
    }

    @Override
    public boolean canTankBeEmptied() {
        return false;
    }

    @Override
    public boolean isLiquidInput(final ForgeDirection side) {
        return false;
    }

    @Override
    public boolean isLiquidOutput(final ForgeDirection side) {
        return false;
    }

    @Override
    public int getCapacity() {
        return 0;
    }

    @Override
    public boolean isValidSlot(final int slot) {
        return slot == 0;
    }

    @Override
    public int getInventoryStackLimit() {
        return 1;
    }

    @Override
    public boolean shouldDropItemAt(final int slot) {
        return false;
    }

    @Override
    public boolean allowPutStack(final IGregTechTileEntity base, final int slot, final ForgeDirection side,
        final ItemStack stack) {
        return false;
    }

    @Override
    public boolean allowPullStack(final IGregTechTileEntity base, final int slot, final ForgeDirection side,
        final ItemStack stack) {
        return false;
    }

    @Override
    public FluidStack getFillableStack() {
        return null;
    }

    @Override
    public FluidStack getDrainableStack() {
        return null;
    }

    @Override
    public FluidStack getFluid() {
        return null;
    }

    @Override
    public int getFluidAmount() {
        return 0;
    }

    @Override
    public FluidStack setFillableStack(final FluidStack value) {
        if (value != null && value.amount != 0) throw new IllegalStateException("Single tank disabled");
        return null;
    }

    @Override
    public FluidStack setDrainableStack(final FluidStack value) {
        return this.setFillableStack(value);
    }

    @Override
    public FluidStack drain(final int amount, final boolean actual) {
        return null;
    }

    @Override
    public FluidTankInfo[] getTankInfo(final ForgeDirection side) {
        return new FluidTankInfo[0];
    }

    @Override
    public int fill(final FluidStack offered, final boolean actual) {
        if (offered == null || offered.getFluid() == null || offered.amount <= 0 || this.state.locked()) return 0;
        final FluidStack remainder = offered.copy();
        this.storePartial(remainder, !actual); // Direct fill cannot grant protected-transaction emergency space.
        return offered.amount - remainder.amount;
    }

    @Override
    public boolean outputsSteam() {
        return true;
    }

    @Override
    public boolean outputsLiquids() {
        return true;
    }

    @Override
    public boolean outputsItems() {
        return false;
    }

    @Override
    public boolean isFluidLocked() {
        return false;
    }

    @Override
    public boolean acceptsFluidLock(final Fluid fluid) {
        return false;
    }

    @Override
    public void setLockedFluid(final Fluid fluid) {}

    @Override
    public Fluid getLockedFluid() {
        return null;
    }

    @Override
    public void lockFluid(final boolean lock) {}

    @Override
    public void setMode(final byte mode) {}

    @Override
    public boolean setFilter(final FluidStack fluid) {
        return false;
    }

    @Override
    public boolean acceptsConfigCopy() {
        return false;
    }

    @Override
    public NBTTagCompound getCopiedData(final EntityPlayer player) {
        return new NBTTagCompound();
    }

    @Override
    public boolean pasteCopiedData(final EntityPlayer player, final NBTTagCompound tag) {
        return false;
    }

    @Override
    public void onScrewdriverRightClick(final ForgeDirection side, final EntityPlayer player, final float x,
        final float y, final float z, final ItemStack tool) {}

    @Override
    public boolean isEmptyAndAcceptsAnyFluid() {
        return !this.state.locked() && this.mInventory[0] == null
            && this.state.store()
                .identityCount() == 0
            && this.state.store()
                .capacity() > 0;
    }

    @Override
    public boolean canStoreFluid(final FluidStack stack) {
        return stack != null && stack.getFluid() != null
            && stack.amount > 0
            && !this.state.locked()
            && this.state.output()
                .storePartial(stack.copy(), true);
    }

    @Override
    public String[] getInfoData() {
        return new String[] { this.getLocalName(), this.state.store()
            .totalQuantity()
            .toString() + " L", this.state.fault() };
    }

    @Override
    public boolean storePartial(final FluidStack stack, final boolean simulate) {
        if (this.state.locked()) return false;
        try {
            return this.state.output()
                .storePartial(stack, simulate);
        } finally {
            if (!simulate) this.markDirty();
        }
    }

    @Override
    public IOutputHatchTransaction createTransaction() {
        return new HatchTransaction();
    }

    private final class HatchTransaction implements IOutputHatchTransaction, IOutputTransaction.IRecipeCheckAware,
        IOutputTransaction.IProtectOutputAware {

        private final DistributedFluidHatchState origin = MTEDistributedMEOutputHatch.this.state;
        private final PartitionFluidOutput.Transaction delegate = this.origin.output()
            .createTransaction();

        private void check() {
            if (this.origin != MTEDistributedMEOutputHatch.this.state || this.origin.locked())
                throw new IllegalStateException("Hatch output locked or lifecycle changed");
        }

        @Override
        public IOutputHatch getHatch() {
            return MTEDistributedMEOutputHatch.this;
        }

        @Override
        public void setRecipeCheck(final boolean value) {
            this.check();
            this.delegate.setRecipeCheck(value);
        }

        @Override
        public void setProtectOutput(final boolean value) {
            this.check();
            this.delegate.setProtectOutput(value);
        }

        @Override
        public boolean hasAvailableSpace() {
            return !this.origin.locked() && this.delegate.hasAvailableSpace();
        }

        @Override
        public boolean storePartial(final FluidId identity, final FluidStack stack, final long total, final long each) {
            this.check();
            try {
                return this.delegate.storePartial(identity, stack, total, each);
            } finally {
                if (this.origin.store()
                    .failure() != null) MTEDistributedMEOutputHatch.this.markDirty();
            }
        }

        @Override
        public void complete(final FluidId identity) {
            this.check();
            this.delegate.complete(identity);
        }

        @Override
        public void commit() {
            this.check();
            try {
                this.delegate.commit();
            } finally {
                MTEDistributedMEOutputHatch.this.markDirty();
            }
        }
    }
}
