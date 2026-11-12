package com.kyou.gtnhmelogisticsenhancement.energy;

/** Lazy gross transfer permission in game ticks. It is neither EU nor persistent state. */
public final class MEEnergyRateBudget {

    interface ActiveClock {

        long activeTick(long now);
    }

    private final long rate;
    private final long capacity;
    private long tokens;
    private long lastTick;
    private boolean active;
    private ActiveClock clock;

    public MEEnergyRateBudget(final long rate, final long capacity) {
        if (rate <= 0 || capacity <= 0) throw new IllegalArgumentException("Rate and capacity must be positive");
        this.rate = rate;
        this.capacity = capacity;
    }

    public void reset(final long now) {
        if (now < 0) throw new IllegalArgumentException("Tick must be nonnegative");
        this.clock = null;
        this.tokens = 0;
        this.lastTick = now;
        this.active = false;
    }

    /** A grid-wide paused clock excludes network outages without visiting every source at each event. */
    void bindClock(final ActiveClock value, final long now) {
        this.clock = value;
        this.tokens = 0;
        this.lastTick = value.activeTick(now);
        this.active = false;
    }

    public void setActive(final boolean value, final long now) {
        sync(now); // Account for the previous active interval before pausing.
        this.active = value;
    }

    public long tokens(final long now) {
        sync(now);
        return this.tokens;
    }

    public boolean isActive() {
        return this.active;
    }

    public long availableGross(final long raw, final long reserve, final long now) {
        if (raw < 0 || reserve < 0) throw new IllegalArgumentException("Invalid source stock or reserve");
        sync(now);
        if (!this.active) return 0;
        final long visible = Math.min(raw, this.capacity);
        return Math.min(this.tokens, Math.max(0, visible - reserve));
    }

    public boolean spend(final long gross, final long now) {
        if (gross < 0) throw new IllegalArgumentException("Gross amount must be nonnegative");
        sync(now);
        if (!this.active || gross > this.tokens) return false;
        this.tokens -= gross;
        return true;
    }

    private void sync(final long now) {
        final long current = this.clock == null ? now : this.clock.activeTick(now);
        if (current < this.lastTick) throw new IllegalArgumentException("Game tick moved backwards");
        final long elapsed = current - this.lastTick;
        if (this.active && elapsed > 0) {
            final long room = this.capacity - this.tokens;
            this.tokens = elapsed > room / this.rate ? this.capacity : this.tokens + elapsed * this.rate;
        }
        this.lastTick = current;
    }
}
