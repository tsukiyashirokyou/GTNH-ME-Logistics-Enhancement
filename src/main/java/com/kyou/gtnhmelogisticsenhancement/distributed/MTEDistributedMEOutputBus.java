package com.kyou.gtnhmelogisticsenhancement.distributed;

import static appeng.util.item.AEItemStackType.ITEM_STACK_TYPE;

import java.util.EnumSet;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.util.ForgeDirection;

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
import appeng.api.storage.data.IAEItemStack;
import appeng.api.util.AECableType;
import appeng.api.util.AEColor;
import appeng.api.util.DimensionalCoord;
import appeng.me.GridAccessException;
import appeng.me.helpers.AENetworkProxy;
import appeng.util.item.AEItemStack;
import gregtech.api.enums.Dyes;
import gregtech.api.enums.OutputBusType;
import gregtech.api.enums.Textures;
import gregtech.api.interfaces.IOutputBus;
import gregtech.api.interfaces.IOutputBusTransaction;
import gregtech.api.interfaces.IOutputTransaction;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchOutputBus;
import gregtech.api.render.TextureFactory;
import gregtech.api.util.GTUtility.ItemId;

/** Generic GT output bus, with one real capacity-card slot and a private partitioned output cache. */
@IMetaTileEntity.SkipGenerateDescription
@IMetaTileEntity.SkipGenerateName
public class MTEDistributedMEOutputBus extends MTEHatchOutputBus implements DistributedOutputHost<ItemId> {

    private static final String DATA = "distributedItemBus";
    private DistributedItemBusState state = new DistributedItemBusState();
    private AENetworkProxy proxy;
    private NBTTagCompound networkData;
    private NBTBase cardEvidence;
    private boolean allSides;
    private boolean savedProxy;
    private boolean ready;
    private boolean policyReady;
    private boolean destroyed;
    private final MachineSource source = new MachineSource(this);

    public MTEDistributedMEOutputBus(final int id, final String name, final String regional) {
        super(id, name, regional, 4, null, 1);
    }

    public MTEDistributedMEOutputBus(final String name, final int tier, final String[] description,
        final ITexture[][][] textures) {
        super(name, tier, 1, description, textures);
    }

    @Override
    public MetaTileEntity newMetaEntity(final IGregTechTileEntity base) {
        return new MTEDistributedMEOutputBus(this.mName, this.mTier, this.mDescriptionArray, this.mTextures);
    }

    @Override
    public MetaTileEntity machine() {
        return this;
    }

    public DistributedItemBusState state() {
        return this.state;
    }

    @Override
    public String getLocalName() {
        return StatCollector.translateToLocal("gtnhmelogisticsenhancement.bus.name");
    }

    @Override
    public String[] getDescription() {
        return new String[] { StatCollector.translateToLocal("gtnhmelogisticsenhancement.bus.description"),
            StatCollector.translateToLocal("gtnhmelogisticsenhancement.bus.card_note"),
            StatCollector.translateToLocal("gtnhmelogisticsenhancement.bus.emergency_note") };
    }

    @Override
    public ITexture[] getTexturesActive(final ITexture base) {
        return new ITexture[] { base, TextureFactory.of(Textures.BlockIcons.OVERLAY_ME_HATCH_ACTIVE),
            TextureFactory.of(DistributedBusRegistration.PARTITION_OVERLAY) };
    }

    @Override
    public ITexture[] getTexturesInactive(final ITexture base) {
        return new ITexture[] { base, TextureFactory.of(Textures.BlockIcons.OVERLAY_ME_HATCH),
            TextureFactory.of(DistributedBusRegistration.PARTITION_OVERLAY) };
    }

    @Override
    public boolean pushOutputInventory() {
        return false;
    }

    @Override
    public boolean allowPullStack(final IGregTechTileEntity base, final int index, final ForgeDirection side,
        final ItemStack stack) {
        return false;
    }

    @Override
    public boolean allowPutStack(final IGregTechTileEntity base, final int index, final ForgeDirection side,
        final ItemStack stack) {
        return false;
    }

    @Override
    public boolean shouldDropItemAt(final int index) {
        return false;
    }

    @Override
    public int getInventoryStackLimit() {
        return 1;
    }

