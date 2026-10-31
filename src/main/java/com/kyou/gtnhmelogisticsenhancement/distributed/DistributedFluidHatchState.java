package com.kyou.gtnhmelogisticsenhancement.distributed;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;

import gregtech.api.util.GTUtility.FluidId;

/** Fluid quantities use Forge/GT units throughout, including the int-sized AE bridge. */
public final class DistributedFluidHatchState extends DistributedOutputState<FluidId> {

    private PartitionFluidOutput output;

    public DistributedFluidHatchState() {
        super(PartitionStore.FLUID_CAPACITY, DistributedIdentities.FLUIDS, FluidCellCapacityPolicy::inspect);
        this.output = new PartitionFluidOutput(this.store, this::filtered, this::accepts);
    }

    public PartitionFluidOutput output() {
        return this.output;
    }

    public static DistributedFluidHatchState read(final NBTBase raw, final ItemStack card) {
        final DistributedFluidHatchState state = new DistributedFluidHatchState();
        state.readState(raw, card);
        state.output = new PartitionFluidOutput(state.store, state::filtered, state::accepts);
        return state;
    }
}
