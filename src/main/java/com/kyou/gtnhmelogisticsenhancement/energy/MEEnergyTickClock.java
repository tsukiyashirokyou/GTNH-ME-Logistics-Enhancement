package com.kyou.gtnhmelogisticsenhancement.energy;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** One shared SERVER/START game-tick counter, never wall time or a dimension's mutable World time. */
public final class MEEnergyTickClock {

    private static long tick;

    public static long now() {
        return tick;
    }

    static void reset() {
        tick = 0;
    }

    @SubscribeEvent
    public void tick(final TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) tick = Math.incrementExact(tick);
    }
}
