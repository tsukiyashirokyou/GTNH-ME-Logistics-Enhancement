package com.kyou.gtnhmelogisticsenhancement.energy;

/** Main-thread native-stock commit. Quotas/tokens are permissions; only the two native fields contain EU. */
public final class MEEnergyTransfer {

    MEEnergyTransfer() {}

    private boolean committing;
    private long attempts;
    private long commits;
    private long reads;
    private long writes;
    private long rejected;
    private long stockLimited;
    private long rateLimited;
    private long spaceLimited;

    public long availableNet(final MEEnergyGridCache.Member source, final long now) {
        if (!source.isCurrent() || source.endpoint()
            .spec()
            .role() != MEEnergySpec.Role.DYNAMO) return 0;
        final IMEEnergyEndpoint host = source.endpoint();
        final long raw = host.readEU();
        this.reads++;
        if (raw < 0) {
            this.stockLimited++;
            return 0;
        }
        final long gross = host.energyState()
            .rateBudget()
            .availableGross(
                raw,
                host.spec()
                    .nativeReserve(),
                now);
        if (gross == 0) {
            if (Math.min(
                raw,
                host.spec()
                    .capacity())
                <= host.spec()
                    .nativeReserve())
                this.stockLimited++;
            else this.rateLimited++;
        }
        return host.energyState()
            .lossAccount()
            .maximumNet(gross);
    }

    public long freeSpace(final MEEnergyGridCache.Member target) {
        final long raw = target.endpoint()
            .readEU();
        this.reads++;
        if (raw < 0 || raw >= target.endpoint()
            .spec()
            .capacity()) return 0;
        return target.endpoint()
            .spec()
            .capacity() - raw;
    }

    /** Returns actual net EU, never a simulated acceptance. All checks precede the first write. */
    public long commit(final MEEnergyGridCache cache, final long epoch, final MEEnergyGridCache.Member source,
        final MEEnergyGridCache.Member target, final long limit, final long now) {
        this.attempts++;
        if (this.committing || limit <= 0
            || now != MEEnergyTickClock.now()
            || source.cache != cache
            || target.cache != cache
            || source.endpoint()
                .spec()
                .role() != MEEnergySpec.Role.DYNAMO
            || target.endpoint()
                .spec()
                .role() != MEEnergySpec.Role.ENERGY
            || source.endpoint()
                .spec()
                .tier()
                != target.endpoint()
                    .spec()
                    .tier()
            || !cache.isEpochValid(
                source.endpoint()
                    .spec()
                    .tier(),
                epoch)
            || !source.isCurrent()
            || !target.isCurrent()
            || !source.endpoint()
                .machine()
                .getBaseMetaTileEntity()
                .isServerSide()
            || !target.endpoint()
                .machine()
                .getBaseMetaTileEntity()
                .isServerSide()
            || !MEEnergyGroupPlanner.matches(
                source.endpoint()
                    .businessColor(),
                target.endpoint()
                    .businessColor())) {
            this.rejected++;
            return 0;
        }
        this.committing = true;
        try {
            final IMEEnergyEndpoint from = source.endpoint(), to = target.endpoint();
            final long rawSource = from.readEU(), rawTarget = to.readEU();
            this.reads += 2;
            if (rawSource < 0 || rawTarget < 0) {
                this.stockLimited++;
                return 0;
            }
            final long free = rawTarget >= to.spec()
                .capacity() ? 0
                    : to.spec()
                        .capacity() - rawTarget;
            if (free == 0) {
                this.spaceLimited++;
                return 0;
            }
            final MEEnergyRateBudget budget = from.energyState()
                .rateBudget();
            final MEEnergyLossAccount loss = from.energyState()
                .lossAccount();
            final long gross = budget.availableGross(
                rawSource,
                from.spec()
                    .nativeReserve(),
                now);
            if (gross == 0) {
                if (Math.min(
                    rawSource,
                    from.spec()
                        .capacity())
                    <= from.spec()
                        .nativeReserve())
                    this.stockLimited++;
                else this.rateLimited++;
                return 0;
            }
            final long net = Math.min(limit, Math.min(free, loss.maximumNet(gross)));
            if (net <= 0) return 0;
            final long debit = loss.grossCost(net);
            // Native final MTE classes use MetaTileEntity.setEUVar; no third-party EU injection callbacks.
            // Reentrant commit attempts during native markDirty are rejected by the cache-owned guard.
            from.machine()
                .setEUVar(rawSource - debit);
            loss.record(net, debit);
            if (!budget.spend(debit, now))
                throw new IllegalStateException("Validated gross budget changed during commit");
            to.machine()
                .setEUVar(rawTarget + net);
            cache.recordAdmission(target, net);
            if (net == free) cache.sleepTarget(target);
            this.writes += 2;
            this.commits++;
            return net;
        } finally {
            this.committing = false;
        }
    }

    public long attempts() {
        return this.attempts;
    }

    public long commits() {
        return this.commits;
    }

    public long reads() {
        return this.reads;
    }

    public long writes() {
        return this.writes;
    }

    public long rejected() {
        return this.rejected;
    }

    public long stockLimited() {
        return this.stockLimited;
    }

    public long rateLimited() {
        return this.rateLimited;
    }

    public long spaceLimited() {
        return this.spaceLimited;
    }
}
