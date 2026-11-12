package com.kyou.gtnhmelogisticsenhancement.energy;

import java.util.Arrays;

import com.kyou.gtnhmelogisticsenhancement.energy.MEEnergyGridCache.Member;
import com.kyou.gtnhmelogisticsenhancement.energy.MEEnergySpec.Role;

/** Reused per-tier snapshot/solve/execute state. One step has bounded work, no source x target search. */
final class MEEnergyGroupState {

    private static final int COLORS = MEEnergyGroupPlanner.COLOR_COUNT;
    private static final int CLEANUP_BATCH = 16;
    private static final int IDLE = 0, SNAPSHOT = 1, SOLVE = 2, EXECUTE = 3, CLEANUP = 4;
    private final MEEnergyGridCache cache;
    private final int tier;
    private final MEEnergyEpochPlan plan = new MEEnergyEpochPlan();
    private final Member[] resume = new Member[2 * COLORS];
    private final int[] heads = new int[COLORS];
    private final int[] tails = new int[COLORS];
    private final int[] sourceCounts = new int[COLORS];
    private final int[] rotations = new int[COLORS];
    private Member[] sources = new Member[8];
    private long[] remaining = new long[8];
    private int[] nextSource = new int[8];
    private Member[] targets = new Member[8];
    private int sourceCount, targetCount, scanBucket;
    private int scanRemaining, cleanupIndex;
    private Member scanNext, scanStart, scanTail;
    private boolean scanning;
    private int state;
    private long due = -1, generation, membershipEpoch;
    private long rounds, cancellations, expirations, work, maxWork, lastWorkTick = -1, tickWork;
    private long lastServiceTick = -1, maxServiceGap;
    private long emptySupplyRounds, emptyDemandRounds, unrouteableTargets;
    private boolean wide;

    MEEnergyGroupState(final MEEnergyGridCache cache, final int tier) {
        this.cache = cache;
        this.tier = tier;
    }

    boolean step(final long now) {
        final int period = this.cache.servicePeriod();
        if (this.state != IDLE && this.state != CLEANUP
            && (!this.cache.isEpochValid(this.tier, this.membershipEpoch) || now > this.plan.deadlineTick())) {
            if (now > this.plan.deadlineTick()) this.expirations++;
            this.cancellations++;
            cancel();
        }
        if (this.state == CLEANUP) {
            countWork(now);
            cleanupNext();
            return true;
        }
        if (this.state == IDLE) {
            if (this.cache.refreshPending(this.tier)) return false;
            if (this.due < 0) {
                final int stride = period == 10 ? 3 : 2;
                final int phase = (this.cache.phaseOffset() + (this.tier - MEEnergySpec.MIN_TIER) * stride) % period;
                this.due = now + (phase - now % period + period) % period;
            }
            if (now < this.due) return false;
            // Never wall-clock catch up or replay missed epochs after a delayed game tick.
            final long scheduled = this.due + ((now - this.due) / period) * period;
            this.due = Math.addExact(scheduled, period);
            if (!this.cache.hasBothRoles(this.tier) || !this.cache.hasDemand(this.tier)) return false;
            if (now - scheduled > MEEnergyEpochPlan.DEADLINE_SLACK) {
                this.expirations++;
                return false;
            }
            this.membershipEpoch = this.cache.epoch(this.tier);
            // Waiting for the shared budget cannot extend a scheduled epoch's original deadline.
            this.plan.begin(++this.generation, scheduled, period);
            Arrays.fill(this.heads, -1);
            Arrays.fill(this.tails, -1);
            Arrays.fill(this.sourceCounts, 0);
            this.scanBucket = 0;
            this.state = SNAPSHOT;
            this.rounds++;
        }
        countWork(now);
        if (this.state == SNAPSHOT) snapshot(now);
        else if (this.state == SOLVE) {
            if (this.plan.planNext(1)) {
                this.wide = this.plan.usesWideArithmetic();
                this.state = EXECUTE;
            }
        } else execute(now);
        return true;
    }

