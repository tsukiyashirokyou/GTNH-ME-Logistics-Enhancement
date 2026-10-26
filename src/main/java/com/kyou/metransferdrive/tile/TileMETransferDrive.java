package com.kyou.metransferdrive.tile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;

import com.kyou.metransferdrive.METransferDriveMod;

import appeng.api.AEApi;
import appeng.api.config.AccessRestriction;
import appeng.api.networking.GridFlags;
import appeng.api.networking.events.MENetworkCellArrayUpdate;
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

/** A single-grid, 64-cell drive. Routing is added in a later stage. */
public class TileMETransferDrive extends AENetworkInvTile implements ICellContainer {

    private static final int[] ACCESSIBLE_SLOTS = new int[DriveSlotLayout.TOTAL_SLOTS];

    static {
        for (int slot = 0; slot < ACCESSIBLE_SLOTS.length; slot++) {
            ACCESSIBLE_SLOTS[slot] = slot;
        }
    }

    private final AppEngInternalInventory cells = new AppEngInternalInventory(this, DriveSlotLayout.TOTAL_SLOTS, 1);
    private final MEInventoryHandler<?>[] handlersBySlot = new MEInventoryHandler<?>[DriveSlotLayout.TOTAL_SLOTS];
    private final Map<IAEStackType<?>, List<IMEInventoryHandler>> cellsByType = new IdentityHashMap<>();
    private final BaseActionSource actionSource = new MachineSource(this);

    private AccessRestriction accessMode = AccessRestriction.READ_WRITE;
    private boolean handlersCached;
    private boolean ready;

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
        this.updateHandlers();
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
        return slot >= 0 && slot < DriveSlotLayout.TOTAL_SLOTS
            && stack != null
            && AEApi.instance()
                .registries()
                .cell()
                .isCellHandled(stack);
    }

    @Override
    public void onChangeInventory(final IInventory inventory, final int slot, final InvOperation operation,
        final ItemStack removed, final ItemStack added) {
        if (slot < 0) {
            return;
        }

        this.handlersCached = false;
        if (this.worldObj != null && !this.worldObj.isRemote) {
            this.markDirty();
        }
        if (!this.ready || this.worldObj == null || this.worldObj.isRemote) {
            return;
        }

        this.updateHandlers();
        try {
            this.getProxy()
                .getGrid()
                .postEvent(new MENetworkCellArrayUpdate());
            Platform.postChanges(
                this.getProxy()
                    .getStorage(),
                removed,
                added,
                this.actionSource);
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

        this.cellsByType.clear();
        for (final IAEStackType<?> type : AEStackTypeRegistry.getAllTypes()) {
            this.cellsByType.put(type, new ArrayList<>());
        }

        double idlePower = 2.0;
        for (int slot = 0; slot < DriveSlotLayout.TOTAL_SLOTS; slot++) {
            this.handlersBySlot[slot] = null;
            final ItemStack cell = this.cells.getStackInSlot(slot);
            if (cell == null) {
                continue;
            }

            final ICellHandler cellHandler = AEApi.instance()
                .registries()
                .cell()
                .getHandler(cell);
            if (cellHandler == null) {
                continue;
            }

            for (final IAEStackType<?> type : AEStackTypeRegistry.getAllTypes()) {
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

        this.getProxy()
            .setIdlePowerUsage(idlePower);
        this.handlersCached = true;
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

    private static class DriveCellHandler<T extends IAEStack<T>> extends MEInventoryHandler<T> {

        private final TileMETransferDrive drive;

        DriveCellHandler(final IMEInventory<T> inventory, final IAEStackType<T> type, final TileMETransferDrive drive) {
            super(inventory, type);
            this.drive = drive;
        }

        @Override
        public AccessRestriction getReshuffleAccess() {
            return this.drive.getReshuffleAccess();
        }
    }
}
