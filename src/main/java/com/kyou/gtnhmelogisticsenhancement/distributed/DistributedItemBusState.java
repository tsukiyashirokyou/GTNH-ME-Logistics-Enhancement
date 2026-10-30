package com.kyou.gtnhmelogisticsenhancement.distributed;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;

import gregtech.api.util.GTUtility.ItemId;

/** Item specialization of the shared scheduling, persistence and uncertain-delivery state machine. */
public final class DistributedItemBusState extends DistributedOutputState<ItemId> {

    private PartitionItemOutput output;

    public DistributedItemBusState() {
        super(PartitionStore.ITEM_CAPACITY, DistributedIdentities.ITEMS, CellCapacityPolicy::inspect);
        this.output = new PartitionItemOutput(this.store);
    }

    public PartitionItemOutput output() {
        return this.output;
    }

    public static final class Remainder extends DistributedOutputState.Remainder<ItemId> {

        public Remainder(final ItemId identity, final long quantity) {
            super(identity, quantity);
        }
    }

    public static DistributedItemBusState read(final NBTBase raw, final ItemStack card) {
        final DistributedItemBusState state = new DistributedItemBusState();
        state.readState(raw, card);
        state.output = new PartitionItemOutput(state.store);
        return state;
    }
}
