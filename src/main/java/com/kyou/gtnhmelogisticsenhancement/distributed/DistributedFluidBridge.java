package com.kyou.gtnhmelogisticsenhancement.distributed;

import appeng.api.storage.data.IAEFluidStack;
import appeng.util.item.AEFluidStack;
import gregtech.api.util.GTUtility.FluidId;

/** Verify the native AE representation before any external side effect, including NBT identity. */
final class DistributedFluidBridge {

    private DistributedFluidBridge() {}

    static IAEFluidStack offer(final FluidId identity, final int amount) {
        if (amount <= 0) throw new IllegalArgumentException("Invalid fluid offer");
        final IAEFluidStack offered = AEFluidStack.create(identity.getFluidStack())
            .setStackSize(amount);
        // Fixed AE2's constructor drops FluidStack.tag. Accept only a lossless runtime round trip.
        return identity.equals(FluidId.create(offered.getFluidStack())) ? offered : null;
    }
}
