package com.kyou.gtnhmelogisticsenhancement.distributed;

import static appeng.util.item.AEFluidStackType.FLUID_STACK_TYPE;

import net.minecraft.item.ItemStack;

import appeng.api.config.IncludeExclude;
import appeng.api.implementations.items.IStorageCell;
import appeng.api.storage.ICellInventory;
import appeng.api.storage.data.IAEFluidStack;
import appeng.me.storage.CellInventory;
import appeng.me.storage.FluidCellInventoryHandler;
import appeng.util.item.AEFluidStack;
import gregtech.api.util.GTUtility.FluidId;

/** Pinned AE2FC 1.5.113 native finite fluid cells, probed exclusively on a defensive copy. */
public final class FluidCellCapacityPolicy implements DistributedOutputState.CapacityPolicy<FluidId> {

    private final IStorageCell cell;
    private final FluidCellInventoryHandler handler;
    private final long bytes;
    private final int perType;
    private final long ceiling;
    private final int types;
    private final boolean valid;

    private FluidCellCapacityPolicy(final IStorageCell cell, final FluidCellInventoryHandler handler, final long bytes,
        final int perType, final long ceiling, final int types, final boolean valid) {
        this.cell = cell;
        this.handler = handler;
        this.bytes = bytes;
        this.perType = perType;
        this.ceiling = ceiling;
        this.types = types;
        this.valid = valid;
    }

    public static FluidCellCapacityPolicy inspect(final ItemStack real) {
        if (real == null) return new FluidCellCapacityPolicy(null, null, 0, 0, 128000, 16, true);
        try {
            // Exact reviewed classes only. No AE2FC linkage when it is absent; no lookalike subclasses.
            final Class<?> actual = real.getItem()
                .getClass();
            if (real.stackSize != 1 || !(real.getItem() instanceof IStorageCell) || !reviewed(actual)) return invalid();
            final ItemStack copy = real.copy();
            final IStorageCell cell = (IStorageCell) copy.getItem();
            if (cell.getStackType() != FLUID_STACK_TYPE || !cell.isStorageCell(copy)) return invalid();
            final Object nativeHandler = CellInventory.getCell(copy, null, FLUID_STACK_TYPE);
            if (!(nativeHandler instanceof FluidCellInventoryHandler)) return invalid();
            // The pinned native ORE_FILTER branch is empty for fluids and skips precise config parsing.
            // On the defensive copy only, route that branch through the native precise-list builder instead.
            final FluidCellInventoryHandler handler = new FluidCellInventoryHandler(
                ((FluidCellInventoryHandler) nativeHandler).getCellInv()) {

                @Override
                protected void setOreFilteredList(final String ignored) {
                    this.setPriorityList(
                        false,
                        this.getCellInv()
                            .getConfigAEInventory(),
                        this.getCellInv()
                            .getFuzzyMode());
                }
            };
            final ICellInventory<IAEFluidStack> inventory = handler.getCellInv();
            long count = 0;
            int types = 0;
            for (final IAEFluidStack stack : handler
                .getAvailableItems(FLUID_STACK_TYPE.createPrimitiveList(), appeng.util.IterationCounter.fetchNewId())) {
                if (stack.getStackSize() <= 0) return invalid();
                count = Math.addExact(count, stack.getStackSize());
                types++;
            }
            final long bytes = inventory.getTotalBytes();
            final int perType = inventory.getBytesPerType();
            final long maximumTypes = inventory.getTotalItemTypes();
            final long nativeTypes = ((CellInventory<?>) inventory).getMaxTypes();
            final long stored = inventory.getStoredItemCount();
            final long remaining = inventory.getRemainingItemCount();
            if (bytes <= 0 || bytes > Long.MAX_VALUE / FLUID_STACK_TYPE.getAmountPerByte()
                || perType < 0
                || maximumTypes < 1
                || maximumTypes > 63
                || maximumTypes > nativeTypes
                || stored < 0
                || remaining < 0
                || handler.isOverflow()
                || count != stored
                || types != inventory.getStoredItemTypes()) return invalid();
            return new FluidCellCapacityPolicy(
                cell,
                handler,
                bytes,
                perType,
                Math.addExact(stored, remaining),
                (int) Math.min(16, maximumTypes),
                true);
        } catch (RuntimeException invalid) {
            return invalid();
        }
    }

    private static boolean reviewed(final Class<?> actual) {
        for (final String name : new String[] { "com.glodblock.github.common.item.ItemBasicFluidStorageCell",
            "com.glodblock.github.common.item.ItemMultiFluidStorageCell",
            "com.glodblock.github.common.item.ItemFluidExtremeStorageCell" }) {
            try {
                if (actual == Class.forName(name, false, FluidCellCapacityPolicy.class.getClassLoader())) return true;
            } catch (ClassNotFoundException absent) {
                return false;
            }
        }
        return false;
    }

    private static FluidCellCapacityPolicy invalid() {
        return new FluidCellCapacityPolicy(null, null, 0, 0, 0, 0, false);
    }

    @Override
    public boolean valid() {
        return this.valid;
    }

    @Override
    public int types() {
        return this.types;
    }

    @Override
    public long capacity(final int partitions) {
        if (partitions < 1 || partitions > 16) throw new IllegalArgumentException("Invalid partitions");
        if (this.handler == null) return this.ceiling;
        final long overhead = Math.multiplyExact((long) Math.min(partitions, this.types), this.perType);
        return Math.min(
            this.ceiling,
            Math.multiplyExact(Math.max(0, this.bytes - overhead), FLUID_STACK_TYPE.getAmountPerByte()));
    }

    @Override
    public boolean accepts(final FluidId identity) {
        if (!this.valid) return false;
        if (this.handler == null) return true;
        final IAEFluidStack stack = AEFluidStack.create(identity.getFluidStack());
        if (stack == null || this.cell.isBlackListed(stack)) return false;
        final boolean listed = this.handler.getPartitionList()
            .isListed(stack);
        return this.handler.getIncludeExcludeMode() == IncludeExclude.BLACKLIST ? !listed
            : this.handler.getPartitionList()
                .isEmpty() || listed;
    }
}
