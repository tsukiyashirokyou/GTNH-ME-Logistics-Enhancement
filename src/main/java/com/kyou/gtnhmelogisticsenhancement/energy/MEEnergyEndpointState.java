package com.kyou.gtnhmelogisticsenhancement.energy;

import java.util.Objects;

import net.minecraft.nbt.NBTBase;

/** Per-MTE metadata and runtime permissions. EU exists only in BaseMetaTileEntity. */
public final class MEEnergyEndpointState {

    private final MEEnergySpec spec;
    private final MEEnergyRateBudget rateBudget;
    private MEEnergyLossAccount lossAccount;
    private String diagnostic = "";
    private NBTBase invalidData;
    private boolean stopped = true;
    private long revision;

    public MEEnergyEndpointState(final MEEnergySpec spec) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.rateBudget = spec.role() == MEEnergySpec.Role.DYNAMO
            ? new MEEnergyRateBudget(spec.power(), spec.capacity())
            : null;
        this.lossAccount = this.rateBudget == null ? null : new MEEnergyLossAccount();
    }

    static int tier(final MEEnergySpec spec, final MEEnergySpec.Role role, final MEEnergySpec.Family family) {
        Objects.requireNonNull(spec, "spec");
        if (spec.role() != role || spec.family() != family) {
            throw new IllegalArgumentException("Spec does not match the native hatch class");
        }
        return spec.tier();
    }

    public MEEnergySpec spec() {
        return this.spec;
    }

    public MEEnergyLossAccount lossAccount() {
        if (this.lossAccount == null) throw new IllegalStateException("Only dynamos own rounding credit");
        return this.lossAccount;
    }

    public MEEnergyRateBudget rateBudget() {
        if (this.rateBudget == null) throw new IllegalStateException("Only dynamos own source rate budgets");
        return this.rateBudget;
    }

    public String diagnostic() {
        return this.diagnostic;
    }

    public long revision() {
        return this.revision;
    }

    public boolean isStopped() {
        return this.stopped;
    }

    /** Native first tick only establishes liveness; it grants neither EU nor active-channel tokens. */
    public void start() {
        if (this.stopped) {
            this.revision = Math.incrementExact(this.revision);
            this.stopped = false;
        }
    }

    /** Unload/removal invalidates runtime work but must retain world stock and source rounding credit. */
    public void stop() {
        if (!this.stopped) {
            this.revision = Math.incrementExact(this.revision);
            this.stopped = true;
        }
        if (this.rateBudget != null) this.rateBudget.reset(0);
    }

    public void configurationChanged() {
        this.revision = Math.incrementExact(this.revision);
    }

    void restore(final int credit, final String fault, final NBTBase evidence) {
        stop();
        this.revision = Math.incrementExact(this.revision);
        if (this.lossAccount != null) this.lossAccount = new MEEnergyLossAccount(credit);
        this.diagnostic = fault;
        this.invalidData = evidence == null ? null : evidence.copy();
    }

    NBTBase invalidData() {
        return this.invalidData;
    }
}
