package com.kyou.gtnhmelogisticsenhancement.energy;

import java.util.Arrays;
import java.util.Objects;

/**
 * Reusable, sliceable weighted plan for one voltage group. No TE references or EU writes.
 * Target credit is persistent runtime scheduling state; reservations belong only to this plan.
 */
public final class MEEnergyEpochPlan {

    public static final int MAX_PERIOD = 20;
    public static final int DEADLINE_SLACK = 3;
    private static final int MAINTENANCE = 0, PRECHARGE = 1, FINISHED = 2;

    private final MEEnergyGroupPlanner graph = new MEEnergyGroupPlanner();
    private Target[] targets = new Target[8];
    private int[] colors = new int[8];
    private long[] free = new long[8];
    private long[] quotas = new long[8];
    private long[] maintenance = new long[8];
    private int[] maintenanceTargets = new int[8];
    private int[] prechargeNext = new int[8];
    private int[] prechargePrevious = new int[8];
    private int[] bonusTargets = new int[8];
    private int[] pendingNext = new int[8];
    private int[] pendingPrevious = new int[8];
    private int[] maintenancePendingNext = new int[8];
    private int[] maintenancePendingPrevious = new int[8];
    private int pendingHead = -1;
    private int pendingTail = -1;
    private int maintenancePendingHead = -1;
    private int maintenancePendingTail = -1;
    private int targetCount;
    private int nextStart;
    private int start;
    private int period;
    private int round;
    private int offset;
    private int bonusCount;
    private int bonusOffset;
    private int bonusRetained;
    private int phase;
    private int maintenanceCount;
    private int maintenanceStart;
    private int prechargeHead = -1;
    private int prechargeTail = -1;
    private int prechargeCursor = -1;
    private int prechargeCount;
    private int prechargePrimaryRemaining;
    private long epoch = -1;
    private long epochStartTick;
    private long lastRecordTick;
    private boolean collecting;
    private boolean solving;
    private boolean ready;
    private boolean cancelling;
    private int cancelIndex;

    public void begin(final long newEpoch, final long now, final int periodTicks) {
        if (newEpoch < 0 || newEpoch < this.epoch
            || now < 0
            || now > Long.MAX_VALUE - DEADLINE_SLACK
            || periodTicks < 1
            || periodTicks > MAX_PERIOD) {
            throw new IllegalArgumentException("Invalid epoch, tick or period");
        }
        if (this.epoch >= 0 && now < this.lastRecordTick) {
            throw new IllegalArgumentException("Game tick moved backwards");
        }
        if (newEpoch == this.epoch && (periodTicks != this.period || now > deadlineTick())) {
            throw new IllegalArgumentException("A replan cannot change or extend its epoch");
        }
        cancel();
        if (newEpoch != this.epoch) this.epochStartTick = now;
        this.epoch = newEpoch;
        this.lastRecordTick = now;
        this.period = periodTicks;
        this.graph.reset();
        this.collecting = true;
    }

    public void addSource(final int color, final long net) {
        checkCollecting();
        this.graph.addSource(color, net);
    }

    /** Add active targets only. Full targets clear credit rather than saving idle entitlement. */
    public void addTarget(final Target target, final int color, final long freeSpace) {
        addTarget(target, color, freeSpace, 0);
    }

    /** Observed running demand is served before spare capacity is used for ordinary weighted precharging. */
    public void addTarget(final Target target, final int color, final long freeSpace, final long maintenanceNeed) {
        checkCollecting();
        Objects.requireNonNull(target, "target");
        if (color < 0 || color >= MEEnergyGroupPlanner.COLOR_COUNT
            || freeSpace < 0
            || freeSpace > target.capacity
            || maintenanceNeed < 0
            || maintenanceNeed > freeSpace
            || target.owner != null
            || target.grantEpoch > this.epoch) {
            throw new IllegalArgumentException("Invalid, stale or already owned target");
        }
        ensureCapacity();
        this.graph.noteDemand(color, freeSpace);
        this.targets[this.targetCount] = target;
        this.colors[this.targetCount] = color;
        this.free[this.targetCount] = freeSpace;
        this.quotas[this.targetCount] = 0;
        this.maintenance[this.targetCount] = maintenanceNeed;
        if (maintenanceNeed > 0) this.maintenanceTargets[this.maintenanceCount++] = this.targetCount;
        if (freeSpace > 0) {
            this.prechargePrevious[this.targetCount] = this.prechargeTail;
            this.prechargeNext[this.targetCount] = -1;
            if (this.prechargeTail < 0) this.prechargeHead = this.targetCount;
            else this.prechargeNext[this.prechargeTail] = this.targetCount;
            this.prechargeTail = this.targetCount;
            this.prechargeCount++;
        } else this.prechargeNext[this.targetCount] = this.prechargePrevious[this.targetCount] = -1;
        this.pendingNext[this.targetCount] = this.pendingPrevious[this.targetCount] = -1;
        this.maintenancePendingNext[this.targetCount] = this.maintenancePendingPrevious[this.targetCount] = -1;
        this.targetCount++;
        target.credit = Math.min(target.credit, freeSpace);
        target.owner = this;
    }

