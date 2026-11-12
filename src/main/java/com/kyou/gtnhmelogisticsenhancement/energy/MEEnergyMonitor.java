package com.kyou.gtnhmelogisticsenhancement.energy;

import java.math.BigInteger;
import java.util.Arrays;

/** Viewer-only, cache-local telemetry. Inventory sampling never takes work away from energy service. */
public final class MEEnergyMonitor {

    static final int SAMPLE_BUDGET = 32;
    static final int LEASE_TICKS = 100;
    private final MEEnergyGridCache cache;
    private final MEEnergyMonitorWindow window;
    private final long firstFullTick;
    private final Cell[] collecting = new Cell[MEEnergyMonitorWindow.CELLS];
    private final MEEnergyMonitorRow[] rows = new MEEnergyMonitorRow[MEEnergyMonitorWindow.CELLS];
    private final String[] encoded = new String[MEEnergyMonitorWindow.CELLS];
    private MEEnergyGridCache.Member cursor;
    private int viewers;
    private long touched;
    private long nextSample;
    private long sampleStart;
    private long stockStart = -1, stockEnd = -1;
    private long stockVersion;
    private long stockReads;
    private long budgetTick = -1;
    private int tickSamples;
    private boolean scanning;
    private boolean invalidated = true;

    MEEnergyMonitor(final MEEnergyGridCache cache, final long now) {
        this.cache = cache;
        // A GUI can open midway through a Tick. Exclude that partial Tick from the first full window.
        this.firstFullTick = now + 1;
        this.window = new MEEnergyMonitorWindow(this.firstFullTick);
        this.touched = now;
        Arrays.fill(this.rows, MEEnergyMonitorRow.EMPTY);
    }

    Watch watch(final long now) {
        this.viewers++;
        this.touched = now;
        return new Watch(this);
    }

    boolean live(final long now) {
        return this.viewers > 0 && now >= this.touched && now - this.touched < LEASE_TICKS;
    }

    void record(final int tier, final int color, final long gross, final long net, final long now) {
        if (!live(now) || now < this.firstFullTick) return;
        advance(now);
        this.window.record(MEEnergyMonitorWindow.cell(tier, color), gross, net, now);
    }

    void advance(final long now) {
        if (now < this.firstFullTick) return;
        final long previous = this.window.completedEnd();
        this.window.advance(now);
        if (previous != this.window.completedEnd()) Arrays.fill(this.encoded, null);
    }

    void invalidate() {
        this.cursor = null;
        this.scanning = false;
        this.invalidated = true;
        this.nextSample = 0;
    }

    int sample(final long now, final int availableWork) {
        advance(now);
        if (this.budgetTick != now) {
            this.budgetTick = now;
            this.tickSamples = 0;
        }
        final int budget = Math.min(availableWork, SAMPLE_BUDGET - this.tickSamples);
        if (budget <= 0 || !this.scanning && now < this.nextSample) return 0;
        if (!this.scanning) {
            for (final Cell cell : this.collecting) if (cell != null) cell.clear();
            this.cursor = this.cache.firstMonitorMember();
            this.sampleStart = now;
            this.scanning = true;
        }
        int work = 0;
        while (this.cursor != null && work < budget) {
            final MEEnergyGridCache.Member member = this.cursor;
            this.cursor = member.nextMonitorMember();
            work++;
            collect(member);
        }
        this.tickSamples += work;
        if (this.cursor == null) {
            for (int index = 0; index < this.rows.length; index++)
                this.rows[index] = this.collecting[index] == null ? MEEnergyMonitorRow.EMPTY
                    : this.collecting[index].finish();
            this.stockStart = this.sampleStart;
            this.stockEnd = now;
            this.stockVersion++;
            this.scanning = this.invalidated = false;
            this.nextSample = Math.max(this.sampleStart + MEEnergyMonitorWindow.PERIOD, now + 1);
            Arrays.fill(this.encoded, null);
        }
        return work;
    }

