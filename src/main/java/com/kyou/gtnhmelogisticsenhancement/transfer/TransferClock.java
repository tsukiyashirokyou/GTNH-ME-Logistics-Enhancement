package com.kyou.gtnhmelogisticsenhancement.transfer;

/** Countdown only; callers perform bounded work on the server tick. */
public final class TransferClock {

    public static final int DEFAULT_SECONDS = 60;
    public static final int MIN_SECONDS = 1;
    public static final int MAX_SECONDS = 86400;

    private int seconds = DEFAULT_SECONDS;
    private int ticksLeft = DEFAULT_SECONDS * 20;

    public void setSeconds(final int value) {
        if (value < MIN_SECONDS || value > MAX_SECONDS) {
            throw new IllegalArgumentException("Transfer period must be between 1 and 86400 seconds");
        }
        this.seconds = value;
        this.reset();
    }

    public boolean tick() {
        if (--this.ticksLeft <= 0) {
            this.reset();
            return true;
        }
        return false;
    }

    public void reset() {
        this.ticksLeft = this.seconds * 20;
    }

    public int getSeconds() {
        return this.seconds;
    }
}
