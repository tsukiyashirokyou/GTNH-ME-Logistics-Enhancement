package com.kyou.gtnhmelogisticsenhancement.transfer;

import java.util.function.Supplier;

import appeng.api.config.Actionable;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEStack;
import appeng.util.IterationCounter;

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
    private final int fillPercent;

    GridStorageDestination(final IAEStack<?> identity, final Object source, final BaseActionSource actionSource,
        final Supplier<IMEMonitor<?>> monitor, final ResultQuantity quantity) {
        this(identity, source, actionSource, monitor, quantity, TransferFillLimit.DEFAULT_PERCENT);
    }

    GridStorageDestination(final IAEStack<?> identity, final Object source, final BaseActionSource actionSource,
        final Supplier<IMEMonitor<?>> monitor, final ResultQuantity quantity, final int fillPercent) {
        this.identity = identity;
        this.source = source;
        this.actionSource = actionSource;
        this.monitor = monitor;
        this.quantity = quantity;
        this.fillPercent = TransferFillLimit.validate(fillPercent);
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
        if (this.fillPercent == 0) return 0;
        final IMEMonitor destination = this.monitor.get();
        if (destination == null) {
            return 0;
        }
        return TransferInjectionGuard.call(this.source, () -> {
            long permitted = amount;
            if (this.fillPercent < 100) {
                // External adapters bridge int stacks. A saturated simulation is a conservative capacity lower
                // bound; never assume a larger unsafe probe is supported or that INT_MAX is the real capacity.
                final IAEStack probe = this.identity.copy()
                    .setStackSize(TransferEnergy.MAX_AMOUNT)
                    .setCraftable(false);
                final long remaining = TransferEnergy.MAX_AMOUNT - this.quantity.amount(
                    destination.injectItems(probe, Actionable.SIMULATE, this.actionSource),
                    TransferEnergy.MAX_AMOUNT);
                if (remaining == 0 || this.monitor.get() != destination) return 0L;
                // AE optimizes this exact-identity query, including deduplication for nested networks. Do not
                // refresh the monitor's full storage list or introduce a second inventory cache.
                final IMEInventory network = destination.getExternalNetworkInventory();
                final IMEInventory inventory = network == null ? destination : network;
                final IAEStack stored = inventory.getAvailableItem(
                    this.identity.copy()
                        .setStackSize(1),
                    IterationCounter.fetchNewId());
                permitted = Math.min(
                    amount,
                    TransferFillLimit
                        .allowance(this.quantity.amount(stored, Long.MAX_VALUE), remaining, this.fillPercent));
                if (permitted == 0 || this.monitor.get() != destination) return 0L;
                if (mode == Actionable.SIMULATE) return permitted;
            }
            final IAEStack<?> input = this.identity.copy()
                .setStackSize(permitted)
                .setCraftable(false);
            final IAEStack<?> leftover = destination.injectItems(input, mode, this.actionSource);
            return permitted - this.quantity.amount(leftover, permitted);
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
