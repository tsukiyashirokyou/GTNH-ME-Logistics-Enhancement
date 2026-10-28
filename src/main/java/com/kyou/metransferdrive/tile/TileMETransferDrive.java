package com.kyou.metransferdrive.tile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.kyou.metransferdrive.METransferDriveMod;
import com.kyou.metransferdrive.binding.BindingToolNbt;
import com.kyou.metransferdrive.transfer.AETransferController;
import com.kyou.metransferdrive.transfer.RecoveryEvidence;
import com.kyou.metransferdrive.transfer.RouteGraph;
import com.kyou.metransferdrive.transfer.RoutingCycle;
import com.kyou.metransferdrive.transfer.TargetRegistry;
import com.kyou.metransferdrive.transfer.TransferInjectionGuard;
import com.kyou.metransferdrive.transfer.TransferTarget;

import appeng.api.AEApi;
import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.events.MENetworkCellArrayUpdate;
import appeng.api.networking.events.MENetworkChannelsChanged;
import appeng.api.networking.events.MENetworkEventSubscribe;
import appeng.api.networking.events.MENetworkPowerStatusChange;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.MachineSource;
import appeng.api.storage.ICellContainer;
import appeng.api.storage.ICellHandler;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.data.AEStackTypeRegistry;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEStackType;
import appeng.api.util.AECableType;
import appeng.api.util.DimensionalCoord;
import appeng.me.GridAccessException;
import appeng.me.storage.MEInventoryHandler;
import appeng.tile.TileEvent;
import appeng.tile.events.TileEventType;
import appeng.tile.grid.AENetworkInvTile;
import appeng.tile.inventory.AppEngInternalInventory;
import appeng.tile.inventory.InvOperation;
import appeng.util.Platform;

/** A single source-grid, 64-cell drive with bounded transfers to independent target grids. */
public class TileMETransferDrive extends AENetworkInvTile implements ICellContainer {

    private static final int[] ACCESSIBLE_SLOTS = new int[DriveSlotLayout.TOTAL_SLOTS];

    static {
        for (int slot = 0; slot < ACCESSIBLE_SLOTS.length; slot++) {
            ACCESSIBLE_SLOTS[slot] = slot;
        }
    }

    private final AppEngInternalInventory cells = new ProtectedCellInventory();
    private final MEInventoryHandler<?>[] handlersBySlot = new MEInventoryHandler<?>[DriveSlotLayout.TOTAL_SLOTS];
    private final Map<IAEStackType<?>, List<IMEInventoryHandler>> cellsByType = new IdentityHashMap<>();
    private final BaseActionSource actionSource = new MachineSource(this);
    private final AETransferController transfer = new AETransferController(this);

    private AccessRestriction accessMode = AccessRestriction.READ_WRITE;
    private boolean handlersCached;
    private boolean buildingHandlers;
    private boolean ready;
    private boolean wasActive;
    private UUID bindingIdentity = UUID.randomUUID();
    private NBTBase quarantinedCells;
    private boolean loadingCells;

    public TileMETransferDrive() {
        this.getProxy()
            .setFlags(GridFlags.REQUIRE_CHANNEL);
    }

    @Override
    public void onReady() {
        this.getProxy()
            .setVisualRepresentation(new ItemStack(METransferDriveMod.meTransferDrive));
        super.onReady();
        this.ready = true;
        if (this.worldObj != null && !this.worldObj.isRemote) {
            this.markDirty();
        }
        this.updateHandlers();
        this.updateNetworkAvailability();
        if (this.worldObj != null && !this.worldObj.isRemote) {
            this.transfer.topologyChanged();
        }
    }

    @MENetworkEventSubscribe
    public void channelsChanged(final MENetworkChannelsChanged event) {
        this.transfer.topologyChanged();
        this.updateNetworkAvailability();
    }

    @MENetworkEventSubscribe
    public void powerChanged(final MENetworkPowerStatusChange event) {
        this.transfer.topologyChanged();
        this.updateNetworkAvailability();
    }

