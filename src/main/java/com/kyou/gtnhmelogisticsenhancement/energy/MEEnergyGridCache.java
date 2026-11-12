package com.kyou.gtnhmelogisticsenhancement.energy;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridStorage;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.events.MENetworkBootingStatusChange;
import appeng.api.networking.events.MENetworkEventSubscribe;
import appeng.api.networking.events.MENetworkPowerStatusChange;
import appeng.api.networking.pathing.IPathingGrid;

/** Event-driven main-thread membership and bounded periodic EU service. No full Grid traversal. */
public final class MEEnergyGridCache implements IMEEnergyGrid, MEEnergyRateBudget.ActiveClock {

    private static final int TIERS = MEEnergySpec.MAX_TIER - MEEnergySpec.MIN_TIER + 1;
    public static final int WORK_PER_TICK = 512;
    public static final int SERVICE_PERIOD = 10;
    static final int REFRESH_WORK_PER_TICK = 128;
    private static final int GROUP_QUANTUM = 32;
    private final int servicePeriod;
    private final int phaseOffset;
    private final IGrid grid;
    private final Map<IGridNode, Member> byNode = new IdentityHashMap<>(4);
    private final Map<IMEEnergyEndpoint, Member> byHost = new IdentityHashMap<>(4);
    private Bucket[] buckets;
    private Bucket[] demandBuckets;
    private final long[] epochs = new long[TIERS];
    private final int[] activeByRole = new int[2 * TIERS];
    private final int[] demandByTier = new int[TIERS];
    private final int[] dirtyByTier = new int[TIERS];
    private final MEEnergyGroupState[] groups = new MEEnergyGroupState[TIERS];
    private final MEEnergyTransfer transfers = new MEEnergyTransfer();
    private Member members;
    private Member dirtyHead;
    private Member dirtyTail;
    private Member sweepCursor;
    private boolean closed;
    private long refreshes;
    private long lastScheduleTick = -1;
    private int nextGroup;
    private boolean updating;
    private long maxTickWork;
    private int tickWork;
    private int tickRefreshWork;
    private long networkRevision;
    private long clockTick = -1;
    private long activeTicks;
    private boolean networkAvailable;
    private IEnergyGrid networkPower;
    private IPathingGrid networkPath;
    private int nextProbePhase;
    private int dormantCount;
    private long localDemandProbes;
    private MEEnergyMonitor monitor;

    public MEEnergyGridCache(final IGrid grid) {
        this(grid, SERVICE_PERIOD, MEEnergyNetwork.nextPhase(SERVICE_PERIOD));
    }

    /** Internal policy seam for fixed-period comparisons; never a per-hatch runtime setting. */
    MEEnergyGridCache(final IGrid grid, final int period) {
        this(grid, period, 0);
    }

    MEEnergyGridCache(final IGrid grid, final int period, final int phase) {
        if (period < 1 || period > MEEnergyEpochPlan.MAX_PERIOD)
            throw new IllegalArgumentException("Invalid ME EU service period");
        this.servicePeriod = period;
        this.phaseOffset = Math.floorMod(phase, period);
        this.grid = Objects.requireNonNull(grid, "grid");
        this.closed = MEEnergyNetwork.isStopping();
        MEEnergyNetwork.track(this);
    }

    @Override
    public void addNode(final IGridNode node, final IGridHost host) {
        if (this.closed || !(host instanceof IMEEnergyEndpoint)) return;
        final IMEEnergyEndpoint endpoint = (IMEEnergyEndpoint) host;
        if (!(host instanceof MTEHatchMEEnergyStandard || host instanceof MTEHatchMEEnergyMulti
            || host instanceof MTEHatchMEDynamoStandard
            || host instanceof MTEHatchMEDynamoMulti)) return;
        if (this.byNode.containsKey(node) || this.byHost.containsKey(endpoint)) return;
        // Only the passed node/host is inspected here. Never ask the partially rebuilt Grid for its contents.
        if (endpoint.nodeSupport()
            .peekNode() != node || node.getMachine() != host) return;
        final Member member = new Member(this, node, endpoint);
        if (endpoint.spec()
            .role() == MEEnergySpec.Role.ENERGY && endpoint.isEndpointValid())
            member.observedStock = Math.min(
                endpoint.spec()
                    .capacity(),
                Math.max(0, endpoint.readEU()));
        endpoint.nodeSupport()
            .bind(member);
        if (endpoint.spec()
            .role() == MEEnergySpec.Role.DYNAMO)
            endpoint.energyState()
                .rateBudget()
                .bindClock(this, MEEnergyTickClock.now());
        this.byNode.put(node, member);
        this.byHost.put(endpoint, member);
        member.allNext = this.members;
        if (this.members != null) this.members.allPrevious = member;
        this.members = member;
        invalidate(member);
        enqueue(member);
    }

