package com.kyou.metransferdrive.transfer;

import java.util.function.Supplier;

import appeng.api.config.Actionable;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEStack;

/** Injects into the grid's current monitor, leaving backend selection and notifications to AE2. */
final class GridStorageDestination implements TransferTransaction.Destination {

    interface ResultQuantity {

        long amount(IAEStack<?> result, long maximum);
    }

    private final IAEStack<?> identity;
    private final Object source;
    private final BaseActionSource actionSource;
    private final Supplier<IMEMonitor<?>> monitor;
    private final ResultQuantity quantity;

    GridStorageDestination(final IAEStack<?> identity, final Object source, final BaseActionSource actionSource,
        final Supplier<IMEMonitor<?>> monitor, final ResultQuantity quantity) {
        this.identity = identity;
        this.source = source;
        this.actionSource = actionSource;
        this.monitor = monitor;
        this.quantity = quantity;
    }

    @Override
    public long simulate(final long amount) {
        return this.inject(amount, Actionable.SIMULATE);
    }

    @Override
    public long insert(final long amount) {
        return this.inject(amount, Actionable.MODULATE);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private long inject(final long amount, final Actionable mode) {
        final IMEMonitor destination = this.monitor.get();
        if (destination == null) {
            return 0;
        }
        return TransferInjectionGuard.call(this.source, () -> {
            final IAEStack<?> input = this.identity.copy()
                .setStackSize(amount)
                .setCraftable(false);
            final IAEStack<?> leftover = destination.injectItems(input, mode, this.actionSource);
            return amount - this.quantity.amount(leftover, amount);
        });
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    static long checkedQuantity(final IAEStack<?> identity, final IAEStack<?> result, final long maximum) {
        if (result == null) {
            return 0;
        }
        if (identity.getStackType() != result.getStackType() || !identity.isSameType((IAEStack) result)
            || result.getStackSize() < 0
            || result.getStackSize() > maximum) {
            throw new IllegalStateException("AE handler returned an unexpected type or amount");
        }
        return result.getStackSize();
    }
}