    private void updateNetworkAvailability() {
        final boolean active = this.getProxy()
            .isActive();
        if (active != this.wasActive) {
            this.wasActive = active;
            this.postCellArrayUpdate();
        }
    }

    @TileEvent(TileEventType.TICK)
    public void tickTransfers() {
        if (this.ready && this.worldObj != null && !this.worldObj.isRemote && !this.isInvalid()) {
            this.transfer.tick();
        }
    }

    @Override
    public void invalidate() {
        this.ready = false;
        this.transfer.suspend();
        super.invalidate();
    }

    @Override
    public void onChunkUnload() {
        this.ready = false;
        this.transfer.suspend();
        super.onChunkUnload();
    }

    @Override
    public AECableType getCableConnectionType(final ForgeDirection side) {
        return AECableType.SMART;
    }

    @Override
    public DimensionalCoord getLocation() {
        return new DimensionalCoord(this);
    }

    @Override
    public IInventory getInternalInventory() {
        return this.cells;
    }

    @Override
    public int getInventoryStackLimit() {
        return 1;
    }

    @Override
    public int[] getAccessibleSlotsBySide(final ForgeDirection side) {
        return ACCESSIBLE_SLOTS;
    }

    @Override
    public boolean isItemValidForSlot(final int slot, final ItemStack stack) {
        return !this.hasTransferRecovery() && slot >= 0
            && slot < DriveSlotLayout.TOTAL_SLOTS
            && stack != null
            && AEApi.instance()
                .registries()
                .cell()
                .isCellHandled(stack);
    }

    @Override
    public void onChangeInventory(final IInventory inventory, final int slot, final InvOperation operation,
        final ItemStack removed, final ItemStack added) {
        if (this.loadingCells) {
            this.handlersCached = false;
            return;
        }

        if (slot < 0) {
            // AE's setter/decrement ends with markDirty(-1), after the new handlers may already be in the Grid.
            // A content/save notification must not detach our cache from those published handlers.
            this.saveChanges(null);
            return;
        }

        this.handlersCached = false;
        if (this.worldObj != null && !this.worldObj.isRemote) {
            this.markDirty();
        }
        if (!this.ready || this.worldObj == null || this.worldObj.isRemote) {
            return;
        }

        this.transfer.topologyChanged();
        this.updateHandlers();
        try {
            this.getProxy()
                .getGrid()
                .postEvent(new MENetworkCellArrayUpdate());
            if (this.getProxy()
                .isActive() && this.accessMode.hasPermission(AccessRestriction.READ)) {
                Platform.postChanges(
                    this.getProxy()
                        .getStorage(),
                    removed,
                    added,
                    this.actionSource);
            }
        } catch (final GridAccessException ignored) {
            // An unpowered or disconnected drive has no storage grid to notify.
        }
    }

    @TileEvent(TileEventType.WORLD_NBT_READ)
    public void readAccessMode(final NBTTagCompound data) {
        if (data.hasKey("sourceAccessMode")) {
            try {
                final AccessRestriction stored = AccessRestriction.valueOf(data.getString("sourceAccessMode"));
                this.accessMode = stored == AccessRestriction.NO_ACCESS ? AccessRestriction.READ_WRITE : stored;
            } catch (final IllegalArgumentException ignored) {
                this.accessMode = AccessRestriction.READ_WRITE;
            }
        }
    }

    @TileEvent(TileEventType.WORLD_NBT_WRITE)
    public void writeAccessMode(final NBTTagCompound data) {
        data.setString("sourceAccessMode", this.accessMode.name());
    }

    @TileEvent(TileEventType.WORLD_NBT_READ)
    public void readTransferState(final NBTTagCompound data) {
        this.transfer.readFromNBT(data);
    }

    @TileEvent(TileEventType.WORLD_NBT_WRITE)
    public void writeTransferState(final NBTTagCompound data) {
        this.transfer.writeToNBT(data);
    }

    @TileEvent(TileEventType.WORLD_NBT_READ)
    public void readBindingIdentity(final NBTTagCompound data) {
        this.bindingIdentity = BindingToolNbt.readDeviceIdentity(data, this.bindingIdentity);
    }