    /** Convenience for pure tests/small groups; live integration can call planNext with a visit budget. */
    public void plan() {
        while (!planNext(Integer.MAX_VALUE)) {
            // Each visit advances one target; both weighted phases have a fixed, bounded pass count.
        }
    }

    /** A slice counts target visits; each color-pool operation is constant work on the long path. */
    public boolean planNext(final int targetVisitBudget) {
        if (targetVisitBudget <= 0) throw new IllegalArgumentException("Visit budget must be positive");
        if (this.ready) return true;
        if (this.collecting) {
            this.graph.sealSources();
            this.collecting = false;
            this.solving = true;
            this.start = this.targetCount == 0 ? 0 : this.nextStart % this.targetCount;
            this.round = this.offset = 0;
            this.bonusCount = this.bonusOffset = this.bonusRetained = 0;
            this.phase = this.maintenanceCount == 0 ? PRECHARGE : MAINTENANCE;
            if (this.prechargeCount > 0) {
                this.prechargeNext[this.prechargeTail] = this.prechargeHead;
                this.prechargePrevious[this.prechargeHead] = this.prechargeTail;
                this.prechargeCursor = this.start;
                while (this.free[this.prechargeCursor] == 0)
                    this.prechargeCursor = (this.prechargeCursor + 1) % this.targetCount;
            }
            this.prechargePrimaryRemaining = this.prechargeCount;
            int low = 0, high = this.maintenanceCount;
            while (low < high) {
                final int middle = low + (high - low) / 2;
                if (this.maintenanceTargets[middle] < this.start) low = middle + 1;
                else high = middle;
            }
            this.maintenanceStart = low;
            if (this.maintenanceStart == this.maintenanceCount) this.maintenanceStart = 0;
        } else if (!this.solving) {
            throw new IllegalStateException("No epoch is being planned");
        }
        final int rounds = (MEEnergySpec.BUFFER_TICKS + this.period - 1) / this.period;
        int visits = 0;
        advancePhase(rounds);
        while (this.phase != FINISHED && visits < targetVisitBudget) {
            final int index;
            if (this.round > 0) index = this.bonusTargets[this.bonusOffset];
            else if (this.phase == MAINTENANCE) {
                index = this.maintenanceTargets[(this.maintenanceStart + this.offset) % this.maintenanceCount];
            } else {
                index = this.prechargeCursor;
                this.prechargeCursor = this.prechargeNext[index];
            }
            final Target target = this.targets[index];
            final long remaining = phaseRemaining(index);
            final boolean reachable = remaining > 0 && (this.round == 0 || this.graph.canRouteTo(this.colors[index]));
            boolean exhausted = !reachable;
            if (reachable) {
                final long quantum = target.quantum(this.period);
                target.grant(this.epoch, this.round, quantum, this.free[index], this.phase == MAINTENANCE);
                final long spendable = target.credit - this.quotas[index];
                final boolean primaryProgress = this.round == 0
                    && (this.phase == MAINTENANCE || this.maintenance[index] == 0);
                final long allowance = primaryProgress ? quantum - target.serviceProgress : quantum;
                final long requested = Math.min(allowance, Math.min(remaining, spendable));
                if (requested > 0) {
                    final long granted = this.graph.request(this.colors[index], requested);
                    // A partial request proves this isolated pool is empty. Reuse that result rather
                    // than querying every target's pool again while building the next bonus pass.
                    exhausted = granted < requested;
                    if (granted > 0 && this.quotas[index] == 0) {
                        appendPending(index);
                        if (this.maintenance[index] > 0) appendMaintenancePending(index);
                    }
                    this.quotas[index] += granted;
                    if (this.quotas[index] == this.free[index]) removePrecharge(index);
                }
            }
            visits++;
            if (this.round == 0) {
                if (phaseRemaining(index) > 0 && !exhausted) this.bonusTargets[this.bonusCount++] = index;
                if (this.phase == MAINTENANCE) {
                    if (++this.offset == this.maintenanceCount) {
                        this.offset = 0;
                        this.round++;
                    }
                } else if (--this.prechargePrimaryRemaining == 0) {
                    this.round++;
                }
            } else {
                // Stable in-place compaction keeps the original rotated fairness order.
                if (phaseRemaining(index) > 0 && !exhausted) this.bonusTargets[this.bonusRetained++] = index;
                if (++this.bonusOffset == this.bonusCount) {
                    this.bonusCount = this.bonusRetained;
                    this.bonusOffset = this.bonusRetained = 0;
                    this.round++;
                }
            }
            advancePhase(rounds);
        }
        if (this.phase == FINISHED) {
            this.graph.freeze();
            this.solving = false;
            this.ready = true;
            this.nextStart = this.targetCount == 0 ? 0 : (this.start + 1) % this.targetCount;
        }
        return this.ready;
    }