    @Override
    public void removeNode(final IGridNode node, final IGridHost host) {
        final Member member = this.byNode.get(node);
        if (member != null && member.endpoint == host) detach(member);
    }

    void detach(final Member member) {
        if (this.byNode.get(member.node) != member) return;
        invalidate(member);
        unlinkBucket(member);
        if (member.dormant) {
            member.dormant = false;
            this.dormantCount--;
        }
        dequeue(member);
        this.byNode.remove(member.node);
        this.byHost.remove(member.endpoint);
        final MEEnergyGroupState group = this.groups[tierIndex(
            member.endpoint.spec()
                .tier())];
        if (group != null) group.forget(member);
        if (this.sweepCursor == member) this.sweepCursor = member.allNext;
        if (member.allPrevious == null) this.members = member.allNext;
        else member.allPrevious.allNext = member.allNext;
        if (member.allNext != null) member.allNext.allPrevious = member.allPrevious;
        member.allPrevious = member.allNext = null;
        member.endpoint.nodeSupport()
            .unbind(member);
    }

    /** Host-only channel events must explicitly reach this cache; AE postEventTo does not notify caches. */
    void statusChanged(final Member member) {
        if (this.closed || this.byNode.get(member.node) != member) return;
        invalidate(member);
        observePermission(member, MEEnergyTickClock.now());
        enqueue(member);
    }

    void configurationChanged(final Member member) {
        if (this.closed || this.byNode.get(member.node) != member) return;
        invalidate(member);
        enqueue(member);
    }

    @MENetworkEventSubscribe
    public void powerChanged(final MENetworkPowerStatusChange event) {
        networkStatusChanged();
    }

    @MENetworkEventSubscribe
    public void bootChanged(final MENetworkBootingStatusChange event) {
        networkStatusChanged();
    }

    private void networkStatusChanged() {
        if (this.closed) return;
        final long now = MEEnergyTickClock.now();
        updateNetworkPermission(now);
    }

    @Override
    public long activeTick(final long now) {
        if (this.clockTick < 0) {
            this.clockTick = now;
            this.networkAvailable = readNetworkPermission();
        }
        if (now < this.clockTick) throw new IllegalArgumentException("Game tick moved backwards");
        if (this.networkAvailable) this.activeTicks += now - this.clockTick;
        this.clockTick = now;
        return this.activeTicks;
    }

    private boolean readNetworkPermission() {
        if (this.networkPower == null) this.networkPower = this.grid.getCache(IEnergyGrid.class);
        if (this.networkPath == null) this.networkPath = this.grid.getCache(IPathingGrid.class);
        return this.networkPower != null && this.networkPath != null
            && this.networkPower.isNetworkPowered()
            && !this.networkPath.isNetworkBooting();
    }

    private void updateNetworkPermission(final long now) {
        activeTick(now); // First account for the preceding state, at the event's real game Tick.
        final boolean available = readNetworkPermission();
        if (available == this.networkAvailable) return;
        this.networkAvailable = available;
        this.networkRevision++;
        invalidateAll();
        this.sweepCursor = this.members;
    }

