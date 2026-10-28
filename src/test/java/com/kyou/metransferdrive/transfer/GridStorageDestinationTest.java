package com.kyou.metransferdrive.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEStackType;
import appeng.me.storage.MEInventoryHandler;
import appeng.me.storage.NetworkInventoryHandler;
import appeng.util.prioitylist.IPartitionList;

/** Runs the actual pinned AE2 priority algorithm with monitor/backend doubles, without starting Minecraft. */
@SuppressWarnings({ "rawtypes", "unchecked" })
public class GridStorageDestinationTest {

    private final IAEStackType<?> itemType = type("items", 1);
    private final IAEStack<?> identity = stack(this.itemType, IAEItemStack.class, "item", 1);
    private final Object origin = new Object();
    private final BaseActionSource actionSource = new BaseActionSource();
    private final TransferTransaction<IAEStack<?>> transaction = new TransferTransaction<>(() -> {});
    private final Source source = new Source();

    @Test
    public void nativeStorageReceivesThroughTheMonitor() {
        final Storage nativeStorage = new Storage();
        final Monitor monitor = new Monitor(nativeStorage.endpoint);
        assertEquals(80, this.move(this.identity, monitor, 80));
        assertEquals(80, nativeStorage.stored);
        assertEquals(1, monitor.simulations);
        assertEquals(1, monitor.insertions);
    }

    @Test
    public void externalHandlerBehindAStorageBusStyleWrapperReceivesWithoutInspection() {
        final Storage externalContainer = new Storage();
        final Monitor monitor = new Monitor(externalContainer.busAdapter());
        assertEquals(80, this.move(this.identity, monitor, 80));
        assertEquals(80, externalContainer.stored);
        assertFalse(this.transaction.isPending());
    }

    @Test
    public void externalFluidHandlerUsesTheSameInjectionPath() {
        final IAEStackType<?> fluidType = type("fluids", 1000);
        final IAEStack<?> fluid = stack(fluidType, IAEFluidStack.class, "fluid", 1);
        final Storage externalTank = new Storage();
        final Monitor monitor = new Monitor(fluidType, externalTank.busAdapter());
        assertEquals(80, this.move(fluid, monitor, 80));
        assertEquals(80, externalTank.stored);
    }

    @Test
    public void essentiaUsesTheSameNativeNetworkPathWithPartialReceptionAndRollback() {
        final IAEStackType<?> type = type("essentia", 1);
        final IAEStack<?> aspect = stack(type, IAEStack.class, "aer", 1);
        assertFalse(aspect.isItem());
        assertFalse(aspect.isFluid());
        final Storage external = new Storage();
        external.simulatedCapacity = 80;
        external.capacity = 30;
        final Monitor monitor = new Monitor(type, external.busAdapter());
        assertEquals(30, this.move(aspect, monitor, 80));
        assertEquals(70, this.source.count);
        assertEquals(30, external.stored);
        assertFalse(this.transaction.isPending());
    }

    @Test
    public void essentiaRollbackFailureKeepsKnownRemainderAndCannotReplay() {
        final IAEStackType<?> type = type("essentia", 1);
        final IAEStack<?> aspect = stack(type, IAEStack.class, "aer", 1);
        final Storage external = new Storage();
        external.simulatedCapacity = 80;
        external.capacity = 30;
        final Monitor monitor = new Monitor(type, external.busAdapter());
        this.source.restoreLimit = 0;
        assertEquals(30, this.move(aspect, monitor, 80));
        assertEquals(TransferTransaction.Phase.RECOVERABLE, this.transaction.getPhase());
        assertEquals(50, this.transaction.getHeld());
        assertEquals(0, this.move(aspect, monitor, 80));
        assertEquals(1, monitor.insertions);
        assertEquals(100, this.source.count + external.stored + this.transaction.getHeld());
        this.source.restoreLimit = 100;
        this.transaction.retryReturn(this.source);
        assertEquals(70, this.source.count);
        assertFalse(this.transaction.isPending());
    }

