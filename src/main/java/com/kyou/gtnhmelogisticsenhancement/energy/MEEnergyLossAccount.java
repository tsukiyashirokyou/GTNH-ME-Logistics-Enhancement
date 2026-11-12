package com.kyou.gtnhmelogisticsenhancement.energy;

/** Source-owned rounding credit. Simulation never changes it; no EU lives in this account. */
public final class MEEnergyLossAccount {

    private int credit;

    public MEEnergyLossAccount() {
        this(0);
    }

    public MEEnergyLossAccount(final int credit) {
        if (credit < 0 || credit >= 19) throw new IllegalArgumentException("Credit must be in [0,18]");
        this.credit = credit;
    }

    public int credit() {
        return this.credit;
    }

    public long maximumNet(final long availableGross) {
        nonNegative(availableGross);
        return (availableGross / 20) * 19 + ((availableGross % 20) * 19 + this.credit) / 20;
    }

    public long grossCost(final long net) {
        nonNegative(net);
        return Math.addExact(Math.addExact(net, net / 19), net % 19 > this.credit ? 1 : 0);
    }

    public int creditAfter(final long net) {
        nonNegative(net);
        final int remainder = (int) (net % 19);
        return this.credit - remainder + (remainder > this.credit ? 19 : 0);
    }

    /** Called only for the gross/net pair actually committed by the later TE transfer layer. */
    public void record(final long net, final long paidGross) {
        if (grossCost(net) != paidGross) throw new IllegalArgumentException("Gross payment does not match credit");
        this.credit = creditAfter(net);
    }

    private static void nonNegative(final long quantity) {
        if (quantity < 0) throw new IllegalArgumentException("EU amount must be nonnegative");
    }
}