    @Override
    public void onUpdateTick() {
        if (this.closed || this.updating) return;
        this.updating = true;
        try {
            final long now = MEEnergyTickClock.now();
            updateNetworkPermission(now);
            if (this.lastScheduleTick != now) {
                this.lastScheduleTick = now;
                this.tickWork = this.tickRefreshWork = 0;
            }
            int work = this.tickWork;
            while (this.tickRefreshWork < REFRESH_WORK_PER_TICK && work < WORK_PER_TICK
                && (this.sweepCursor != null || this.dirtyHead != null)) {
                final Member member;
                if (this.sweepCursor != null) {
                    member = this.sweepCursor;
                    this.sweepCursor = member.allNext;
                } else member = this.dirtyHead;
                dequeue(member);
                work++;
                this.tickRefreshWork++;
                this.refreshes++;
                final boolean active = observePermission(member, now);
                final int color = active ? member.endpoint.businessColor() : -1;
                final int bucket = active && color >= 0 && color < MEEnergyGroupPlanner.COLOR_COUNT ? bucketId(
                    member.endpoint.spec()
                        .role(),
                    member.endpoint.spec()
                        .tier(),
                    color) : -1;
                if (member.bucketId != bucket) {
                    unlinkBucket(member);
                    if (bucket >= 0) linkBucket(member, bucket);
                }
                member.indexedRevision = member.endpoint.energyState()
                    .revision();
                member.indexedNetworkRevision = this.networkRevision;
            }
            boolean progressed = true;
            while (work < WORK_PER_TICK && progressed) {
                progressed = false;
                for (int offset = 0; offset < TIERS && work < WORK_PER_TICK; offset++) {
                    final MEEnergyGroupState group = this.groups[(this.nextGroup + offset) % TIERS];
                    if (group == null) continue;
                    for (int slice = 0; slice < GROUP_QUANTUM && work < WORK_PER_TICK; slice++) {
                        if (!group.step(now)) break;
                        work++;
                        progressed = true;
                    }
                }
                this.nextGroup = (this.nextGroup + 1) % TIERS;
            }
            if (this.monitor != null) {
                if (!this.monitor.live(now)) stopMonitoring();
                else work += this.monitor.sample(now, WORK_PER_TICK - work);
            }
            this.tickWork = work;
            this.maxTickWork = Math.max(this.maxTickWork, work);
        } finally {
            this.updating = false;
        }
    }

    private boolean observePermission(final Member member, final long now) {
        final boolean active = member.endpoint.nodeSupport()
            .liveIn(this, member.node);
        final gregtech.api.interfaces.tileentity.IGregTechTileEntity base = member.endpoint.machine()
            .getBaseMetaTileEntity();
        if (base != null && base.isActive() != active) base.setActive(active);
        if (!active) member.clearFairCredit = true;
        if (member.endpoint.spec()
            .role() == MEEnergySpec.Role.DYNAMO) {
            member.endpoint.energyState()
                .rateBudget()
                .setActive(
                    member.endpoint.nodeSupport()
                        .channelEligibleIn(this, member.node),
                    now);
        }
        return active;
    }

    boolean isCurrent(final Member member) {
        if (this.closed || member.bucketId < 0
            || member.indexedNetworkRevision != this.networkRevision
            || this.byNode.get(member.node) != member
            || member.indexedRevision != member.endpoint.energyState()
                .revision())
            return false;
        return member.bucketId % MEEnergyGroupPlanner.COLOR_COUNT == member.endpoint.businessColor()
            && member.endpoint.nodeSupport()
                .liveIn(this, member.node);
    }

    IGrid grid() {
        return this.grid;
    }

    public MEEnergyMonitor.Watch watch() {
        if (this.closed) throw new IllegalStateException("Closed ME EU Grid");
        final long now = MEEnergyTickClock.now();
        if (this.monitor == null || !this.monitor.live(now)) {
            stopMonitoring();
            this.monitor = new MEEnergyMonitor(this, now);
        }
        return this.monitor.watch(now);
    }

    boolean isMonitoring(final MEEnergyMonitor candidate) {
        return !this.closed && this.monitor == candidate;
    }

    void releaseMonitor(final MEEnergyMonitor candidate) {
        if (this.monitor == candidate && !candidate.live(MEEnergyTickClock.now())) stopMonitoring();
    }

    private void stopMonitoring() {
        if (this.monitor != null) this.monitor.invalidate();
        this.monitor = null;
    }