    @Test
    public void essentiaRejectsAnotherAspectAndOtherStorageTypesAsRemainders() {
        final IAEStackType<?> type = type("essentia", 1);
        final IAEStack<?> aspect = stack(type, IAEStack.class, "aer", 1);
        for (final IAEStack<?> result : new IAEStack<?>[] { stack(type, IAEStack.class, "ignis", 5), this.identity,
            stack(type, IAEStack.class, "aer", -1), stack(type, IAEStack.class, "aer", 81) }) {
            try {
                GridStorageDestination.checkedQuantity(aspect, result, 80);
                throw new AssertionError("Accepted invalid essentia result");
            } catch (IllegalStateException expected) {
                // An actual invalid result is quarantined by the shared transaction algorithm.
            }
        }
    }

    @Test
    public void actualAE2PriorityRoutesToExternalStorageBeforeLowerPriorityNativeStorage() {
        final Storage external = new Storage();
        external.priority = 100;
        external.capacity = 30;
        final Storage nativeStorage = new Storage();
        // Deliberately register in reverse priority order. AE2 performs the ordering and both insertion passes.
        final Monitor monitor = new Monitor(nativeStorage.endpoint, external.busAdapter());
        assertEquals(80, this.move(this.identity, monitor, 80));
        assertEquals(30, external.stored);
        assertEquals(50, nativeStorage.stored);
    }

    @Test
    public void backendCategoryDoesNotOverrideNativePriority() {
        final Storage nativeStorage = new Storage();
        nativeStorage.priority = 100;
        nativeStorage.capacity = 25;
        final Storage external = new Storage();
        final Monitor monitor = new Monitor(external.busAdapter(), nativeStorage.endpoint);
        assertEquals(80, this.move(this.identity, monitor, 80));
        assertEquals(25, nativeStorage.stored);
        assertEquals(55, external.stored);
    }

