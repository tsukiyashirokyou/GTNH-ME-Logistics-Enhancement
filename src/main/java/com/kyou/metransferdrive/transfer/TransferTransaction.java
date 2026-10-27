package com.kyou.metransferdrive.transfer;

/** One bounded transaction. Uncertain calls are never replayed or compensated automatically. */
public final class TransferTransaction<K> {

    public enum Phase {
        IDLE,
        EXTRACTING,
        HELD,
        INSERTING,
        RETURNING,
        RECOVERABLE,
        UNCERTAIN
    }

    public interface Source {

        long extract(long amount);

        long restore(long amount);
    }

    public interface Destination {

        long simulate(long amount);

        long insert(long amount);
    }

    public interface Energy {

        long reserve(long amount);
    }

    private final Runnable checkpoint;
    private Phase phase = Phase.IDLE;
    private Phase uncertainOperation = Phase.IDLE;
    private K key;
    private long requested;
    private long held;
    private long delivered;
    private long reportedAmount;
    private String failureDescription = "";

    public TransferTransaction(final Runnable checkpoint) {
        this.checkpoint = checkpoint;
    }

    public long move(final K identity, final long limit, final Source source, final Destination destination,
        final Energy energy) {
        if (this.isPending() || limit <= 0) {
            return 0;
        }
        // Simulation and energy reservation precede extraction. Neither authorizes claiming delivery.
        final long simulated = checked(destination.simulate(limit), limit);
        final long authorized = checked(energy.reserve(simulated), simulated);
        if (authorized == 0) {
            return 0;
        }

        this.key = identity;
        this.requested = authorized;
        this.held = 0;
        this.delivered = 0;
        this.reportedAmount = 0;
        this.uncertainOperation = Phase.IDLE;
        this.failureDescription = "";
        try {
            this.enter(Phase.EXTRACTING);
            this.reportedAmount = source.extract(authorized);
            this.held = checked(this.reportedAmount, authorized);
            this.enter(Phase.HELD);
            if (this.held == 0) {
                this.clear();
                return 0;
            }
            this.enter(Phase.INSERTING);
            this.reportedAmount = destination.insert(this.held);
            this.delivered = checked(this.reportedAmount, this.held);
            this.held -= this.delivered;
            this.enter(Phase.HELD);
            this.returnRemainder(source);
        } catch (final RuntimeException failure) {
            this.recordFailure(failure);
            this.uncertainOperation = this.phase;
            this.phase = Phase.UNCERTAIN;
            this.checkpoint.run();
        }
        return this.delivered;
    }

    /** Only a known remainder may be retried, and only back to its original cell. */
    public void retryReturn(final Source source) {
        if (this.phase != Phase.RECOVERABLE) {
            return;
        }
        try {
            this.returnRemainder(source);
        } catch (final RuntimeException failure) {
            this.recordFailure(failure);
            this.uncertainOperation = this.phase;
            this.phase = Phase.UNCERTAIN;
            this.checkpoint.run();
        }
    }

    private void returnRemainder(final Source source) {
        if (this.held > 0) {
            this.enter(Phase.RETURNING);
            this.reportedAmount = source.restore(this.held);
            this.held -= checked(this.reportedAmount, this.held);
        }
        if (this.held > 0) {
            this.enter(Phase.RECOVERABLE);
        } else {
            this.clear();
        }
    }

    /** A chunk NBT snapshot is not an atomic commit with another grid's chunks. Never replay it. */
    public void quarantine(final K identity, final Phase interrupted, final long amount, final long remaining,
        final long accepted) {
        this.key = identity;
        this.requested = amount;
        this.held = remaining;
        this.delivered = accepted;
        this.uncertainOperation = interrupted;
        this.phase = Phase.UNCERTAIN;
    }

    private void enter(final Phase next) {
        this.phase = next;
        this.checkpoint.run();
    }

    private void clear() {
        this.phase = Phase.IDLE;
        // Keep the identity until the checkpoint succeeds, even if the persistence callback throws.
        this.checkpoint.run();
        this.key = null;
        this.held = 0;
        this.requested = 0;
        // Preserve delivered until the caller accounts for this operation.
    }

    private static long checked(final long amount, final long maximum) {
        if (amount < 0 || amount > maximum) {
            throw new IllegalStateException("Inventory returned an invalid transfer amount");
        }
        return amount;
    }

    public boolean isPending() {
        return this.phase != Phase.IDLE;
    }

    public Phase getPhase() {
        return this.phase;
    }

    public Phase getUncertainOperation() {
        return this.uncertainOperation;
    }

    public K getKey() {
        return this.key;
    }

    public long getRequested() {
        return this.requested;
    }

    public long getHeld() {
        return this.held;
    }

    public long getDelivered() {
        return this.delivered;
    }

    public long getReportedAmount() {
        return this.reportedAmount;
    }

    private void recordFailure(final RuntimeException failure) {
        final String text = failure.getClass()
            .getName() + ": "
            + String.valueOf(failure.getMessage());
        this.failureDescription = text.substring(0, Math.min(256, text.length()));
    }

    public String getFailureDescription() {
        return this.failureDescription;
    }
}