    void recordMonitorTransfer(final int tier, final int color, final long gross, final long net, final long now) {
        if (this.monitor != null) this.monitor.record(tier, color, gross, net, now);
    }

    Member firstMonitorMember() {
        return this.members;
    }

    @Override
    public long epoch(final int tier) {
        return this.epochs[tierIndex(tier)];
    }

    public boolean isEpochValid(final int tier, final long epoch) {
        return !this.closed && epoch(tier) == epoch;
    }

    @Override
    public int memberCount() {
        return this.byNode.size();
    }

    @Override
    public int activeCount(final MEEnergySpec.Role role, final int tier, final int color) {
        final int id = bucketId(role, tier, color);
        final Bucket bucket = this.buckets == null ? null : this.buckets[id];
        return bucket == null ? 0 : bucket.count;
    }

    @Override
    public Member first(final MEEnergySpec.Role role, final int tier, final int color) {
        final int id = bucketId(role, tier, color);
        final Bucket bucket = this.buckets == null ? null : this.buckets[id];
        return bucket == null ? null : bucket.first;
    }

    public long refreshes() {
        return this.refreshes;
    }

    boolean hasBothRoles(final int tier) {
        final int index = tierIndex(tier);
        return this.activeByRole[index] > 0 && this.activeByRole[TIERS + index] > 0;
    }

    public MEEnergyTransfer transfers() {
        return this.transfers;
    }

    public long maxTickWork() {
        return this.maxTickWork;
    }

    int servicePeriod() {
        return this.servicePeriod;
    }

    int phaseOffset() {
        return this.phaseOffset;
    }

    boolean refreshPending() {
        return this.sweepCursor != null || this.dirtyHead != null;
    }

    boolean refreshPending(final int tier) {
        return this.sweepCursor != null || this.dirtyByTier[tierIndex(tier)] > 0;
    }

    int dormantCount() {
        return this.dormantCount;
    }

    long localDemandProbes() {
        return this.localDemandProbes;
    }

    void noteLocalDemandProbe() {
        this.localDemandProbes++;
    }

    boolean hasDemand(final int tier) {
        return this.demandByTier[tierIndex(tier)] > 0;
    }

    Member firstDemand(final int tier, final int color) {
        final Bucket bucket = demandBucket(tier, color);
        return bucket == null ? null : bucket.first;
    }

    Member lastDemand(final int tier, final int color) {
        final Bucket bucket = demandBucket(tier, color);
        return bucket == null ? null : bucket.last;
    }

    int demandCount(final int tier, final int color) {
        final Bucket bucket = demandBucket(tier, color);
        return bucket == null ? 0 : bucket.count;
    }

    private Bucket demandBucket(final int tier, final int color) {
        return this.demandBuckets == null ? null
            : this.demandBuckets[tierIndex(tier) * MEEnergyGroupPlanner.COLOR_COUNT + color];
    }

    void sleepTarget(final Member member) {
        if (member.dormant) return;
        member.clearFairCredit = true;
        member.observedStock = member.endpoint.spec()
            .capacity();
        member.admittedSinceObservation = 0;
        member.dormant = true;
        this.dormantCount++;
        unlinkDemand(member);
    }

    void wakeTarget(final Member member) {
        if (!member.dormant || this.byNode.get(member.node) != member) return;
        member.dormant = false;
        this.dormantCount--;
        // Demand changes never invalidate another consumer's in-flight plan.
        if (member.bucketId >= 0) linkDemand(member);
    }

    void recordAdmission(final Member target, final long net) {
        target.admittedSinceObservation += net;
    }

    long maintenanceDemand(final Member member, final long free) {
        final MEEnergySpec spec = member.endpoint.spec();
        final long stock = spec.capacity() - free;
        final long drawn = member.observedStock < 0 ? 0
            : Math.max(0, member.observedStock + member.admittedSinceObservation - stock);
        member.observedStock = stock;
        member.admittedSinceObservation = 0;
        if (drawn > 0) member.observedConsumption = true;
        if (!member.observedConsumption) return 0;
        // Keep a recently starved consumer eligible even when its next native draw fails. An idle
        // consumer receives only a finite reserve, then returns to ordinary weighted precharge.
        final long nominalDraw = spec.power() + spec.power() / 4;
        final long reserve = Math.min(spec.capacity(), nominalDraw * (this.servicePeriod + 7L));
        return Math.min(free, Math.max(drawn, Math.max(0, reserve - stock)));
    }

