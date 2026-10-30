package com.kyou.gtnhmelogisticsenhancement.distributed;

import java.util.function.BooleanSupplier;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEStackType;
import appeng.util.Platform;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Shared only by distributed outputs; native poweredInsert retains actual-quantity charging. */
public final class DistributedOutputEnergy {

    private static final DistributedEnergyBudget SERVER = new DistributedEnergyBudget();

    private DistributedOutputEnergy() {}

    public static void init() {
        FMLCommonHandler.instance()
            .bus()
            .register(new ServerTicks());
    }

    public static void clear() {
        SERVER.clear();
    }

    static <T extends IAEStack<T>> T insert(final Object grid, final IEnergyGrid energy, final BooleanSupplier valid,
        final IMEInventory<T> monitor, final T offered, final IAEStackType<T> type, final BaseActionSource source) {
        final double multiplier = PowerMultiplier.CONFIG.multiply(1);
        final long amount = SERVER.authorize(grid, new DistributedEnergyBudget.Power() {

            @Override
            public boolean valid() {
                return valid.getAsBoolean();
            }

            @Override
            public boolean infinite() {
                return energy.getHasInfiniteStore();
            }

            @Override
            public double stored() {
                return energy.getStoredPower();
            }

            @Override
            public double capacity() {
                return energy.getMaxStoredPower();
            }

            @Override
            public double idle() {
                return energy.getIdlePowerUsage() * multiplier;
            }

            @Override
            public double available(final double physicalRequest) {
                return energy.extractAEPower(physicalRequest, Actionable.SIMULATE, PowerMultiplier.ONE);
            }
        }, offered.getStackSize(), offered.getAmountPerUnit(), multiplier);
        if (amount == 0 || !valid.getAsBoolean()) return offered;
        final T limited = offered.copy()
            .setStackSize(amount);
        final T returned = Platform
            .poweredInsert(energy, new CheckedStorageDestination<>(monitor, type, valid), limited, source);
        final long accepted = amount - (returned == null ? 0 : returned.getStackSize());
        if (accepted == offered.getStackSize()) return null;
        return offered.copy()
            .setStackSize(offered.getStackSize() - accepted);
    }

    public static final class ServerTicks {

        @SubscribeEvent
        public void tick(final TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.START) clear();
        }
    }
}