    @Override
    public boolean isItemValidForSlot(final int index, final ItemStack stack) {
        return index == 0 && stack != null
            && !this.state.locked()
            && CellCapacityPolicy.inspect(stack)
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
    public boolean isLocked() {
        return false;
    }

    @Override
    public boolean acceptsConfigCopy() {
        return false;
    }

    @Override
    public String getCopiedDataIdentifier(final EntityPlayer player) {
        return "distributedOutputNoCopy";
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
    public boolean onRightclick(final IGregTechTileEntity base, final EntityPlayer player) {
        if (base.isServerSide() && this.canConfigure(player)) this.openGui(player);
        return true;
    }

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
        try {
            return this.getProxy()
                .getSecurity()
                .hasPermission(player, SecurityPermissions.BUILD);
        } catch (GridAccessException offline) {
            return player.getUniqueID()
                .equals(base.getOwnerUuid());
        }
    }

    @Override
    public boolean onWireCutterRightClick(final ForgeDirection side, final ForgeDirection wrenchingSide,
        final EntityPlayer player, final float x, final float y, final float z, final ItemStack tool) {
        if (this.getBaseMetaTileEntity()
            .isServerSide() && this.canConfigure(player)
            && !this.state.locked()) {
            this.setConnectsToAllSides(!this.allSides);
            return true;
        }
        return false;
    }

    @Override
    public boolean connectsToAllSides() {
        return this.allSides;
    }

    @Override
    public void setConnectsToAllSides(final boolean connects) {
        if (this.state.locked()) return;
        this.allSides = connects;
        this.onFacingChange();
        this.markDirty();
    }

    @Override
    public AENetworkProxy getProxy() {
        if (this.proxy == null) {
            this.proxy = DistributedAEOutput.createProxy(this, this, () -> this.savedProxy);
            this.updateSides();
            if (this.networkData != null) {
                this.proxy.readFromNBT(this.networkData);
            }
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
        final IGridNode node = this.getProxy()
            .getNode();
        if (node != null) node.updateState();
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
        super.onPostTick(base, tick); // pushOutputInventory=false: superclass cannot eject the card every eight ticks.
        if (!base.isServerSide() || !this.ready || this.destroyed || !this.state.tick()) return;
        if (!this.getProxy()
            .isActive()) return;
        final boolean changed = this.state.flush((identity, amount) -> {
            final IAEItemStack remaining = DistributedAEOutput.insert(
                this.proxy,
                AEItemStack.create(identity.getItemStack())
                    .setStackSize(amount),
                ITEM_STACK_TYPE,
                this.source);
            if (remaining == null) return null;
            final ItemStack item = remaining.getItemStack();
            return new DistributedItemBusState.Remainder(
                ItemId.create(item.getItem(), item.getItemDamage(), item.getTagCompound()),
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
    public void onRemoval() {
        this.preserveNetworkData();
        this.destroyed = true;
        this.state.stop();
        if (this.proxy != null) this.proxy.invalidate();
        super.onRemoval();
    }

    @Override
    public void onBlockDestroyed() {
        this.preserveNetworkData();
        this.destroyed = true;
        this.state.stop();
        super.onBlockDestroyed();
    }

    private void preserveNetworkData() {
        if (this.proxy == null) return;
        final NBTTagCompound saved = new NBTTagCompound();
        this.proxy.writeToNBT(saved);
        if (saved.hasKey("distributedAE")) this.networkData = saved;
    }

    @Override
    public void saveNBTData(final NBTTagCompound tag) {
        super.saveNBTData(tag);
        this.writeBus(tag);
    }

    private void writeBus(final NBTTagCompound tag) {
        tag.setTag(DATA, this.state.write());
        tag.setBoolean("distributedAllSides", this.allSides);
        if (this.cardEvidence != null) tag.setTag("distributedCardEvidence", this.cardEvidence.copy());
        if (this.proxy != null) this.proxy.writeToNBT(tag);
        if (!tag.hasKey("distributedAE") && this.networkData != null && this.networkData.hasKey("distributedAE"))
            tag.setTag(
                "distributedAE",
                this.networkData.getTag("distributedAE")
                    .copy());
    }

    @Override
    public void loadNBTData(final NBTTagCompound tag) {
        super.loadNBTData(tag);
        // GT CommonBaseMetaTileEntity has already restored Inventory (including the real card).
        this.state = tag.hasKey(DATA) ? DistributedItemBusState.read(tag.getTag(DATA), this.mInventory[0])
            : new DistributedItemBusState();
        if (!tag.hasKey(DATA)) this.state.updateCard(this.mInventory[0]);
        this.policyReady = true;
        this.cardEvidence = null;
        if (tag.hasKey("distributedCardEvidence")) this.cardEvidence = tag.getTag("distributedCardEvidence")
            .copy();
        else if (tag.hasKey("Inventory")) {
            final NBTBase raw = tag.getTag("Inventory");
            boolean valid = raw instanceof NBTTagList;
            final NBTTagList inventory = tag.getTagList("Inventory", 10);
            valid &= inventory.tagCount() <= 1;
            if (raw instanceof NBTTagList && ((NBTTagList) raw).tagCount() != inventory.tagCount()) valid = false;
            if (inventory.tagCount() == 1) {
                final NBTTagCompound entry = inventory.getCompoundTagAt(0);
                final ItemStack decoded = ItemStack.loadItemStackFromNBT(entry);
                valid &= entry.hasKey("IntSlot", 3) && entry.getInteger("IntSlot") == 0
                    && decoded != null
                    && decoded.stackSize == 1
                    && ItemStack.areItemStacksEqual(decoded, this.mInventory[0]);
            }
            if (!valid) this.cardEvidence = raw.copy();
        }
        if (this.cardEvidence != null) this.state.isolate("INVALID_CARD_INVENTORY");
        this.allSides = tag.getBoolean("distributedAllSides");
        this.savedProxy = tag.hasKey("distributedAE");
        this.networkData = new NBTTagCompound();
        if (this.savedProxy) this.networkData.setTag(
            "distributedAE",
            tag.getTag("distributedAE")
                .copy());
        if (this.proxy != null) {
            this.proxy.readFromNBT(this.networkData);
        }
    }

    @Override
    public void setItemNBT(final NBTTagCompound tag) {
        super.setItemNBT(tag);
        this.writeBus(tag);
        final NBTTagList inventory = new NBTTagList();
        if (this.mInventory[0] != null) {
            final NBTTagCompound card = new NBTTagCompound();
            this.mInventory[0].writeToNBT(card);
            card.setInteger("IntSlot", 0);
            inventory.appendTag(card);
        }
        tag.setTag("Inventory", inventory);
        // The snapshot now belongs to the machine item. The old TE must never output it again.
        this.destroyed = true;
        this.state.stop();
    }

    @Override
    public boolean isFiltered() {
        return false;
    }

    @Override
    public boolean isFilteredToItem(final ItemId identity) {
        return false;
    }

    @Override
    public OutputBusType getBusType() {
        return OutputBusType.MECacheUnfiltered;
    }

    @Override
    public boolean storePartial(final ItemStack stack, final boolean simulate) {
        if (this.state.locked()) return false;
        try {
            return this.state.output()
                .storePartial(stack, simulate);
        } finally {
            if (!simulate) this.markDirty();
        }
    }

    @Override
    public IOutputBusTransaction createTransaction() {
        return new BusTransaction();
    }

    private final class BusTransaction
        implements IOutputBusTransaction, IOutputTransaction.IRecipeCheckAware, IOutputTransaction.IProtectOutputAware {

        private final DistributedItemBusState origin = MTEDistributedMEOutputBus.this.state;
        private final PartitionItemOutput.Transaction delegate = this.origin.output()
            .createTransaction();

        private void check() {
            if (this.origin != MTEDistributedMEOutputBus.this.state || this.origin.locked())
                throw new IllegalStateException("Bus output is locked or its lifecycle changed");
        }

        @Override
        public IOutputBus getBus() {
            return MTEDistributedMEOutputBus.this;
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
        public boolean storePartial(final ItemId identity, final ItemStack stack, final long total, final long each) {
            this.check();
            try {
                return this.delegate.storePartial(identity, stack, total, each);
            } finally {
                if (this.origin.store()
                    .failure() != null) MTEDistributedMEOutputBus.this.markDirty();
            }
        }

        @Override
        public void complete(final ItemId identity) {
            this.check();
            this.delegate.complete(identity);
        }

        @Override
        public void commit() {
            this.check();
            try {
                this.delegate.commit();
            } finally {
                MTEDistributedMEOutputBus.this.markDirty();
            }
        }
    }

    public void configurePartitions(final int value) {
        this.state.setPartitions(value);
        this.markDirty();
        this.notifyWatchers();
    }

    public void configurePeriod(final int value) {
        this.state.setPeriod(value);
        this.markDirty();
    }

    @Override
    public ModularPanel buildUI(final PosGuiData data, final PanelSyncManager sync, final UISettings settings) {
        return DistributedItemBusGui.build(this, data, sync, settings);
    }

    @Override
    protected boolean forceUseMui2() {
        return true;
    }
}