    MEEnergyGroupState group(final int tier) {
        return this.groups[tierIndex(tier)];
    }

    @Override
    public void onSplit(final IGridStorage destination) {
        invalidateAll(); // No EU, c, token or quotas are copied into AE Grid storage.
    }

    @Override
    public void onJoin(final IGridStorage source) {
        invalidateAll();
    }

    @Override
    public void populateGridStorage(final IGridStorage destination) {
        // Derived references are rebuilt by add/remove callbacks. Nothing in this cache is persistent EU.
    }

    void shutdown() {
        this.closed = true;
        stopMonitoring();
        invalidateAll();
        for (final MEEnergyGroupState group : this.groups) if (group != null) group.close();
        while (this.members != null) detach(this.members);
    }

    private void invalidate(final Member member) {
        if (this.monitor != null) this.monitor.invalidate();
        final int index = tierIndex(
            member.endpoint.spec()
                .tier());
        this.epochs[index] = Math.incrementExact(this.epochs[index]);
    }

    private void invalidateAll() {
        if (this.monitor != null) this.monitor.invalidate();
        for (int index = 0; index < TIERS; index++) this.epochs[index] = Math.incrementExact(this.epochs[index]);
    }

    private void enqueue(final Member member) {
        if (member.dirty) return;
        member.dirty = true;
        this.dirtyByTier[tierIndex(
            member.endpoint.spec()
                .tier())]++;
        member.dirtyPrevious = this.dirtyTail;
        if (this.dirtyTail == null) this.dirtyHead = member;
        else this.dirtyTail.dirtyNext = member;
        this.dirtyTail = member;
    }

    private void dequeue(final Member member) {
        if (!member.dirty) return;
        this.dirtyByTier[tierIndex(
            member.endpoint.spec()
                .tier())]--;
        if (member.dirtyPrevious == null) this.dirtyHead = member.dirtyNext;
        else member.dirtyPrevious.dirtyNext = member.dirtyNext;
        if (member.dirtyNext == null) this.dirtyTail = member.dirtyPrevious;
        else member.dirtyNext.dirtyPrevious = member.dirtyPrevious;
        member.dirtyPrevious = member.dirtyNext = null;
        member.dirty = false;
    }

    private void linkBucket(final Member member, final int id) {
        if (this.buckets == null) this.buckets = new Bucket[2 * TIERS * MEEnergyGroupPlanner.COLOR_COUNT];
        Bucket bucket = this.buckets[id];
        if (bucket == null) this.buckets[id] = bucket = new Bucket();
        member.bucketId = id;
        member.previous = bucket.last;
        if (bucket.last == null) bucket.first = member;
        else bucket.last.next = member;
        bucket.last = member;
        bucket.count++;
        this.activeByRole[id / MEEnergyGroupPlanner.COLOR_COUNT]++;
        if (member.endpoint.spec()
            .role() == MEEnergySpec.Role.ENERGY && !member.dormant) linkDemand(member);
        final int tier = member.endpoint.spec()
            .tier();
        if (this.groups[tierIndex(tier)] == null && hasBothRoles(tier))
            this.groups[tierIndex(tier)] = new MEEnergyGroupState(this, tier);
    }

    private void unlinkBucket(final Member member) {
        if (member.bucketId < 0) return;
        unlinkDemand(member);
        final Bucket bucket = this.buckets[member.bucketId];
        if (member.previous == null) bucket.first = member.next;
        else member.previous.next = member.next;
        if (member.next == null) bucket.last = member.previous;
        else member.next.previous = member.previous;
        bucket.count--;
        this.activeByRole[member.bucketId / MEEnergyGroupPlanner.COLOR_COUNT]--;
        member.previous = member.next = null;
        member.bucketId = -1;
    }