    private void collect(final MEEnergyGridCache.Member member) {
        final IMEEnergyEndpoint endpoint = member.endpoint();
        final MEEnergySpec spec = endpoint.spec();
        final int color = endpoint.businessColor();
        if (color < 0 || color >= 17) return;
        final int index = MEEnergyMonitorWindow.cell(spec.tier(), color);
        if (this.collecting[index] == null) this.collecting[index] = new Cell();
        final Cell cell = this.collecting[index];
        final boolean source = spec.role() == MEEnergySpec.Role.DYNAMO;
        final int role = source ? 0 : 2;
        cell.counts[role]++;
        final boolean valid = endpoint.isEndpointValid();
        final boolean online = valid && member.isCurrent();
        if (online) cell.counts[role + 1]++;
        if (!source && member.dormant) cell.counts[4]++;
        cell.values[source ? MEEnergyMonitorRow.SOURCE_CAPACITY : MEEnergyMonitorRow.TARGET_CAPACITY]
            .add(spec.capacity());
        cell.values[source ? MEEnergyMonitorRow.SOURCE_LIMIT : MEEnergyMonitorRow.TARGET_LIMIT].add(spec.power());
        if (source && online) cell.values[MEEnergyMonitorRow.ONLINE_LIMIT].add(spec.power());
        final long stored = valid ? endpoint.readEU() : -1;
        if (valid) this.stockReads++;
        if (stored < 0) cell.counts[5]++;
        else cell.values[source ? MEEnergyMonitorRow.SOURCE_STOCK : MEEnergyMonitorRow.TARGET_STOCK].add(stored);
    }

    public MEEnergyMonitorRow row(final int tier, final int color) {
        return this.rows[MEEnergyMonitorWindow.cell(tier, color)];
    }

    String encodedRow(final int tier, final int color) {
        final int cell = MEEnergyMonitorWindow.cell(tier, color);
        if (this.encoded[cell] == null) this.encoded[cell] = this.rows[cell].encode(this.window.value(cell));
        return this.encoded[cell];
    }

    public long stockStart() {
        return this.stockStart;
    }

    public long stockEnd() {
        return this.stockEnd;
    }

    public long stockVersion() {
        return this.stockVersion;
    }

    public long flowEnd() {
        return this.window.completedEnd();
    }

    public boolean updating() {
        return this.scanning || this.invalidated;
    }

    public long stockReads() {
        return this.stockReads;
    }

    public MEEnergyMonitorWindow.Value flow(final int tier, final int color) {
        return this.window.value(MEEnergyMonitorWindow.cell(tier, color));
    }

    private static final class Cell {

        final int[] counts = new int[6];
        final MEEnergyWideCounter[] values = new MEEnergyWideCounter[7];

        Cell() {
            for (int index = 0; index < this.values.length; index++) this.values[index] = new MEEnergyWideCounter();
        }

        void clear() {
            Arrays.fill(this.counts, 0);
            for (final MEEnergyWideCounter value : this.values) value.clear();
        }

        MEEnergyMonitorRow finish() {
            if (this.counts[0] == 0 && this.counts[2] == 0) return MEEnergyMonitorRow.EMPTY;
            final BigInteger[] amounts = new BigInteger[9];
            for (int index = 0; index < 7; index++) amounts[index] = this.values[index].toBigInteger();
            amounts[7] = amounts[8] = BigInteger.ZERO;
            return new MEEnergyMonitorRow(this.counts, amounts);
        }
    }

    public static final class Watch implements AutoCloseable {

        private final MEEnergyMonitor monitor;
        private boolean closed;

        private Watch(final MEEnergyMonitor monitor) {
            this.monitor = monitor;
        }

        public boolean touch() {
            if (this.closed || !this.monitor.cache.isMonitoring(this.monitor)) return false;
            this.monitor.touched = MEEnergyTickClock.now();
            return true;
        }

        public MEEnergyMonitor monitor() {
            return this.monitor;
        }

        @Override
        public void close() {
            if (this.closed) return;
            this.closed = true;
            this.monitor.viewers--;
            this.monitor.cache.releaseMonitor(this.monitor);
        }
    }
}