    @TileEvent(TileEventType.WORLD_NBT_WRITE)
    public void writeBindingIdentity(final NBTTagCompound data) {
        BindingToolNbt.writeDeviceIdentity(data, this.bindingIdentity);
    }

    public UUID getBindingIdentity() {
        return this.bindingIdentity;
    }

    public boolean isTransferConfigurationLocked() {
        return this.transfer.isConfigurationLocked() || this.quarantinedCells != null;
    }

    /** Code-only endpoint for the future validated binding flow. No player binding gesture is added here. */
    public void setTransferTarget(final TransferTarget target) {
        this.requireServer();
        this.transfer.setTarget(target);
    }

    public TransferTarget getTransferTarget() {
        return this.transfer.getTarget();
    }

    /** Server-thread API for later binding/GUI code; no temporary player binding mechanism. */
    public UUID registerTransferTarget(final TransferTarget target) {
        this.requireServer();
        return this.transfer.registerTarget(target);
    }

    public List<TransferTarget> getTransferTargets() {
        return this.transfer.getTargets();
    }

    public void updateTransferTarget(final UUID id, final String name, final int priority) {
        this.requireServer();
        this.transfer.updateTarget(id, name, priority);
    }

    public void removeTransferTarget(final UUID id) {
        this.requireServer();
        this.transfer.removeTarget(id);
    }

    public void setTransferGroupRoute(final int group, final TargetRegistry.Route route) {
        this.requireServer();
        this.transfer.setRoute(group, route);
    }

    public TargetRegistry.Route getTransferGroupRoute(final int group) {
        return this.transfer.getRoute(group);
    }

    /** Status of the most recent cycle's resolution; it is not a persistent network identity. */
    public RoutingCycle.Status getTransferTargetStatus(final UUID id) {
        return this.transfer.getTargetStatus(id);
    }

    public void resetTransferRoutingConfiguration() {
        this.requireServer();
        this.transfer.resetRoutingConfiguration();
    }

    public void setTransferPeriodSeconds(final int seconds) {
        this.requireServer();
        this.transfer.setPeriodSeconds(seconds);
    }

    public int getTransferPeriodSeconds() {
        return this.transfer.getPeriodSeconds();
    }

    public String getTransferStatus() {
        return this.quarantinedCells != null ? "invalid_cell_inventory" : this.transfer.getStatus();
    }

    public boolean hasTransferRecovery() {
        return this.transfer.hasRecovery() || this.quarantinedCells != null;
    }

    public boolean hasCellInventoryQuarantine() {
        return this.quarantinedCells != null;
    }

    public RouteGraph.Device<IGrid> getTransferRoutingWitness(final IAEStackType<?> type) {
        return this.transfer.routingWitness(type);
    }

    public long getTransferConfigurationRevision() {
        return this.transfer.getConfigurationRevision();
    }

    @Override
    @TileEvent(TileEventType.WORLD_NBT_READ)
    public void readFromNBT_AEBaseInvTile(final NBTTagCompound data) {
        final CellInventoryNbt.Loaded loaded = CellInventoryNbt.read(data, ItemStack::loadItemStackFromNBT);
        this.loadingCells = true;
        try {
            this.quarantinedCells = loaded.quarantine;
            for (int slot = 0; slot < loaded.cells.length; slot++) {
                this.cells.setInventorySlotContents(slot, loaded.cells[slot]);
            }
            this.handlersCached = false;
        } finally {
            this.loadingCells = false;
        }
    }

    @Override
    @TileEvent(TileEventType.WORLD_NBT_WRITE)
    public void writeToNBT_AEBaseInvTile(final NBTTagCompound data) {
        final ItemStack[] contents = new ItemStack[DriveSlotLayout.TOTAL_SLOTS];
        for (int slot = 0; slot < contents.length; slot++) {
            contents[slot] = this.cells.getStackInSlot(slot);
        }
        CellInventoryNbt.write(data, contents, this.quarantinedCells);
    }