    private void linkDemand(final Member member) {
        if (member.demandBucketId >= 0) return;
        if (this.demandBuckets == null) this.demandBuckets = new Bucket[TIERS * MEEnergyGroupPlanner.COLOR_COUNT];
        final int tier = tierIndex(
            member.endpoint.spec()
                .tier());
        final int id = tier * MEEnergyGroupPlanner.COLOR_COUNT + member.bucketId % MEEnergyGroupPlanner.COLOR_COUNT;
        Bucket bucket = this.demandBuckets[id];
        if (bucket == null) this.demandBuckets[id] = bucket = new Bucket();
        member.demandBucketId = id;
        member.demandPrevious = bucket.last;
        if (bucket.last == null) bucket.first = member;
        else bucket.last.demandNext = member;
        bucket.last = member;
        bucket.count++;
        this.demandByTier[tier]++;
    }

    private void unlinkDemand(final Member member) {
        if (member.demandBucketId < 0) return;
        final Bucket bucket = this.demandBuckets[member.demandBucketId];
        if (member.demandPrevious == null) bucket.first = member.demandNext;
        else member.demandPrevious.demandNext = member.demandNext;
        if (member.demandNext == null) bucket.last = member.demandPrevious;
        else member.demandNext.demandPrevious = member.demandPrevious;
        bucket.count--;
        this.demandByTier[member.demandBucketId / MEEnergyGroupPlanner.COLOR_COUNT]--;
        member.demandPrevious = member.demandNext = null;
        member.demandBucketId = -1;
    }

    private static int tierIndex(final int tier) {
        if (tier < MEEnergySpec.MIN_TIER || tier > MEEnergySpec.MAX_TIER)
            throw new IllegalArgumentException("Unknown tier");
        return tier - MEEnergySpec.MIN_TIER;
    }

    private static int bucketId(final MEEnergySpec.Role role, final int tier, final int color) {
        if (color < 0 || color >= MEEnergyGroupPlanner.COLOR_COUNT) throw new IllegalArgumentException("Unknown color");
        return (Objects.requireNonNull(role, "role")
            .ordinal() * TIERS + tierIndex(tier)) * MEEnergyGroupPlanner.COLOR_COUNT + color;
    }

    private static final class Bucket {

        Member first;
        Member last;
        int count;
    }

    /** Intrusive indexed reference; callers must check tier epoch and isCurrent before using a snapshot. */
    public static final class Member {

        final MEEnergyGridCache cache;
        final IGridNode node;
        private final IMEEnergyEndpoint endpoint;
        private Member allPrevious;
        private Member allNext;
        private Member dirtyPrevious;
        private Member dirtyNext;
        private Member previous;
        private Member next;
        private Member demandPrevious;
        private Member demandNext;
        private int bucketId = -1;
        private int demandBucketId = -1;
        private long indexedRevision;
        private long indexedNetworkRevision;
        private boolean dirty;
        boolean dormant;
        final int probePhase;
        boolean clearFairCredit;
        MEEnergyGroupState snapshotOwner;
        int snapshotIndex;
        private long observedStock = -1;
        private long admittedSinceObservation;
        private boolean observedConsumption;
        private MEEnergyEpochPlan.Target fairness;

        private Member(final MEEnergyGridCache cache, final IGridNode node, final IMEEnergyEndpoint endpoint) {
            this.cache = cache;
            this.node = node;
            this.endpoint = endpoint;
            this.probePhase = cache.nextProbePhase++ & 3;
        }

        public IMEEnergyEndpoint endpoint() {
            return this.endpoint;
        }

        public Member next() {
            return this.next;
        }

        Member nextMonitorMember() {
            return this.allNext;
        }

        Member nextDemand() {
            return this.demandNext;
        }

        public boolean isCurrent() {
            return this.cache.isCurrent(this);
        }

        MEEnergyEpochPlan.Target fairness() {
            if (this.endpoint.spec()
                .role() != MEEnergySpec.Role.ENERGY)
                throw new IllegalStateException("Only energy targets own fair credit");
            if (this.fairness == null) this.fairness = new MEEnergyEpochPlan.Target(
                this.endpoint.spec()
                    .power());
            return this.fairness;
        }
    }
}