    private long phaseRemaining(final int index) {
        return (this.phase == MAINTENANCE ? this.maintenance[index] : this.free[index]) - this.quotas[index];
    }

    private void advancePhase(final int rounds) {
        // An unfinished weighted quantum can reduce the primary allowance by almost one quantum.
        // One extra maintenance pass restores the full bounded C24 reserve before cold precharging.
        final int phaseRounds = rounds + (this.phase == MAINTENANCE ? 1 : 0);
        final boolean noPrimary = this.phase == PRECHARGE && this.round == 0 && this.prechargePrimaryRemaining == 0;
        final boolean noBonus = this.round > 0
            && (this.round == phaseRounds || this.bonusCount == 0 || !this.graph.hasRemainingSupply());
        if (!noPrimary && !noBonus) return;
        if (this.phase == MAINTENANCE) {
            // Even exhausted supply must not skip the cold targets' initial fair-credit grant.
            this.phase = PRECHARGE;
            this.round = this.offset = 0;
            this.bonusCount = this.bonusOffset = this.bonusRetained = 0;
            this.prechargePrimaryRemaining = this.prechargeCount;
            if (this.prechargeCount == 0) this.phase = FINISHED;
        } else this.phase = FINISHED;
    }

    private void removePrecharge(final int index) {
        final int before = this.prechargePrevious[index], after = this.prechargeNext[index];
        if (--this.prechargeCount == 0) this.prechargeCursor = -1;
        else {
            this.prechargeNext[before] = after;
            this.prechargePrevious[after] = before;
            if (this.prechargeCursor == index) this.prechargeCursor = after;
        }
        this.prechargePrevious[index] = this.prechargeNext[index] = -1;
    }

    public boolean isReady() {
        return this.ready;
    }

    public boolean isValid(final long expectedEpoch, final long now) {
        return this.ready && this.epoch == expectedEpoch && now >= this.lastRecordTick && now <= deadlineTick();
    }

    public long deadlineTick() {
        if (this.epoch < 0) throw new IllegalStateException("No epoch");
        return this.epochStartTick + DEADLINE_SLACK;
    }

    public int targetCount() {
        return this.targetCount;
    }

    public long quota(final int index) {
        checkReady();
        checkIndex(index);
        return this.quotas[index];
    }

    /** A running target's maintenance portion must finish before spare precharge quotas consume work slices. */
    public long executionLimit(final int index) {
        checkReady();
        checkIndex(index);
        return this.maintenance[index] > 0 ? Math.min(this.quotas[index], this.maintenance[index]) : this.quotas[index];
    }

    public long colorFlowLimit(final int sourceColor, final int targetColor, final long limit) {
        checkReady();
        return this.graph.flowLimit(sourceColor, targetColor, limit);
    }

    /** Rotates actual execution as well as planning; callers may split one quota into many commits. */
    public int nextTargetIndex() {
        checkReady();
        final int index = this.maintenancePendingHead >= 0 ? this.maintenancePendingHead : this.pendingHead;
        if (index >= 0) {
            if (this.maintenancePendingHead >= 0) {
                removeMaintenancePending(index);
                appendMaintenancePending(index);
            }
            removePending(index);
            appendPending(index);
        }
        return index;
    }

    public boolean canRecord(final long expectedEpoch, final int index, final int sourceColor, final long net,
        final long now) {
        if (!isValid(expectedEpoch, now)) return false;
        checkIndex(index);
        if (net <= 0 || net > this.quotas[index] || net > this.targets[index].credit) return false;
        return this.graph.flowLimit(sourceColor, this.colors[index], net) == net;
    }

