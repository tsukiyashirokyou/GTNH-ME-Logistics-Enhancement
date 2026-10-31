package com.kyou.gtnhmelogisticsenhancement.distributed;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import net.minecraftforge.fluids.FluidStack;

import gregtech.api.enums.OutputHatchType;
import gregtech.api.interfaces.IOutputHatch;
import gregtech.api.interfaces.IOutputHatchTransaction;
import gregtech.api.util.GTUtility.FluidId;

/** Standard GT output endpoint; fluid amounts keep GT/Forge's native litre unit. */
public final class PartitionFluidOutput implements IOutputHatch {

    private final PartitionStore<FluidId> store;
    private final BooleanSupplier filtered;
    private final Predicate<FluidId> accepts;

    public PartitionFluidOutput() {
        this(new PartitionStore<>(PartitionStore.FLUID_CAPACITY));
    }

    public PartitionFluidOutput(final PartitionStore<FluidId> store) {
        this(store, () -> false, identity -> false);
    }

    PartitionFluidOutput(final PartitionStore<FluidId> store, final BooleanSupplier filtered,
        final Predicate<FluidId> accepts) {
        this.store = Objects.requireNonNull(store, "store");
        this.filtered = Objects.requireNonNull(filtered, "filtered");
        this.accepts = Objects.requireNonNull(accepts, "accepts");
    }

    public PartitionStore<FluidId> store() {
        return this.store;
    }

    @Override
    public boolean isFiltered() {
        return this.filtered.getAsBoolean();
    }

    @Override
    public boolean isFilteredToFluid(final FluidId identity) {
        return this.accepts.test(identity);
    }

    @Override
    public OutputHatchType getHatchType() {
        return this.isFiltered() ? OutputHatchType.MECacheFiltered : OutputHatchType.MECacheUnfiltered;
    }

    @Override
    public boolean storePartial(final FluidStack stack, final boolean simulate) {
        Objects.requireNonNull(stack, "stack");
        final Transaction transaction = this.createTransaction();
        transaction.setRecipeCheck(simulate);
        final FluidStack offered = stack.copy();
        final boolean accepted = transaction.storePartial(FluidId.create(stack), offered, 0, 0);
        if (!simulate) transaction.commit();
        stack.amount = offered.amount;
        return accepted;
    }

    @Override
    public Transaction createTransaction() {
        return new Transaction();
    }

    public final class Transaction extends PartitionOutputTransaction<FluidId, FluidStack>
        implements IOutputHatchTransaction {

        private Transaction() {
            super(PartitionFluidOutput.this.store);
        }

        @Override
        public IOutputHatch getHatch() {
            return PartitionFluidOutput.this;
        }

        @Override
        public void complete(final FluidId id) {
            super.complete(FluidId.create(id.getFluidStack()));
        }

        @Override
        public boolean storePartial(final FluidId id, final FluidStack stack, final long totalPerParallel,
            final long perParallel) {
            this.requireActive();
            if (id == null || stack == null || !id.matches(stack)) {
                throw new IllegalArgumentException("GT fluid identity mismatch");
            }
            final boolean accepted = this.transaction.offer(FluidId.create(stack), stack.amount);
            if (accepted) stack.amount = 0;
            return accepted;
        }
    }
}
