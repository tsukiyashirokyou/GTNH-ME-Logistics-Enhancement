package com.kyou.gtnhmelogisticsenhancement.distributed;

import java.util.function.BooleanSupplier;

import appeng.api.config.Actionable;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEStackType;
import appeng.me.storage.MEPassThrough;

/** Validate raw receipts before poweredInsert normalizes them. */
final class CheckedStorageDestination<T extends IAEStack<T>> extends MEPassThrough<T> {

    private final BooleanSupplier valid;

    CheckedStorageDestination(final IMEInventory<T> inventory, final IAEStackType<T> type) {
        this(inventory, type, () -> true);
    }

    CheckedStorageDestination(final IMEInventory<T> inventory, final IAEStackType<T> type,
        final BooleanSupplier valid) {
        super(inventory, type);
        this.valid = valid;
    }

    @Override
    public T injectItems(final T input, final Actionable mode, final BaseActionSource source) {
        if (!this.valid.getAsBoolean()) return input;
        final T identity = input.copy()
            .setStackSize(1);
        final long requested = input.getStackSize();
        final T remainder = this.getInternal()
            .injectItems(input, mode, source);
        if (remainder != null && (remainder.getStackSize() < 0 || remainder.getStackSize() > requested
            || !identity.equals(
                remainder.copy()
                    .setStackSize(1))))
            throw new IllegalStateException("Invalid native monitor remainder");
        return remainder;
    }
}
