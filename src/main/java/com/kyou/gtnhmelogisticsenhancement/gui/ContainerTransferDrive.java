package com.kyou.gtnhmelogisticsenhancement.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ICrafting;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import com.kyou.gtnhmelogisticsenhancement.tile.DriveSlotLayout;
import com.kyou.gtnhmelogisticsenhancement.tile.TileMETransferDrive;
import com.kyou.gtnhmelogisticsenhancement.transfer.TargetRegistry;
import com.kyou.gtnhmelogisticsenhancement.transfer.TransferTarget;

import appeng.api.AEApi;
import appeng.api.config.AccessRestriction;
import appeng.api.networking.security.PlayerSource;
import appeng.util.Platform;

/** Fixed physical slot IDs: delayed clicks can never be remapped to another cell. */
public final class ContainerTransferDrive extends Container implements DriveGuiLogic.Context {

    private final TileMETransferDrive tile;
    private final EntityPlayer owner;
    private final UUID deviceId;
    private final UUID sessionId = UUID.randomUUID();
    private final IInventory cells;
    private int page;
    private long configRevision;
    private NBTTagCompound lastConfig;
    private NBTTagCompound lastSent;
    private NBTTagCompound clientState = new NBTTagCompound();
    private String feedback = "ok";
    private int syncTicks;
    private long actionTick = Long.MIN_VALUE;
    private int actionsThisTick;
    private long pageRevision;
    private long lastClickSequence;
    private long clickTick = Long.MIN_VALUE;
    private int clicksThisTick;
    private boolean processingClick;

