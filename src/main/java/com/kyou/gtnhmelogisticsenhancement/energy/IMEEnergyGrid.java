package com.kyou.gtnhmelogisticsenhancement.energy;

import appeng.api.networking.IGridCache;

/** Per-AE-Grid derived membership service. It owns no EU account or persistent transfer state. */
public interface IMEEnergyGrid extends IGridCache {

    long epoch(int tier);

    int memberCount();

    int activeCount(MEEnergySpec.Role role, int tier, int color);

    MEEnergyGridCache.Member first(MEEnergySpec.Role role, int tier, int color);
}