    private void snapshot(final long now) {
        while (this.scanBucket < 2 * COLORS) {
            final Role role = this.scanBucket < COLORS ? Role.DYNAMO : Role.ENERGY;
            final int color = this.scanBucket % COLORS;
            if (!this.scanning) {
                final Member saved = this.resume[this.scanBucket];
                this.scanStart = saved != null && (role == Role.DYNAMO || !saved.dormant)
                    && saved.isCurrent()
                    && saved.endpoint()
                        .businessColor() == color ? saved : first(role, color);
                this.scanNext = this.scanStart;
                this.scanTail = role == Role.ENERGY ? this.cache.lastDemand(this.tier, color) : null;
                this.scanRemaining = role == Role.DYNAMO ? this.cache.activeCount(role, this.tier, color)
                    : this.cache.demandCount(this.tier, color);
                this.scanning = true;
            }
            if (this.scanNext == null || this.scanRemaining == 0) {
                this.resume[this.scanBucket] = null;
                this.scanTail = null;
                this.scanBucket++;
                this.scanning = false;
                continue;
            }
            final Member member = this.scanNext;
            // Wakes append to the demand bucket without invalidating this plan. Wrap at its original
            // tail so a newly appended member cannot replace an unvisited old member in the count.
            final Member next = role == Role.DYNAMO ? member.next()
                : member == this.scanTail ? null : member.nextDemand();
            this.scanNext = next == null ? first(role, color) : next;
            this.resume[this.scanBucket] = this.scanNext;
            if (--this.scanRemaining == 0) {
                this.scanNext = null;
                this.scanTail = null;
                this.scanBucket++;
                this.scanning = false;
            }
            if (!member.isCurrent()) return;
            if (role == Role.DYNAMO) {
                final long net = this.cache.transfers()
                    .availableNet(member, now);
                if (net > 0) addSource(member, color, net);
            } else {
                final MEEnergyEpochPlan.Target fairness = member.fairness();
                if (member.clearFairCredit) {
                    fairness.clearCredit();
                    member.clearFairCredit = false;
                }
                final long free = this.cache.transfers()
                    .freeSpace(member);
                if (free == 0) {
                    fairness.clearCredit();
                    this.cache.sleepTarget(member);
                    return;
                }
                if (this.targetCount == this.targets.length)
                    this.targets = Arrays.copyOf(this.targets, this.targets.length * 2);
                member.snapshotOwner = this;
                member.snapshotIndex = this.targetCount;
                this.targets[this.targetCount++] = member;
                this.plan.addTarget(fairness, color, free, this.cache.maintenanceDemand(member, free));
            }
            return;
        }
        // Make each source-color list circular, then choose one rotated head per service round.
        if (this.sourceCount == 0) this.emptySupplyRounds++;
        if (this.targetCount == 0) this.emptyDemandRounds++;
        for (int color = 0; color < COLORS; color++) {
            if (this.sourceCounts[color] == 0) continue;
            this.nextSource[this.tails[color]] = this.heads[color];
            final int offset = this.rotations[color] % this.sourceCounts[color];
            this.heads[color] += offset; // A color's sources were collected contiguously.
            this.rotations[color] = (offset + 1) % this.sourceCounts[color];
        }
        this.state = SOLVE;
    }

    private Member first(final Role role, final int color) {
        return role == Role.DYNAMO ? this.cache.first(role, this.tier, color)
            : this.cache.firstDemand(this.tier, color);
    }

    private void addSource(final Member member, final int color, final long net) {
        if (this.sourceCount == this.sources.length) {
            final int length = this.sources.length * 2;
            this.sources = Arrays.copyOf(this.sources, length);
            this.remaining = Arrays.copyOf(this.remaining, length);
            this.nextSource = Arrays.copyOf(this.nextSource, length);
        }
        final int index = this.sourceCount++;
        member.snapshotOwner = this;
        member.snapshotIndex = index;
        this.sources[index] = member;
        this.remaining[index] = net;
        if (this.heads[color] < 0) this.heads[color] = index;
        else this.nextSource[this.tails[color]] = index;
        this.tails[color] = index;
        this.sourceCounts[color]++;
        this.plan.addSource(color, net);
    }

