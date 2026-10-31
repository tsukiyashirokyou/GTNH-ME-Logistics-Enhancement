package com.kyou.gtnhmelogisticsenhancement.distributed;

import static appeng.util.item.AEItemStackType.ITEM_STACK_TYPE;

import net.minecraft.item.ItemStack;

import appeng.api.config.IncludeExclude;
import appeng.api.storage.ICellInventory;
import appeng.api.storage.data.IAEItemStack;
import appeng.items.storage.ItemAdvancedStorageCell;
import appeng.items.storage.ItemBasicStorageCell;
import appeng.items.storage.ItemExtremeStorageCell;
import appeng.me.storage.CellInventory;
import appeng.me.storage.ItemCellInventoryHandler;
import appeng.util.item.AEItemStack;
import gregtech.api.util.GTUtility.ItemId;

/** Immutable event-built policy. Only the copied card ever reaches AE's mutable cell constructors. */
public final class CellCapacityPolicy implements DistributedOutputState.CapacityPolicy<ItemId> {

    private final ItemBasicStorageCell cell;
    private final ItemCellInventoryHandler handler;
    private final long bytes;
    private final int bytesPerType;
    private final long ceiling;
    private final int types;
    private final boolean valid;

    private CellCapacityPolicy(final ItemBasicStorageCell cell, final ItemCellInventoryHandler handler,
        final long bytes, final int bytesPerType, final long ceiling, final int types, final boolean valid) {
        this.cell = cell;
        this.handler = handler;
        this.bytes = bytes;
        this.bytesPerType = bytesPerType;
        this.ceiling = ceiling;
        this.types = types;
        this.valid = valid;
    }

    public static CellCapacityPolicy inspect(final ItemStack real) {
        if (real == null) return new CellCapacityPolicy(null, null, 0, 0, 1600, 16, true);
        try {
            // Unknown subclasses can redefine capacity, voiding and injection. They need a separate review.
            final Class<?> itemClass = real.getItem()
                .getClass();
            if (real.stackSize != 1
                || (itemClass != ItemBasicStorageCell.class && itemClass != ItemAdvancedStorageCell.class
                    && itemClass != ItemExtremeStorageCell.class))
                return invalid();
            final ItemStack copy = real.copy();
            final ItemBasicStorageCell item = (ItemBasicStorageCell) copy.getItem();
            final Object nativeHandler = CellInventory.getCell(copy, null, ITEM_STACK_TYPE);
            if (!(nativeHandler instanceof ItemCellInventoryHandler)) return invalid();
            final ItemCellInventoryHandler handler = (ItemCellInventoryHandler) nativeHandler;
            final ICellInventory<IAEItemStack> inventory = handler.getCellInv();
            long actualCount = 0;
            int actualTypes = 0;
            for (final IAEItemStack stack : handler
                .getAvailableItems(ITEM_STACK_TYPE.createPrimitiveList(), appeng.util.IterationCounter.fetchNewId())) {
                if (stack.getStackSize() <= 0) return invalid();
                actualCount = Math.addExact(actualCount, stack.getStackSize());
                actualTypes++;
            }
            final long bytes = inventory.getTotalBytes();
            final int perType = inventory.getBytesPerType();
            final long types = inventory.getTotalItemTypes();
            final long stored = inventory.getStoredItemCount();
            final long remaining = inventory.getRemainingItemCount();
            if (bytes <= 0 || bytes > Long.MAX_VALUE / ITEM_STACK_TYPE.getAmountPerByte()
                || perType < 0
                || types < 1
                || types > 63
                || stored < 0
                || remaining < 0
                || handler.isOverflow()
                || actualCount != stored
                || actualTypes != inventory.getStoredItemTypes()) return invalid();
            final long ceiling = Math.addExact(stored, remaining);
            return new CellCapacityPolicy(item, handler, bytes, perType, ceiling, (int) Math.min(16, types), true);
        } catch (RuntimeException invalidCard) {
            return invalid();
        }
    }

    private static CellCapacityPolicy invalid() {
        return new CellCapacityPolicy(null, null, 0, 0, 0, 0, false);
    }

    public boolean valid() {
        return this.valid;
    }

    public int types() {
        return this.types;
    }

    public long capacity(final int partitions) {
        if (partitions < 1 || partitions > 16) throw new IllegalArgumentException("Invalid partitions");
        if (this.handler == null) return this.ceiling;
        final long overhead = Math.multiplyExact((long) Math.min(partitions, this.types), this.bytesPerType);
        return Math.min(
            this.ceiling,
            Math.multiplyExact(Math.max(0, this.bytes - overhead), ITEM_STACK_TYPE.getAmountPerByte()));
    }

    @Override
    public boolean filtered() {
        return this.handler != null && this.handler.isPreformatted();
    }

    public boolean accepts(final ItemId identity) {
        if (!this.valid) return false;
        if (this.handler == null) return true;
        final IAEItemStack stack = AEItemStack.create(identity.getItemStack());
        if (stack == null || this.cell.isBlackListed(stack)) return false;
        // Use the native parsed fuzzy/ore/precise partition list, without asking the copied inventory for capacity.
        final boolean listed = this.handler.getPartitionList()
            .isListed(stack);
        if (this.handler.getIncludeExcludeMode() == IncludeExclude.BLACKLIST ? listed
            : !this.handler.getPartitionList()
                .isEmpty() && !listed)
            return false;
        if (stack.getItem() instanceof appeng.api.implementations.items.IStorageCell
            && !((appeng.api.implementations.items.IStorageCell) stack.getItem()).storableInStorageCell()) {
            // Native nested-cell probing can repair NBT: inspect a copy of the output identity as well.
            final ItemStack nested = identity.getItemStack()
                .copy();
            final appeng.api.implementations.items.IStorageCell nestedCell = (appeng.api.implementations.items.IStorageCell) nested
                .getItem();
            final Object nestedHandler = CellInventory.getCell(nested, null, nestedCell.getStackType());
            if (!(nestedHandler instanceof appeng.me.storage.CellInventoryHandler)
                || ((appeng.me.storage.CellInventoryHandler<?>) nestedHandler).getUsedBytes() > 0) return false;
        }
        return true;
    }
}
