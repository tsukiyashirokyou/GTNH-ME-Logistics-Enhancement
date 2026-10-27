package com.kyou.metransferdrive.transfer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Routes only the remainder of one source-cell type, one existing transaction per step. */
public final class RoutedTransfer<K, G> {

    private final K key;
    private final List<RoutingCycle.Target<G>> targets;
    private final BooleanSupplier topologyValid;
    private long remaining;
    private int next;
    private boolean isolated;

    public RoutedTransfer(final K key, final long amount, final RoutingCycle<G> cycle, final int group) {
        if (amount < 0) {
            throw new IllegalArgumentException("Negative routing quantity");
        }
        this.key = key;
        this.remaining = amount;
        this.targets = new ArrayList<>(cycle.targetsForGroup(group));
        this.topologyValid = cycle::isTopologyValid;
    }

    public long step(final long batch, final TransferTransaction<K> transaction,
        final TransferTransaction.Source source,
        final Function<RoutingCycle.Target<G>, TransferTransaction.Destination> destination,
        final TransferTransaction.Energy energy) {
        if (transaction.isPending() || !this.topologyValid.getAsBoolean()) {
            this.isolated = true;
        }
        if (this.isolated || this.isFinished() || batch <= 0) {
            return 0;
        }
        final RoutingCycle.Target<G> target = this.targets.get(this.next);
        final long limit = Math.min(batch, this.remaining);
        final Reception reception = new Reception(destination.apply(target));
        final long delivered = transaction.move(this.key, limit, source, reception, energy);
        this.remaining -= delivered;
        if (transaction.isPending() || !this.topologyValid.getAsBoolean()) {
            this.isolated = true;
        } else if (reception.simulated > 0 && reception.offered < 0) {
            // No energy or no actual source contents: a lower priority destination cannot fix this.
            this.next = this.targets.size();
        } else if (reception.simulated == 0 || reception.received < reception.offered
            || reception.offered == reception.simulated && reception.simulated < limit) {
                // Known rejection/partial reception, fully returned to source: advance exactly once.
                this.next++;
            }
        return delivered;
    }

    public boolean isFinished() {
        return !this.isolated && (this.remaining == 0 || this.next >= this.targets.size());
    }

    public long getRemaining() {
        return this.remaining;
    }

    /** Observe the existing transaction; distinguish receiver limits from energy/extraction limits. */
    private static final class Reception implements TransferTransaction.Destination {

        private final TransferTransaction.Destination delegate;
        private long simulated;
        private long offered = -1;
        private long received;

        Reception(final TransferTransaction.Destination delegate) {
            this.delegate = delegate;
        }

        @Override
        public long simulate(final long amount) {
            this.simulated = this.delegate.simulate(amount);
            return this.simulated;
        }

        @Override
        public long insert(final long amount) {
            this.offered = amount;
            this.received = this.delegate.insert(amount);
            return this.received;
        }
    }

}