    private void execute(final long now) {
        final int targetIndex = this.plan.nextTargetIndex();
        if (targetIndex < 0) {
            cancel();
            return;
        }
        final Member target = this.targets[targetIndex];
        if (!target.isCurrent()) {
            this.cancellations++;
            cancel();
            return;
        }
        final int targetColor = target.endpoint()
            .businessColor();
        final int color = targetColor;
        final int index = this.heads[color];
        if (index >= 0) {
            final long limit = this.plan.colorFlowLimit(
                color,
                targetColor,
                Math.min(this.remaining[index], this.plan.executionLimit(targetIndex)));
            if (limit == 0) {
                this.plan.abandonTarget(targetIndex);
                this.unrouteableTargets++;
                return;
            }
            if (!this.sources[index].isCurrent()) {
                this.cancellations++;
                cancel();
                return;
            }
            final long net = this.cache.transfers()
                .commit(this.cache, this.membershipEpoch, this.sources[index], target, limit, now);
            if (net > 0) {
                final long previous = target.fairness()
                    .lastServedTick();
                this.plan.record(this.generation, targetIndex, color, net, now);
                this.remaining[index] -= net;
                if (previous >= 0) this.maxServiceGap = Math.max(this.maxServiceGap, now - previous);
                this.lastServiceTick = now;
                if (this.remaining[index] == 0) popSource(color, index);
            } else {
                // An external controller may have consumed a snapshot source or filled a target.
                if (this.cache.transfers()
                    .freeSpace(target) == 0) this.plan.abandonTarget(targetIndex);
                else popSource(color, index);
            }
            return; // One candidate/commit per work unit; the next unit rotates pending targets.
        }
        this.plan.abandonTarget(targetIndex); // Only the same-color source pool can serve this target.
        this.unrouteableTargets++;
    }

    private void popSource(final int color, final int index) {
        this.heads[color] = --this.sourceCounts[color] == 0 ? -1 : this.nextSource[index];
        this.remaining[index] = 0;
    }

    void cancel() {
        if (this.state == CLEANUP) return;
        this.plan.startCancel();
        this.cleanupIndex = 0;
        this.scanNext = this.scanStart = this.scanTail = null;
        this.scanning = false;
        this.state = CLEANUP;
    }

    private void cleanupNext() {
        final boolean complete = this.plan.cancelNext(CLEANUP_BATCH);
        for (int count = 0; count < CLEANUP_BATCH
            && (this.cleanupIndex < this.sourceCount || this.cleanupIndex < this.targetCount); count++) {
            if (this.cleanupIndex < this.sourceCount) {
                releaseSnapshot(this.sources[this.cleanupIndex]);
                this.sources[this.cleanupIndex] = null;
            }
            if (this.cleanupIndex < this.targetCount) {
                releaseSnapshot(this.targets[this.cleanupIndex]);
                this.targets[this.cleanupIndex] = null;
            }
            this.cleanupIndex++;
        }
        if (complete && this.cleanupIndex >= this.sourceCount && this.cleanupIndex >= this.targetCount) {
            this.sourceCount = this.targetCount = 0;
            this.state = IDLE;
        }
    }

    private void releaseSnapshot(final Member member) {
        if (member != null && member.snapshotOwner == this) member.snapshotOwner = null;
    }

    void forget(final Member member) {
        if (this.scanTail == member) this.scanTail = null;
        if (member.snapshotOwner == this) {
            final int index = member.snapshotIndex;
            if (member.endpoint()
                .spec()
                .role() == Role.DYNAMO) this.sources[index] = null;
            else this.targets[index] = null;
            member.snapshotOwner = null;
        }
        for (int index = 0; index < this.resume.length; index++)
            if (this.resume[index] == member) this.resume[index] = null;
    }

    void close() {
        cancel();
        while (this.state == CLEANUP) cleanupNext();
        Arrays.fill(this.resume, null);
    }

    private void countWork(final long now) {
        if (this.lastWorkTick != now) {
            this.lastWorkTick = now;
            this.tickWork = 0;
        }
        this.work++;
        this.maxWork = Math.max(this.maxWork, ++this.tickWork);
    }

    long rounds() {
        return this.rounds;
    }

    long cancellations() {
        return this.cancellations;
    }

    long expirations() {
        return this.expirations;
    }

    long work() {
        return this.work;
    }

    long maxWork() {
        return this.maxWork;
    }

    long lastServiceTick() {
        return this.lastServiceTick;
    }

    long maxServiceGap() {
        return this.maxServiceGap;
    }

    boolean wide() {
        return this.wide;
    }

    long emptySupplyRounds() {
        return this.emptySupplyRounds;
    }

    long emptyDemandRounds() {
        return this.emptyDemandRounds;
    }

    long unrouteableTargets() {
        return this.unrouteableTargets;
    }
}
