package com.kyou.gtnhmelogisticsenhancement.distributed;

import java.util.Objects;

import net.minecraft.item.ItemStack;

import gregtech.api.enums.OutputBusType;
import gregtech.api.interfaces.IOutputBus;
import gregtech.api.interfaces.IOutputBusTransaction;
import gregtech.api.util.GTUtility.ItemId;

/** Standard GT output endpoint for composition by the future generic output-bus subclass. */
public final class PartitionItemOutput implements IOutputBus {

    private final PartitionStore<ItemId> store;

    public PartitionItemOutput() {
        this(new PartitionStore<>(PartitionStore.ITEM_CAPACITY));
    }

    public PartitionItemOutput(final PartitionStore<ItemId> store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public PartitionStore<ItemId> store() {
        return this.store;
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
        Objects.requireNonNull(stack, "stack");
        final Transaction transaction = this.createTransaction();
        transaction.setRecipeCheck(simulate);
        final ItemStack offered = stack.copy();
        final boolean accepted = transaction.storePartial(identity(stack), offered, 0, 0);
        if (!simulate) transaction.commit();
        stack.stackSize = offered.stackSize;
        return accepted;
    }

    @Override
    public Transaction createTransaction() {
        return new Transaction();
    }

    private static ItemId identity(final ItemStack stack) {
        if (stack.getItem() == null) throw new IllegalArgumentException("Missing output item");
        return ItemId.create(stack.getItem(), stack.getItemDamage(), stack.getTagCompound());
    }

    public final class Transaction extends PartitionOutputTransaction<ItemId, ItemStack>
        implements IOutputBusTransaction {

        private Transaction() {
            super(PartitionItemOutput.this.store);
        }

        @Override
        public IOutputBus getBus() {
            return PartitionItemOutput.this;
        }

        @Override
        public void complete(final ItemId id) {
            super.complete(identity(id.getItemStack()));
        }

        @Override
        public boolean storePartial(final ItemId id, final ItemStack stack, final long totalPerParallel,
            final long perParallel) {
            this.requireActive();
            if (id == null || stack == null || !id.matches(stack)) {
                throw new IllegalArgumentException("GT item identity mismatch");
            }
            final boolean accepted = this.transaction.offer(identity(stack), stack.stackSize);
            if (accepted) stack.stackSize = 0;
            return accepted;
        }
    }
}