    public ContainerTransferDrive(final EntityPlayer player, final TileMETransferDrive tile) {
        this.tile = tile;
        this.owner = player;
        this.deviceId = tile.getBindingIdentity();
        this.cells = player.worldObj.isRemote ? new InventoryBasic("Transfer cells", false, 64) : tile;
        for (int slot = 0; slot < DriveSlotLayout.TOTAL_SLOTS; slot++) {
            this.addSlotToContainer(new CellSlot(slot));
        }
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                this.addSlotToContainer(
                    new Slot(player.inventory, column + row * 9 + 9, 9 + column * 18, 169 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            this.addSlotToContainer(new Slot(player.inventory, column, 9 + column * 18, 223));
        }
        this.positionSlots();
    }

    @Override
    public boolean canInteractWith(final EntityPlayer player) {
        if (player != this.owner || player.isDead) {
            return false;
        }
        if (player.worldObj.isRemote) {
            return true;
        }
        return player.worldObj == this.tile.getWorldObj() && !this.tile.isInvalid()
            && player.worldObj.getChunkProvider()
                .chunkExists(this.tile.xCoord >> 4, this.tile.zCoord >> 4)
            && player.worldObj.getTileEntity(this.tile.xCoord, this.tile.yCoord, this.tile.zCoord) == this.tile
            && this.deviceId.equals(this.tile.getBindingIdentity())
            && player.getDistanceSq(this.tile.xCoord + 0.5, this.tile.yCoord + 0.5, this.tile.zCoord + 0.5) <= 64
            && player.worldObj.canMineBlock(player, this.tile.xCoord, this.tile.yCoord, this.tile.zCoord)
            && player.canPlayerEdit(this.tile.xCoord, this.tile.yCoord, this.tile.zCoord, 1, player.getHeldItem())
            && Platform.canAccess(this.tile.getProxy(), new PlayerSource(player, null));
    }

    @Override
    public boolean canUse() {
        return this.owner.openContainer == this && this.canInteractWith(this.owner);
    }

    @Override
    public boolean isLocked() {
        return this.owner.worldObj.isRemote
            ? !this.clientState.hasKey("session") || this.clientState.getBoolean("locked")
            : this.tile.isTransferConfigurationLocked();
    }

    @Override
    public UUID session() {
        return this.sessionId;
    }

    @Override
    public long revision() {
        return this.configRevision;
    }

    @Override
    public void setPage(final int group) {
        if (this.page != group) {
            this.pageRevision++;
        }
        this.page = group;
        this.func_94533_d();
        this.positionSlots();
        // A newly visible page may have changed while hidden; force its eight slots to refresh.
        if (!this.owner.worldObj.isRemote) {
            for (final ICrafting listener : this.crafters) {
                for (int index = 0; index < 8; index++) {
                    final int slot = DriveSlotLayout.slot(group, index);
                    listener.sendSlotContents(this, slot, this.cells.getStackInSlot(slot));
                }
            }
        }
    }

    private void positionSlots() {
        for (int index = 0; index < 64; index++) {
            final Slot slot = this.getSlot(index);
            slot.xDisplayPosition = DriveGuiLogic.visibleSlot(index, this.page) ? 18 + index % 8 * 18 : -10000;
            slot.yDisplayPosition = DriveGuiLogic.visibleSlot(index, this.page) ? 47 : -10000;
        }
    }

    @Override
    public ItemStack slotClick(final int slot, final int button, final int mode, final EntityPlayer player) {
        if (!this.owner.worldObj.isRemote && !this.processingClick || player != this.owner
            || !this.canUse()
            || this.isLocked()
            || !DriveGuiLogic.validClick(slot, button, mode, this.page)) {
            this.func_94533_d();
            return null;
        }
        // Vanilla 1.7.10's number-key path places the entire hotbar stack without checking the slot limit.
        if (mode == 2 && !DriveGuiLogic.validHotbarSwap(
            slot,
            mode,
            player.inventory.getStackInSlot(button) == null ? 0 : player.inventory.getStackInSlot(button).stackSize)) {
            return null;
        }
        return super.slotClick(slot, button, mode, player);
    }

    @Override
    public boolean canDragIntoSlot(final Slot slot) {
        return slot.slotNumber >= 64 || DriveGuiLogic.visibleSlot(slot.slotNumber, this.page);
    }

    @Override
    public ItemStack transferStackInSlot(final EntityPlayer player, final int index) {
        if (!this.canUse() || this.isLocked() || index < 0 || index >= this.inventorySlots.size()) {
            return null;
        }
        final Slot slot = this.getSlot(index);
        if (!slot.getHasStack() || !slot.canTakeStack(player)) {
            return null;
        }
        final ItemStack stack = slot.getStack();
        final ItemStack original = stack.copy();
        if (index < 64) {
            if (!DriveGuiLogic.visibleSlot(index, this.page) || !this.mergeItemStack(stack, 64, 100, true)) {
                return null;
            }
        } else {
            // Vanilla mergeItemStack ignores destination validation and a cell's one-item limit.
            insertCells(stack, this.page, this.inventorySlots);
            if (stack.stackSize == original.stackSize) {
                return null;
            }
        }
        if (stack.stackSize == 0) {
            slot.putStack(null);
        } else {
            slot.onSlotChanged();
        }
        slot.onPickupFromSlot(player, stack);
        return original;
    }

    /** Only empty cell slots receive one real stack; ItemStack.splitStack retains independent native cell NBT. */
    public static void insertCells(final ItemStack stack, final int group, final List<Slot> slots) {
        if (!DriveGuiLogic.validGroup(group)) {
            throw new IllegalArgumentException("Invalid cell page");
        }
        for (int offset = 0; offset < 8 && stack.stackSize > 0; offset++) {
            final Slot destination = slots.get(DriveSlotLayout.slot(group, offset));
            if (!destination.getHasStack() && destination.isItemValid(stack)) {
                destination.putStack(stack.splitStack(1));
            }
        }
    }

    @Override
    public List<ItemStack> getInventory() {
        final List<ItemStack> result = new ArrayList<>();
        for (int index = 0; index < this.inventorySlots.size(); index++) {
            result.add(
                index >= 64 || DriveGuiLogic.visibleSlot(index, this.page) ? this.getSlot(index)
                    .getStack() : null);
        }
        return result;
    }

    @Override
    public void detectAndSendChanges() {
        if (this.owner.worldObj.isRemote) {
            return;
        }
        if (!this.canUse()) {
            if (this.owner.openContainer == this && this.owner instanceof EntityPlayerMP) {
                ((EntityPlayerMP) this.owner).closeScreen();
            }
            return;
        }
        // Only compare visible cell NBT and the player inventory; send ordinary vanilla slot deltas.
        for (int index = 0; index < this.inventorySlots.size(); index++) {
            if (index < 64 && !DriveGuiLogic.visibleSlot(index, this.page)) {
                continue;
            }
            final ItemStack current = this.getSlot(index)
                .getStack();
            if (!ItemStack.areItemStacksEqual(current, this.inventoryItemStacks.get(index))) {
                final ItemStack copy = current == null ? null : current.copy();
                this.inventoryItemStacks.set(index, copy);
                for (final ICrafting listener : this.crafters) {
                    listener.sendSlotContents(this, index, copy);
                }
            }
        }
        if (this.syncTicks++ % 10 == 0) {
            this.sendState(false);
        }
    }

    public void handle(final DriveGuiAction action) {
        if (!this.canUse()) {
            return;
        }
        final long tick = this.owner.worldObj.getTotalWorldTime();
        if (this.actionTick != tick) {
            this.actionTick = tick;
            this.actionsThisTick = 0;
        }
        if (++this.actionsThisTick > 4) {
            return;
        }
        this.refreshConfig();
        this.feedback = DriveGuiLogic.apply(action, this);
        this.sendState(true);
    }

    public void handleClick(final DriveGuiNetwork.ClickMessage message) {
        if (!this.canUse() || !this.sessionId.equals(message.session)) {
            return;
        }
        final long tick = this.owner.worldObj.getTotalWorldTime();
        if (this.clickTick != tick) {
            this.clickTick = tick;
            this.clicksThisTick = 0;
        }
        if (++this.clicksThisTick > 64) {
            return;
        }
        final boolean valid = DriveGuiLogic
            .validView(message.pageRevision, this.pageRevision, message.sequence, this.lastClickSequence);
        this.lastClickSequence = Math.max(this.lastClickSequence, message.sequence);
        if (valid && message.configurationRevision == this.tile.getTransferConfigurationRevision()) {
            this.processingClick = true;
            try {
                this.slotClick(message.slot, message.button, message.mode, this.owner);
            } finally {
                this.processingClick = false;
            }
            this.detectAndSendChanges();
            ((EntityPlayerMP) this.owner).updateHeldItem();
        } else {
            this.func_94533_d();
            ((EntityPlayerMP) this.owner).sendContainerToPlayer(this);
            this.sendState(true);
        }
    }

    private NBTTagCompound configuration() {
        final NBTTagCompound config = new NBTTagCompound();
        config.setInteger("period", this.tile.getTransferPeriodSeconds());
        config.setInteger("storagePriority", this.tile.getPriority());
        config.setString(
            "access",
            this.tile.getAccessMode()
                .name());
        for (int group = 0; group < 8; group++) {
            final TargetRegistry.Route route = this.tile.getTransferGroupRoute(group);
            config.setInteger("fill" + group, this.tile.getTransferGroupFillPercent(group));
            config.setString(
                "route" + group,
                route.getMode()
                    .name());
            final NBTTagList selected = new NBTTagList();
            for (final UUID id : route.getTargetIds()) {
                selected.appendTag(new NBTTagString(id.toString()));
            }
            config.setTag("selected" + group, selected);
        }
        final List<TransferTarget> targets = new ArrayList<>(this.tile.getTransferTargets());
        targets.sort((first, second) -> Integer.compare(second.getPriority(), first.getPriority()));
        final NBTTagList list = new NBTTagList();
        for (final TransferTarget target : targets) {
            final NBTTagCompound entry = new NBTTagCompound();
            entry.setString(
                "id",
                target.getId()
                    .toString());
            entry.setString("name", target.getDisplayName());
            entry.setInteger("priority", target.getPriority());
            entry.setInteger("dimension", target.getDimension());
            entry.setInteger("x", target.getX());
            entry.setInteger("y", target.getY());
            entry.setInteger("z", target.getZ());
            list.appendTag(entry);
        }
        config.setTag("targets", list);
        return config;
    }

    private void refreshConfig() {
        final long revision = this.tile.getTransferConfigurationRevision();
        if (this.lastConfig == null || this.configRevision != revision) {
            this.lastConfig = this.configuration();
            this.configRevision = revision;
        }
    }

    private void sendState(final boolean force) {
        if (!(this.owner instanceof EntityPlayerMP) || !this.canUse()) {
            return;
        }
        this.refreshConfig();
        final NBTTagCompound state = (NBTTagCompound) this.lastConfig.copy();
        state.setString("session", this.sessionId.toString());
        state.setLong("revision", this.configRevision);
        state.setInteger("page", this.page);
        state.setLong("pageRevision", this.pageRevision);
        state.setBoolean(
            "connected",
            this.tile.getProxy()
                .isActive());
        state.setBoolean("locked", this.isLocked());
        state.setBoolean("recovery", this.tile.hasTransferRecovery());
        boolean configured = false;
        for (int group = 0; group < 8; group++) {
            final TargetRegistry.Route route = this.tile.getTransferGroupRoute(group);
            configured |= route.getMode() == TargetRegistry.Mode.AUTOMATIC
                || route.getMode() == TargetRegistry.Mode.SPECIFIC && !route.getTargetIds()
                    .isEmpty();
        }
        state.setBoolean(
            "configured",
            configured && !this.tile.getTransferTargets()
                .isEmpty());
        state.setString("status", this.tile.getTransferStatus());
        state.setString("feedback", this.feedback);
        final NBTTagList targets = state.getTagList("targets", 10);
        for (int index = 0; index < targets.tagCount(); index++) {
            final NBTTagCompound target = targets.getCompoundTagAt(index);
            target.setString(
                "status",
                this.tile.getTransferTargetStatus(UUID.fromString(target.getString("id")))
                    .name());
        }
        if (force || !state.equals(this.lastSent)) {
            DriveGuiNetwork.send((EntityPlayerMP) this.owner, this.windowId, state);
            this.lastSent = state;
        }
    }

    public NBTTagCompound getClientState() {
        return this.clientState;
    }

    public void receiveState(final NBTTagCompound state) {
        if (this.owner.worldObj.isRemote && DriveGuiLogic.validGroup(state.getInteger("page"))) {
            this.clientState = state;
            this.setPage(state.getInteger("page"));
        }
    }

    @Override
    public boolean hasTarget(final UUID id) {
        return id != null && this.tile.getTransferTargets()
            .stream()
            .anyMatch(
                target -> target.getId()
                    .equals(id));
    }

    @Override
    public TargetRegistry.Route getRoute(final int group) {
        return this.tile.getTransferGroupRoute(group);
    }

    @Override
    public void setRoute(final int group, final TargetRegistry.Route route) {
        this.tile.setTransferGroupRoute(group, route);
    }

    @Override
    public void setPeriod(final int seconds) {
        this.tile.setTransferPeriodSeconds(seconds);
    }

    @Override
    public void setAccess(final AccessRestriction access) {
        this.tile.setAccessMode(access);
    }

    @Override
    public void setStoragePriority(final int priority) {
        this.tile.setPriority(priority);
    }

    @Override
    public void setFillPercent(final int group, final int percent) {
        this.tile.setTransferGroupFillPercent(group, percent);
    }

    @Override
    public void updateTarget(final UUID id, final String name, final int priority) {
        this.tile.updateTransferTarget(id, name, priority);
    }

    @Override
    public void deleteTarget(final UUID id) {
        this.tile.removeTransferTarget(id);
    }

    private final class CellSlot extends Slot {

        private final int physicalSlot;

        private CellSlot(final int slot) {
            super(ContainerTransferDrive.this.cells, slot, 0, 0);
            this.physicalSlot = slot;
        }

        @Override
        public boolean isItemValid(final ItemStack stack) {
            return !isLocked() && DriveGuiLogic.visibleSlot(this.physicalSlot, page)
                && stack != null
                && AEApi.instance()
                    .registries()
                    .cell()
                    .isCellHandled(stack);
        }

        @Override
        public boolean canTakeStack(final EntityPlayer player) {
            return !isLocked() && DriveGuiLogic.visibleSlot(this.physicalSlot, page);
        }

        @Override
        public int getSlotStackLimit() {
            return 1;
        }
    }
}