    /** Bypasses only this drive's outer access mode, keeping the cell's own handler and permissions. */
    public IMEInventory<?> getNativeCellHandler(final int slot) {
        this.updateHandlers();
        final MEInventoryHandler<?> handler = this.handlersBySlot[slot];
        return handler == null ? null : handler.getInternal();
    }

    @Override
    public boolean canExtractItem(final int slot, final ItemStack stack, final int side) {
        return !this.hasTransferRecovery();
    }

    @Override
    public void getDrops(final World world, final int x, final int y, final int z, final List<ItemStack> drops) {
        if (!this.hasTransferRecovery()) {
            super.getDrops(world, x, y, z, drops);
            return;
        }
        // A recovery device carries its installed cells and one transaction record together, exactly once.
        final ItemStack packed = new ItemStack(METransferDriveMod.meTransferDrive);
        final NBTTagCompound state = new NBTTagCompound();
        this.writeToNBT(state);
        drops.add(RecoveryEvidence.pack(packed, state));
    }

    public void restoreRecoveryItem(final ItemStack stack) {
        if (!stack.hasTagCompound() || !stack.getTagCompound()
            .hasKey("metransferdriveRecovery")) {
            return;
        }
        this.requireServer();
        if (!this.cells.isEmpty() || this.hasTransferRecovery()) {
            throw new IllegalStateException("A recovery item may only initialize an empty device");
        }
        final NBTTagCompound state = RecoveryEvidence.packedState(
            stack.getTagCompound()
                .getTag("metransferdriveRecovery"));
        final boolean wasReady = this.ready;
        this.ready = false;
        try {
            this.readFromNBT_AEBaseInvTile(state);
            this.readAccessMode(state);
            this.readTransferState(state);
        } finally {
            this.ready = wasReady;
        }
        this.handlersCached = false;
        this.markDirty();
        if (wasReady) {
            this.updateHandlers();
            this.postCellArrayUpdate();
        }
        // The old proxy/node is deliberately not restored into a newly placed device.
        // Its binding identity is also not restored: tools pointing at the removed placement must expire.
    }

    private void requireServer() {
        if (this.worldObj != null && this.worldObj.isRemote) {
            throw new IllegalStateException("Transfer configuration must be changed on the server");
        }
    }

    public AccessRestriction getAccessMode() {
        return this.accessMode;
    }

    /** Restricts only source-grid access. Existing cell handlers keep their identity. */
    public void setAccessMode(final AccessRestriction mode) {
        if (mode == null || mode == AccessRestriction.NO_ACCESS) {
            throw new IllegalArgumentException("Expected READ, WRITE or READ_WRITE");
        }
        if (this.worldObj != null && this.worldObj.isRemote) {
            return;
        }
        if (this.accessMode == mode) {
            return;
        }

        this.transfer.sourceAccessChanging();
        this.accessMode = mode;
        for (final MEInventoryHandler<?> handler : this.handlersBySlot) {
            if (handler != null) {
                handler.setBaseAccess(mode);
            }
        }
        this.markDirty();
        if (this.ready && this.worldObj != null && !this.worldObj.isRemote) {
            this.postCellArrayUpdate();
        }
    }

    @Override
    public AccessRestriction getReshuffleAccess() {
        return AccessRestriction.READ_WRITE;
    }

    @Override
    @Nonnull
    @SuppressWarnings("rawtypes")
    public List<IMEInventoryHandler> getCellArray(final IAEStackType<?> type) {
        if (!this.getProxy()
            .isActive()) {
            return Collections.emptyList();
        }
        this.updateHandlers();
        final List<IMEInventoryHandler> handlers = this.cellsByType.get(type);
        return handlers == null ? Collections.emptyList() : handlers;
    }

    @Override
    public int getPriority() {
        return 0;
    }

