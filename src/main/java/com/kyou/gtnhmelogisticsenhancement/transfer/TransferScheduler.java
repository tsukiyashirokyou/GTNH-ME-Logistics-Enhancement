package com.kyou.gtnhmelogisticsenhancement.transfer;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.function.LongSupplier;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Main-thread FIFO work slices. Time limits are checked between indivisible AE operations, never inside a transaction.
 */
public final class TransferScheduler {

    // A vanilla tick has 50 ms: reserve at most 4% for starting transfer work, at most 1% for one device.
    static final long SERVER_NANOS = 2_000_000;
    static final long DEVICE_NANOS = 500_000;
    // The smallest continuation allowance above the old one-transaction rule. At most four source NBT saves on
    // rollback.
    static final int TRANSACTIONS = 2;
    // At most one new cell's <=63-entry snapshot, four candidate probes, and one group's worth of cursor transitions.
    static final int STEPS = 8;
    private static final TransferScheduler SERVER = new TransferScheduler(System::nanoTime);

    interface Work {

        boolean run(Slice budget);
    }

    static final class Slice {

        private final LongSupplier nanos;
        private final long serverStart;
        private final long deviceStart;
        private int steps;
        private int transactions;
        private int openedCells;
        private int probes;

        Slice(final LongSupplier nanos, final long serverStart, final long deviceStart) {
            this.nanos = nanos;
            this.serverStart = serverStart;
            this.deviceStart = deviceStart;
        }

        boolean nextStep() {
            return this.steps++ < STEPS && this.transactions < TRANSACTIONS && this.hasTime();
        }

        boolean openCell() {
            return this.openedCells++ == 0 && this.hasTime();
        }

        boolean probeType() {
            return this.probes++ < 4 && this.hasTime();
        }

        boolean transaction() {
            if (this.transactions >= TRANSACTIONS || !this.hasTime()) {
                return false;
            }
            this.transactions++;
            return true;
        }

        boolean hasTime() {
            final long now = this.nanos.getAsLong();
            return now - this.deviceStart < DEVICE_NANOS && now - this.serverStart < SERVER_NANOS;
        }
    }

    private final LongSupplier nanos;
    private final ArrayDeque<Entry> queue = new ArrayDeque<>();
    private final IdentityHashMap<Work, Entry> queued = new IdentityHashMap<>();
    private final IdentityHashMap<Work, Boolean> served = new IdentityHashMap<>();

    private static final class Entry {

        final Work work;

        Entry(final Work work) {
            this.work = work;
        }
    }

    TransferScheduler(final LongSupplier nanos) {
        this.nanos = nanos;
    }

    public static void init() {
        FMLCommonHandler.instance()
            .bus()
            .register(new ServerTicks());
    }

    public static void clear() {
        SERVER.queue.clear();
        SERVER.queued.clear();
        SERVER.served.clear();
        TransferPowerBudget.SERVER.clear();
    }

    static void enqueue(final Work work) {
        SERVER.submit(work);
    }

    static void remove(final Work work) {
        SERVER.cancel(work);
    }

    void submit(final Work work) {
        if (!this.queued.containsKey(work)) {
            final Entry entry = new Entry(work);
            this.queued.put(work, entry);
            this.queue.addLast(entry);
        }
    }

    void cancel(final Work work) {
        final Entry entry = this.queued.remove(work);
        if (entry != null) {
            this.queue.remove(entry);
        }
    }

    void runTick() {
        final long start = this.nanos.getAsLong();
        TransferPowerBudget.SERVER.clear();
        this.served.clear();
        try {
            while (!this.queue.isEmpty()) {
                final long now = this.nanos.getAsLong();
                final Entry entry = this.queue.peekFirst();
                // Also correct if a synchronous callback cancels other waiting devices during this slice.
                if (now - start >= SERVER_NANOS || this.served.containsKey(entry.work)) {
                    break;
                }
                this.queue.removeFirst();
                this.served.put(entry.work, Boolean.TRUE);
                boolean again = false;
                try {
                    again = entry.work.run(new Slice(this.nanos, start, now));
                } finally {
                    if (this.queued.get(entry.work) == entry) {
                        if (again) {
                            this.queue.addLast(entry);
                        } else {
                            this.queued.remove(entry.work);
                        }
                    }
                }
            }
        } finally {
            this.served.clear();
            TransferPowerBudget.SERVER.clear();
        }
    }

    public static final class ServerTicks {

        @SubscribeEvent
        public void tick(final TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                SERVER.runTick();
            }
        }
    }
}
