package com.kyou.gtnhmelogisticsenhancement.distributed;

import static appeng.util.item.AEItemStackType.ITEM_STACK_TYPE;

import appeng.api.config.Actionable;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.data.IAEItemStack;
import appeng.me.storage.MEPassThrough;

/** Validate raw native-monitor receipts before poweredInsert can normalize/mask an invalid remainder. */
final class CheckedBusDestination extends MEPassThrough<IAEItemStack> {

    CheckedBusDestination(final IMEInventory<IAEItemStack> inventory) {
        super(inventory, ITEM_STACK_TYPE);
    }

    @Override
    public IAEItemStack injectItems(final IAEItemStack input, final Actionable mode, final BaseActionSource source) {
        final IAEItemStack identity = input.copy()
            .setStackSize(1);
        final long requested = input.getStackSize();
        final IAEItemStack remainder = this.getInternal()
            .injectItems(input, mode, source);
        if (remainder != null && (!identity.isSameType(remainder) || remainder.getStackSize() < 0
            || remainder.getStackSize() > requested))
            throw new IllegalStateException("Invalid native monitor remainder");
        return remainder;
    }
}