    @Test
    public void filterRejectionDoesNotExtractOrPermanentlyBanTheBackend() {
        final Storage external = new Storage();
        final AtomicBoolean listed = new AtomicBoolean(false);
        final MEInventoryHandler bus = external.busAdapter();
        bus.setPartitionList(
            (IPartitionList) Proxy.newProxyInstance(
                IPartitionList.class.getClassLoader(),
                new Class<?>[] { IPartitionList.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isEmpty":
                            return false;
                        case "isListed":
                            return listed.get();
                        default:
                            throw new AssertionError("Unexpected filter call: " + method.getName());
                    }
                }));
        final Monitor monitor = new Monitor(bus);
        assertEquals(0, this.move(this.identity, monitor, 80));
        assertEquals(100, this.source.count);
        listed.set(true);
        assertEquals(80, this.move(this.identity, monitor, 80));
        assertEquals(80, external.stored);
    }

    @Test
    public void externalStorageAccessModeIsEnforcedByAE2() {
        final Storage external = new Storage();
        final MEInventoryHandler bus = external.busAdapter();
        bus.setBaseAccess(AccessRestriction.READ);
        final Monitor monitor = new Monitor(bus);
        assertEquals(0, this.move(this.identity, monitor, 80));
        assertEquals(100, this.source.count);
        bus.setBaseAccess(AccessRestriction.WRITE);
        assertEquals(80, this.move(this.identity, monitor, 80));
        assertEquals(80, external.stored);
    }

    @Test
    public void partialActualReceptionReturnsOnlyTheRemainderToTheCell() {
        final Storage external = new Storage();
        external.simulatedCapacity = 80;
        external.capacity = 30;
        final Monitor monitor = new Monitor(external.busAdapter());
        assertEquals(30, this.move(this.identity, monitor, 80));
        assertEquals(70, this.source.count);
        assertEquals(30, external.stored);
        assertFalse(this.transaction.isPending());
    }

    @Test
    public void configuredVoidReceptionIsAcceptedAndNeverRefundedAsIfRejected() {
        final Storage external = new Storage();
        external.capacity = 0;
        external.voidOverflow = true;
        final Monitor monitor = new Monitor(external.busAdapter());
        assertEquals(80, this.move(this.identity, monitor, 80));
        assertEquals(20, this.source.count);
        assertEquals(0, external.stored);
        assertEquals(80, external.voided);
        assertFalse(this.transaction.isPending());
    }

    @Test
    public void invalidActualReturnStillQuarantinesTheTransaction() {
        final Storage external = new Storage();
        external.invalidReturn = true;
        final Monitor monitor = new Monitor(external.busAdapter());
        this.move(this.identity, monitor, 80);
        assertEquals(TransferTransaction.Phase.UNCERTAIN, this.transaction.getPhase());
        assertEquals(TransferTransaction.Phase.INSERTING, this.transaction.getUncertainOperation());
        assertEquals(20, this.source.count);
        assertEquals(0, this.move(this.identity, monitor, 80));
        assertEquals(1, monitor.insertions);
        assertFalse(TransferInjectionGuard.blocks(this.origin));
    }

    @Test
    public void monitorInterceptionIsNotBypassed() {
        final Storage otherwiseRejecting = new Storage();
        otherwiseRejecting.accepts = false;
        final Monitor monitor = new Monitor(otherwiseRejecting.endpoint);
        monitor.intercepts = true;
        assertEquals(80, this.move(this.identity, monitor, 80));
        assertEquals(80, monitor.intercepted);
        assertEquals(0, otherwiseRejecting.stored);
    }

    @Test
    public void sourceFeedbackIsSkippedWhileOtherExternalStorageRemainsWritable() {
        final Storage feedback = new Storage();
        feedback.priority = 100;
        feedback.feedbackOrigin = this.origin;
        final Storage external = new Storage();
        final Monitor monitor = new Monitor(feedback.busAdapter(), external.busAdapter());
        assertEquals(80, this.move(this.identity, monitor, 80));
        assertEquals(0, feedback.stored);
        assertEquals(80, external.stored);
        assertFalse(TransferInjectionGuard.blocks(this.origin));
    }

    @Test
    public void monitorReplacementAfterSimulationUsesCurrentTargetStorage() {
        final Monitor original = new Monitor(new Storage().endpoint);
        final Storage replacementStorage = new Storage();
        final Monitor replacement = new Monitor(replacementStorage.busAdapter());
        final AtomicReference<IMEMonitor<?>> current = new AtomicReference<>(original.endpoint);
        this.source.afterExtract = () -> current.set(replacement.endpoint);
        assertEquals(
            80,
            this.transaction
                .move(this.identity, 80, this.source, this.destination(this.identity, current), amount -> amount));
        assertEquals(0, original.insertions);
        assertEquals(80, replacementStorage.stored);
    }

    @Test
    public void targetGoingOfflineAfterExtractionRollsBackWithoutBackendBlacklisting() {
        final Monitor monitor = new Monitor(new Storage().busAdapter());
        final AtomicReference<IMEMonitor<?>> current = new AtomicReference<>(monitor.endpoint);
        this.source.afterExtract = () -> current.set(null);
        assertEquals(
            0,
            this.transaction
                .move(this.identity, 80, this.source, this.destination(this.identity, current), amount -> amount));
        assertEquals(100, this.source.count);
        assertFalse(this.transaction.isPending());
    }

    @Test
    public void multiTargetFailoverUsesNativeMonitorInjectionAndOnlyTheRemainingQuantity() {
        final Storage external = new Storage();
        external.simulatedCapacity = 80;
        external.capacity = 30;
        final Monitor first = new Monitor(external.busAdapter());
        final Storage nativeStorage = new Storage();
        final Monitor second = new Monitor(nativeStorage.endpoint);
        final RoutedTransfer<IAEStack<?>, Monitor> route = this.route(first, second);
        assertEquals(30, this.routeStep(route));
        assertEquals(50, route.getRemaining());
        assertEquals(50, this.routeStep(route));
        assertEquals(30, external.stored);
        assertEquals(50, nativeStorage.stored);
        assertEquals(20, this.source.count);
        assertFalse(TransferInjectionGuard.blocks(this.origin));
        assertTrue(route.isFinished());
    }

    @Test
    public void invalidExternalMonitorResultBlocksTheNextNetworkWithoutLeakingTheGuard() {
        final Storage broken = new Storage();
        broken.invalidReturn = true;
        final Monitor first = new Monitor(broken.busAdapter());
        final Monitor second = new Monitor(new Storage().endpoint);
        final RoutedTransfer<IAEStack<?>, Monitor> route = this.route(first, second);
        this.routeStep(route);
        this.routeStep(route);
        assertEquals(TransferTransaction.Phase.UNCERTAIN, this.transaction.getPhase());
        assertEquals(0, second.simulations);
        assertEquals(0, second.insertions);
        assertFalse(TransferInjectionGuard.blocks(this.origin));
    }

    @Test
    public void topologyChangeBetweenSimulationAndInsertionReturnsTheBatchAndStopsTheOldRoute() {
        final Monitor first = new Monitor(new Storage().busAdapter());
        final Monitor second = new Monitor(new Storage().endpoint);
        final AtomicReference<Monitor> firstGrid = new AtomicReference<>(first);
        final TargetRegistry registry = this.registry();
        // A non-null source and independently mutable grid references model topology, not Minecraft worlds.
        final Monitor sourceMonitor = new Monitor(new Storage().endpoint);
        final RoutingCycle<Monitor> actual = new RoutingCycle<>(
            registry,
            () -> sourceMonitor,
            binding -> binding.getX() == 0 ? new RoutingCycle.Endpoint<>(first, firstGrid::get, () -> true)
                : new RoutingCycle.Endpoint<>(second, () -> second, () -> true));
        actual.prepare(4);
        final RoutedTransfer<IAEStack<?>, Monitor> route = new RoutedTransfer<>(this.identity, 80, actual, 0);
        this.source.afterExtract = () -> firstGrid.set(second);
        assertEquals(
            0,
            route.step(
                80,
                this.transaction,
                this.source,
                target -> new GridStorageDestination(
                    this.identity,
                    this.origin,
                    this.actionSource,
                    () -> actual.isTopologyValid() ? target.getGrid().endpoint : null,
                    (result, maximum) -> GridStorageDestination.checkedQuantity(this.identity, result, maximum)),
                amount -> amount));
        assertEquals(100, this.source.count);
        assertEquals(0, first.insertions);
        assertFalse(this.transaction.isPending());
        assertEquals(0, this.routeStep(route));
        assertEquals(0, second.insertions);
    }

    private TargetRegistry registry() {
        final TargetRegistry registry = new TargetRegistry();
        registry.register(new TransferTarget(UUID.randomUUID(), 0, 0, 64, 0));
        registry.register(new TransferTarget(UUID.randomUUID(), 0, 1, 64, 0));
        registry.setRoute(0, TargetRegistry.Route.automatic());
        return registry;
    }

    private RoutedTransfer<IAEStack<?>, Monitor> route(final Monitor first, final Monitor second) {
        final Monitor sourceMonitor = new Monitor(new Storage().endpoint);
        final RoutingCycle<Monitor> cycle = new RoutingCycle<>(this.registry(), () -> sourceMonitor, binding -> {
            final Monitor grid = binding.getX() == 0 ? first : second;
            return new RoutingCycle.Endpoint<>(grid, () -> grid, () -> true);
        });
        cycle.prepare(4);
        return new RoutedTransfer<>(this.identity, 80, cycle, 0);
    }

    private long routeStep(final RoutedTransfer<IAEStack<?>, Monitor> route) {
        return route.step(
            80,
            this.transaction,
            this.source,
            target -> this.destination(this.identity, new AtomicReference<>(target.getGrid().endpoint)),
            amount -> amount);
    }

    @Test(expected = IllegalStateException.class)
    public void negativeRemainderIsInvalid() {
        GridStorageDestination.checkedQuantity(this.identity, stack(this.itemType, IAEItemStack.class, "item", -1), 80);
    }

    @Test(expected = IllegalStateException.class)
    public void wrongIdentityIsInvalid() {
        GridStorageDestination
            .checkedQuantity(this.identity, stack(this.itemType, IAEItemStack.class, "other_item", 1), 80);
    }

    private long move(final IAEStack<?> key, final Monitor monitor, final long amount) {
        return this.transaction.move(
            key,
            amount,
            this.source,
            this.destination(key, new AtomicReference<>(monitor.endpoint)),
            available -> available);
    }

    private GridStorageDestination destination(final IAEStack<?> key, final AtomicReference<IMEMonitor<?>> current) {
        return new GridStorageDestination(
            key,
            this.origin,
            this.actionSource,
            current::get,
            (result, maximum) -> GridStorageDestination.checkedQuantity(key, result, maximum));
    }

    private final class Monitor implements InvocationHandler {

        final NetworkInventoryHandler network;
        final IMEMonitor<?> endpoint;
        int simulations;
        int insertions;
        boolean intercepts;
        long intercepted;

        Monitor(final IMEInventoryHandler... storage) {
            this(itemType, storage);
        }

        Monitor(final IAEStackType<?> stackType, final IMEInventoryHandler... storage) {
            this.network = new NetworkInventoryHandler(stackType, null);
            for (final IMEInventoryHandler handler : storage) {
                this.network.addNewStorage(handler);
            }
            this.endpoint = (IMEMonitor<?>) Proxy
                .newProxyInstance(IMEMonitor.class.getClassLoader(), new Class<?>[] { IMEMonitor.class }, this);
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) {
            if (!method.getName()
                .equals("injectItems")) {
                throw new AssertionError("Unexpected monitor call: " + method.getName());
            }
            final IAEStack<?> input = (IAEStack<?>) args[0];
            final Actionable mode = (Actionable) args[1];
            assertSame(actionSource, args[2]);
            assertTrue(TransferInjectionGuard.blocks(origin));
            if (mode == Actionable.MODULATE) {
                this.insertions++;
            } else {
                this.simulations++;
            }
            if (this.intercepts) {
                if (mode == Actionable.MODULATE) {
                    this.intercepted += input.getStackSize();
                }
                return null;
            }
            return this.network.injectItems(input, mode, (BaseActionSource) args[2]);
        }
    }

    private static final class Source implements TransferTransaction.Source {

        long count = 100;
        long restoreLimit = 100;
        Runnable afterExtract = () -> {};

        @Override
        public long extract(final long amount) {
            final long removed = Math.min(amount, this.count);
            this.count -= removed;
            this.afterExtract.run();
            return removed;
        }

        @Override
        public long restore(final long amount) {
            final long restored = Math.min(amount, this.restoreLimit);
            this.count += restored;
            return restored;
        }
    }

    /** A backend contract double, not a real storage bus or third-party container. */
    private static final class Storage implements InvocationHandler {

        final IMEInventoryHandler endpoint;
        long capacity = 100;
        long simulatedCapacity = -1;
        long stored;
        long voided;
        int priority;
        boolean accepts = true;
        boolean voidOverflow;
        boolean invalidReturn;
        Object feedbackOrigin;

        Storage() {
            this.endpoint = (IMEInventoryHandler) Proxy.newProxyInstance(
                IMEInventoryHandler.class.getClassLoader(),
                new Class<?>[] { IMEInventoryHandler.class },
                this);
        }

        MEInventoryHandler busAdapter() {
            final MEInventoryHandler wrapper = new MEInventoryHandler(this.endpoint, null);
            wrapper.setPriority(this.priority);
            return wrapper;
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) {
            switch (method.getName()) {
                case "getPriority":
                    return this.priority;
                case "getAccess":
                    return AccessRestriction.READ_WRITE;
                case "getSlot":
                    return 0;
                case "getSticky":
                case "isAutoCraftingInventory":
                case "isPrioritized":
                    return false;
                case "validForPass":
                    return (int) args[0] == 2;
                case "canAccept":
                    return this.accepts && !TransferInjectionGuard.blocks(this.feedbackOrigin);
                case "extractItems":
                    return null;
                case "injectItems":
                    final IAEStack input = (IAEStack) args[0];
                    final Actionable mode = (Actionable) args[1];
                    final long maximum = mode == Actionable.SIMULATE && this.simulatedCapacity >= 0
                        ? this.simulatedCapacity
                        : this.capacity - this.stored;
                    final long accepted = Math.min(input.getStackSize(), Math.max(0, maximum));
                    if (mode == Actionable.MODULATE) {
                        this.stored += accepted;
                        if (this.voidOverflow) {
                            this.voided += input.getStackSize() - accepted;
                        }
                        if (this.invalidReturn) {
                            return input.copy()
                                .setStackSize(input.getStackSize() + 1);
                        }
                    }
                    return this.voidOverflow || accepted == input.getStackSize() ? null
                        : input.copy()
                            .setStackSize(input.getStackSize() - accepted);
                default:
                    throw new AssertionError("Backend inspection is forbidden: " + method.getName());
            }
        }
    }

    static IAEStackType<?> type(final String id, final int amountPerUnit) {
        return (IAEStackType<?>) Proxy.newProxyInstance(
            IAEStackType.class.getClassLoader(),
            new Class<?>[] { IAEStackType.class },
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getId":
                        return id;
                    case "getAmountPerUnit":
                        return amountPerUnit;
                    default:
                        throw new AssertionError("Unexpected stack type call: " + method.getName());
                }
            });
    }

    static IAEStack<?> stack(final IAEStackType<?> type, final Class<?> contract, final String key, final long amount) {
        return (IAEStack<?>) Proxy.newProxyInstance(
            contract.getClassLoader(),
            new Class<?>[] { contract },
            new StackValue(type, contract, key, amount));
    }

    private static final class StackValue implements InvocationHandler {

        private final IAEStackType<?> type;
        private final Class<?> contract;
        private final String key;
        private long amount;

        StackValue(final IAEStackType<?> type, final Class<?> contract, final String key, final long amount) {
            this.type = type;
            this.contract = contract;
            this.key = key;
            this.amount = amount;
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) {
            switch (method.getName()) {
                case "isItem":
                    return this.contract == IAEItemStack.class;
                case "isFluid":
                    return this.contract == IAEFluidStack.class;
                case "writeToNBT":
                    final net.minecraft.nbt.NBTTagCompound tag = (net.minecraft.nbt.NBTTagCompound) args[0];
                    tag.setString("AspectTag", this.key);
                    tag.setLong("Cnt", this.amount);
                    return null;
                case "getStackSize":
                    return this.amount;
                case "setStackSize":
                    this.amount = (long) args[0];
                    return proxy;
                case "setCraftable":
                    return proxy;
                case "copy":
                    return stack(this.type, this.contract, this.key, this.amount);
                case "getStackType":
                    return this.type;
                case "isSameType":
                    final StackValue other = (StackValue) Proxy.getInvocationHandler(args[0]);
                    return this.type == other.type && this.key.equals(other.key);
                default:
                    throw new AssertionError("Unexpected stack call: " + method.getName());
            }
        }
    }
}