    @Override
    public void saveChanges(final IMEInventory inventory) {
        if (this.worldObj != null && !this.worldObj.isRemote) {
            this.worldObj.markTileEntityChunkModified(this.xCoord, this.yCoord, this.zCoord, this);
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void updateHandlers() {
        if (this.handlersCached) {
            return;
        }
        if (this.buildingHandlers) {
            throw new IllegalStateException("Recursive cell handler construction");
        }

        double idlePower = 2.0;
        this.buildingHandlers = true;
        try {
            this.cellsByType.clear();
            for (final IAEStackType<?> type : this.cellStackTypes()) {
                this.cellsByType.put(type, new ArrayList<>());
            }

            for (int slot = 0; slot < DriveSlotLayout.TOTAL_SLOTS; slot++) {
                this.handlersBySlot[slot] = null;
                final ItemStack cell = this.cells.getStackInSlot(slot);
                if (cell == null) {
                    continue;
                }

                final ICellHandler cellHandler = this.findCellHandler(cell);
                if (cellHandler == null) {
                    continue;
                }

                for (final IAEStackType<?> type : this.cellStackTypes()) {
                    final IMEInventoryHandler nativeHandler = cellHandler.getCellInventory(cell, this, type);
                    if (nativeHandler == null) {
                        continue;
                    }

                    final MEInventoryHandler handler = new DriveCellHandler(
                        nativeHandler,
                        nativeHandler.getStackType(),
                        this);
                    handler.setBaseAccess(this.accessMode);
                    this.handlersBySlot[slot] = handler;
                    this.cellsByType.get(type)
                        .add(handler);
                    idlePower += cellHandler.cellIdleDrain(cell, nativeHandler);
                    break;
                }
            }

            // setIdlePowerUsage posts a synchronous AE event. Its listeners must see the completed cache.
            this.handlersCached = true;
        } finally {
            this.buildingHandlers = false;
        }

        this.getProxy()
            .setIdlePowerUsage(idlePower);
    }

    ICellHandler findCellHandler(final ItemStack cell) {
        return AEApi.instance()
            .registries()
            .cell()
            .getHandler(cell);
    }

    Iterable<IAEStackType<?>> cellStackTypes() {
        return AEStackTypeRegistry.getAllTypes();
    }

    private void postCellArrayUpdate() {
        try {
            this.getProxy()
                .getGrid()
                .postEvent(new MENetworkCellArrayUpdate());
        } catch (final GridAccessException ignored) {
            // The proxy will be registered again when the grid becomes available.
        }
    }

    private final class ProtectedCellInventory extends AppEngInternalInventory {

        ProtectedCellInventory() {
            super(TileMETransferDrive.this, DriveSlotLayout.TOTAL_SLOTS, 1);
        }

        @Override
        public void setInventorySlotContents(final int slot, final ItemStack stack) {
            if (buildingHandlers) {
                throw new IllegalStateException("Cannot replace cells while constructing their handlers");
            }
            if (!loadingCells && hasTransferRecovery()) {
                throw new IllegalStateException("Cell inventory is locked by a pending transfer transaction");
            }
            if (slot < 0 || slot >= DriveSlotLayout.TOTAL_SLOTS || stack != null && stack.stackSize != 1) {
                throw new IllegalArgumentException("Expected one cell in a valid slot");
            }
            super.setInventorySlotContents(slot, stack);
        }

        @Override
        public ItemStack decrStackSize(final int slot, final int amount) {
            return buildingHandlers || hasTransferRecovery()
                || amount <= 0
                || slot < 0
                || slot >= DriveSlotLayout.TOTAL_SLOTS ? null : super.decrStackSize(slot, amount);
        }
    }

    private static class DriveCellHandler<T extends IAEStack<T>> extends MEInventoryHandler<T> {

        private final TileMETransferDrive drive;

        DriveCellHandler(final IMEInventory<T> inventory, final IAEStackType<T> type, final TileMETransferDrive drive) {
            super(inventory, type);
            this.drive = drive;
        }

        @Override
        public boolean canAccept(final T input) {
            return !TransferInjectionGuard.blocks(this.drive) && super.canAccept(input);
        }

        @Override
        public T injectItems(final T input, final Actionable mode, final BaseActionSource source) {
            return TransferInjectionGuard.blocks(this.drive) ? input : super.injectItems(input, mode, source);
        }

        @Override
        public AccessRestriction getReshuffleAccess() {
            return this.drive.getReshuffleAccess();
        }
    }
}
