package com.kyou.gtnhmelogisticsenhancement.tile;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEStackType;
import appeng.api.storage.data.IItemList;
import appeng.util.IterationCounter;

/** Short-lived notification evidence from existing outer handlers; never an inventory or a handler cache. */
@SuppressWarnings({ "rawtypes", "unchecked" })
final class CellVisibilityChange {

    private final Map<IAEStackType<?>, Changes> types = new IdentityHashMap<>();

    void collect(final IMEInventoryHandler<?>[] handlers, final int sign) {
        for (final IMEInventoryHandler handler : handlers) {
            if (handler == null) {
                continue;
            }
            final IAEStackType type = handler.getStackType();
            final IItemList available = handler
                .getAvailableItems(type.createPrimitiveList(), IterationCounter.fetchNewId());
            for (final Object entry : available) {
                final IAEStack stack = (IAEStack) entry;
                if (stack.getStackType() != type || stack.getStackSize() < 0) {
                    throw new IllegalStateException("Invalid visible cell quantity");
                }
                if (stack.getStackSize() > 0) {
                    this.types.computeIfAbsent(type, Changes::new)
                        .add(stack, sign);
                }
            }
        }
    }

    Map<IAEStackType<?>, List<IAEStack<?>>> deltas() {
        final Map<IAEStackType<?>, List<IAEStack<?>>> result = new IdentityHashMap<>();
        for (final Map.Entry<IAEStackType<?>, Changes> entry : this.types.entrySet()) {
            final List<IAEStack<?>> delta = entry.getValue()
                .deltas();
            if (!delta.isEmpty()) {
                result.put(entry.getKey(), delta);
            }
        }
        return result;
    }

    private static final class Changes {

        private final IItemList identities;
        private final Map<IAEStack, BigInteger> amounts = new IdentityHashMap<>();

        Changes(final IAEStackType type) {
            this.identities = type.createPrimitiveList();
        }

        void add(final IAEStack stack, final int sign) {
            IAEStack identity = this.identities.findPrecise(stack);
            if (identity == null) {
                this.identities.add(
                    stack.copy()
                        .setStackSize(1)
                        .setCraftable(false));
                identity = this.identities.findPrecise(stack);
            }
            final BigInteger quantity = BigInteger.valueOf(stack.getStackSize())
                .multiply(BigInteger.valueOf(sign));
            this.amounts.merge(identity, quantity, BigInteger::add);
        }

        List<IAEStack<?>> deltas() {
            final List<IAEStack<?>> result = new ArrayList<>();
            for (final Map.Entry<IAEStack, BigInteger> entry : this.amounts.entrySet()) {
                BigInteger left = entry.getValue();
                // Several valid long-sized cells can exceed long in aggregate. Split only the notification,
                // not the inventory, instead of overflowing, clamping real stock or duplicating ordinary keys.
                while (left.signum() != 0) {
                    final long amount = left.abs()
                        .min(BigInteger.valueOf(Long.MAX_VALUE))
                        .longValueExact() * left.signum();
                    result.add(
                        entry.getKey()
                            .copy()
                            .setStackSize(amount)
                            .setCraftable(false));
                    left = left.subtract(BigInteger.valueOf(amount));
                }
            }
            return result;
        }
    }
}