    /** Record only after a real transfer succeeds; this method itself never sends or charges EU. */
    public void record(final long expectedEpoch, final int index, final int sourceColor, final long net,
        final long now) {
        if (!canRecord(expectedEpoch, index, sourceColor, net, now)) {
            throw new IllegalStateException("Stale or excessive planned transfer");
        }
        this.graph.consumeFlow(sourceColor, this.colors[index], net);
        this.quotas[index] -= net;
        if (this.quotas[index] == 0) removePending(index);
        if (this.maintenance[index] > 0) {
            this.maintenance[index] -= Math.min(this.maintenance[index], net);
            if (this.maintenance[index] == 0 || this.quotas[index] == 0) removeMaintenancePending(index);
        }
        final Target target = this.targets[index];
        target.credit -= net;
        final long quantum = target.quantum(this.period);
        final long remainder = net % quantum;
        final long untilComplete = quantum - target.serviceProgress;
        target.serviceProgress = remainder >= untilComplete ? remainder - untilComplete
            : target.serviceProgress + remainder;
        this.nextStart = target.serviceProgress == 0 ? (index + 1) % this.targetCount : index;
        target.lastServedTick = now;
        this.lastRecordTick = now;
    }

    /** Keeps earned fair credit but discards all reservations and references, including partial planning. */
    public void cancel() {
        startCancel();
        while (!cancelNext(Integer.MAX_VALUE)) {
            // Immediate convenience for pure tests, begin() after bounded cleanup and shutdown.
        }
    }

    /** Immediately revokes this plan; releasing individual target references can be spread across work slices. */
    public void startCancel() {
        if (this.cancelling) return;
        if (this.solving && this.targetCount > 0) {
            if (this.round > 0 && this.bonusCount > 0) this.nextStart = this.bonusTargets[this.bonusOffset];
            else if (this.phase == MAINTENANCE) {
                this.nextStart = this.maintenanceTargets[(this.maintenanceStart + this.offset) % this.maintenanceCount];
            } else this.nextStart = this.prechargeCursor < 0 ? this.start : this.prechargeCursor;
        } else if (this.maintenancePendingHead >= 0) this.nextStart = this.maintenancePendingHead;
        else if (this.pendingHead >= 0) this.nextStart = this.pendingHead;
        this.collecting = this.solving = this.ready = false;
        this.cancelling = true;
        this.cancelIndex = 0;
    }

    /** Clears at most visitBudget target records; repeated revocations never restart a cleanup in progress. */
    public boolean cancelNext(final int visitBudget) {
        if (visitBudget <= 0) throw new IllegalArgumentException("Visit budget must be positive");
        startCancel();
        int visits = 0;
        while (this.cancelIndex < this.targetCount && visits < visitBudget) {
            final int index = this.cancelIndex++;
            this.targets[index].owner = null;
            this.targets[index] = null;
            this.quotas[index] = this.free[index] = this.maintenance[index] = 0;
            visits++;
        }
        if (this.cancelIndex < this.targetCount) return false;
        this.targetCount = 0;
        this.maintenanceCount = this.prechargeCount = this.prechargePrimaryRemaining = 0;
        this.prechargeHead = this.prechargeTail = this.prechargeCursor = -1;
        this.bonusCount = this.bonusOffset = this.bonusRetained = 0;
        this.pendingHead = this.pendingTail = -1;
        this.maintenancePendingHead = this.maintenancePendingTail = -1;
        this.cancelling = false;
        this.cancelIndex = 0;
        return true;
    }

    /** A changed/full target releases its reservation without charging credit or pretending to send EU. */
    public void abandonTarget(final int index) {
        checkReady();
        checkIndex(index);
        if (this.quotas[index] > 0) {
            removePending(index);
            if (this.maintenance[index] > 0) removeMaintenancePending(index);
        }
        this.quotas[index] = 0;
    }

    public boolean usesWideArithmetic() {
        return this.graph.usesWideArithmetic();
    }

    public long augmentations() {
        return this.graph.augmentations();
    }

    public long edgeVisits() {
        return this.graph.edgeVisits();
    }

    private void appendPending(final int index) {
        this.pendingPrevious[index] = this.pendingTail;
        this.pendingNext[index] = -1;
        if (this.pendingTail < 0) this.pendingHead = index;
        else this.pendingNext[this.pendingTail] = index;
        this.pendingTail = index;
    }

    private void removePending(final int index) {
        final int before = this.pendingPrevious[index], after = this.pendingNext[index];
        if (before < 0) this.pendingHead = after;
        else this.pendingNext[before] = after;
        if (after < 0) this.pendingTail = before;
        else this.pendingPrevious[after] = before;
        this.pendingPrevious[index] = this.pendingNext[index] = -1;
    }

    private void appendMaintenancePending(final int index) {
        this.maintenancePendingPrevious[index] = this.maintenancePendingTail;
        this.maintenancePendingNext[index] = -1;
        if (this.maintenancePendingTail < 0) this.maintenancePendingHead = index;
        else this.maintenancePendingNext[this.maintenancePendingTail] = index;
        this.maintenancePendingTail = index;
    }

    private void removeMaintenancePending(final int index) {
        final int before = this.maintenancePendingPrevious[index], after = this.maintenancePendingNext[index];
        if (before < 0) this.maintenancePendingHead = after;
        else this.maintenancePendingNext[before] = after;
        if (after < 0) this.maintenancePendingTail = before;
        else this.maintenancePendingPrevious[after] = before;
        this.maintenancePendingPrevious[index] = this.maintenancePendingNext[index] = -1;
    }

    private void ensureCapacity() {
        if (this.targetCount < this.targets.length) return;
        final int length = Math.multiplyExact(this.targets.length, 2);
        this.targets = Arrays.copyOf(this.targets, length);
        this.colors = Arrays.copyOf(this.colors, length);
        this.free = Arrays.copyOf(this.free, length);
        this.quotas = Arrays.copyOf(this.quotas, length);
        this.maintenance = Arrays.copyOf(this.maintenance, length);
        this.maintenanceTargets = Arrays.copyOf(this.maintenanceTargets, length);
        this.prechargeNext = Arrays.copyOf(this.prechargeNext, length);
        this.prechargePrevious = Arrays.copyOf(this.prechargePrevious, length);
        this.bonusTargets = Arrays.copyOf(this.bonusTargets, length);
        this.pendingNext = Arrays.copyOf(this.pendingNext, length);
        this.pendingPrevious = Arrays.copyOf(this.pendingPrevious, length);
        this.maintenancePendingNext = Arrays.copyOf(this.maintenancePendingNext, length);
        this.maintenancePendingPrevious = Arrays.copyOf(this.maintenancePendingPrevious, length);
    }

    private void checkCollecting() {
        if (!this.collecting) throw new IllegalStateException("Input snapshot is not open");
    }

    private void checkReady() {
        if (!this.ready) throw new IllegalStateException("Plan has no final quotas");
    }

    private void checkIndex(final int index) {
        if (index < 0 || index >= this.targetCount) throw new IndexOutOfBoundsException("Unknown plan target");
    }

    /** One runtime fairness object per energy endpoint; reset on migration, never persist to NBT. */
    public static final class Target {

        private final long power;
        private final long capacity;
        private long credit;
        private long grantEpoch = -1;
        private int grantRound = -1;
        private int maintenanceGrantRound = -1;
        private long lastServedTick = -1;
        private MEEnergyEpochPlan owner;
        private long serviceQuantum;
        private long serviceProgress;

        public Target(final long power) {
            if (power <= 0) throw new IllegalArgumentException("Target power must be positive");
            this.power = power;
            this.capacity = Math.multiplyExact(power, MEEnergySpec.BUFFER_TICKS);
        }

        public long credit() {
            return this.credit;
        }

        public long capacity() {
            return this.capacity;
        }

        public long lastServedTick() {
            return this.lastServedTick;
        }

        public void clearCredit() {
            checkUnowned();
            this.credit = 0; // Preserve grant stamps so active/full toggles cannot reissue this epoch's credit.
            this.serviceProgress = 0;
        }

        public void reset() {
            checkUnowned();
            this.credit = 0;
            this.grantEpoch = this.lastServedTick = -1;
            this.grantRound = this.maintenanceGrantRound = -1;
            this.serviceQuantum = this.serviceProgress = 0;
        }

        private long quantum(final int period) {
            final long quantum = Math.multiplyExact(this.power, period);
            if (quantum != this.serviceQuantum) {
                this.serviceQuantum = quantum;
                this.serviceProgress = 0;
            }
            return quantum;
        }

        private void grant(final long epoch, final int round, final long quantum, final long freeSpace,
            final boolean maintenance) {
            if (epoch != this.grantEpoch) {
                this.grantEpoch = epoch;
                this.grantRound = this.maintenanceGrantRound = -1;
            }
            if (round <= (maintenance ? this.maintenanceGrantRound : this.grantRound)) return;
            this.credit += Math.min(quantum, Math.min(this.capacity, freeSpace) - this.credit);
            if (maintenance) this.maintenanceGrantRound = round;
            else this.grantRound = round;
        }

        private void checkUnowned() {
            if (this.owner != null) throw new IllegalStateException("Cancel the owning plan before changing fairness");
        }
    }
}
