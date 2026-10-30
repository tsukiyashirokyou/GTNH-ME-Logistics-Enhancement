package com.kyou.gtnhmelogisticsenhancement.transfer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.kyou.gtnhmelogisticsenhancement.GTNHMELogisticsEnhancementMod;
import com.kyou.gtnhmelogisticsenhancement.tile.DriveSlotLayout;
import com.kyou.gtnhmelogisticsenhancement.tile.TileMETransferDrive;

import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.config.SecurityPermissions;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.security.MachineSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEStackType;
import appeng.api.storage.data.IItemList;
import appeng.me.GridAccessException;
import appeng.tile.misc.TileInterface;
import appeng.util.IterationCounter;

/** Server-thread adapter. It never asks the source grid for its inventory. */
@SuppressWarnings({ "rawtypes", "unchecked" })
public final class AETransferController {

    private static final int TARGETS_PER_TICK = 4;
    private final TileMETransferDrive tile;
    private final BaseActionSource actionSource;
    private final TransferClock clock = new TransferClock();
    private final TransferTransaction<IAEStack<?>> transaction;
    private final TransferEnergyAccount energyAccount;
    private TargetRegistry registry = new TargetRegistry();
    private RoutingCycle<IGrid> cycle;
    private RoutedTransfer<IAEStack<?>, IGrid> routed;
    private UUID transactionTargetId;
    private NBTTagCompound quarantinedRouting;
    private boolean active;
    private boolean ticking;
    private boolean recoveryDue;
    private final TransferScheduler.Work scheduledWork = this::runSlice;
    private int nextSlot;
    private int sourceSlot = -1;
    private ItemStack sourceCell;
    private IMEInventory sourceInventory;
    private Iterator<?> types;
    private IAEStack candidate;
    private NBTBase quarantinedRecord;
    private NBTTagCompound unexpectedReturn;
    private String status = "idle";
    private long routingRevision;
    private long witnessRevision;
    private RouteGraph<IGrid> graph;
    private IAEStackType<?> graphType;
    private int graphAge;
    private boolean blockedByCycle;
    private final Map<UUID, RoutingCycle.Status> lastTargetStatuses = new HashMap<>();

    public AETransferController(final TileMETransferDrive tile) {
        this.tile = tile;
        this.actionSource = new MachineSource(tile);
        this.transaction = new TransferTransaction<>(tile::markDirty);
        this.energyAccount = new TransferEnergyAccount(tile::markDirty, TransferPowerBudget.SERVER);
    }

    public void tick() {
        if (this.ticking) {
            return;
        }
        this.ticking = true;
        try {
            if (this.graph != null && this.graph.isComplete()) {
                this.graphAge++;
            }
            if (this.tile.hasCellInventoryQuarantine()) {
                this.stop("invalid_cell_inventory");
                return;
            }
            final boolean due = this.clock.tick();
            if (this.energyAccount.isPending() && !this.transaction.isPending()) {
                this.stop("recovery_UNCERTAIN");
                return;
            }
            if (this.transaction.isPending()) {
                if (this.transaction.getPhase() == TransferTransaction.Phase.RECOVERABLE && due) {
                    this.recoveryDue = true;
                    TransferScheduler.enqueue(this.scheduledWork);
                }
                this.status = "recovery_" + this.transaction.getPhase()
                    .name();
                return;
            }
            if (this.quarantinedRouting != null) {
                this.status = "invalid_routing_configuration";
                return;
            }
            if (!this.active) {
                if (!due || this.registry.isEmpty()) {
                    return;
                }
                this.active = true;
                this.nextSlot = 0;
                this.cycle = null;
                this.graph = null;
            }
            TransferScheduler.enqueue(this.scheduledWork);
        } catch (final RuntimeException failure) {
            this.stop("api_failure");
            GTNHMELogisticsEnhancementMod.LOG.warn("ME Transfer Drive stopped after an AE API failure", failure);
        } finally {
            this.ticking = false;
        }
    }

    /** The server END event services each queued device once; all AE calls remain on the game thread. */
    private boolean runSlice(final TransferScheduler.Slice budget) {
        if (this.ticking) {
            return this.active || this.recoveryDue;
        }
        this.ticking = true;
        try {
            if (this.tile.isInvalid() || this.tile.getWorldObj() == null
                || this.tile.getWorldObj().isRemote
                || !this.tile.getWorldObj()
                    .blockExists(this.tile.xCoord, this.tile.yCoord, this.tile.zCoord)
                || this.tile.getWorldObj()
                    .getTileEntity(this.tile.xCoord, this.tile.yCoord, this.tile.zCoord) != this.tile) {
                this.stop("suspended");
                return false;
            }
            if (this.transaction.isPending()) {
                if (this.energyAccount.isPending()) {
                    this.recoveryDue = false;
                    return false;
                }
                if (this.transaction.getPhase() != TransferTransaction.Phase.RECOVERABLE) {
                    this.recoveryDue = false;
                    return false;
                }
                if (this.recoveryDue && budget.transaction()) {
                    this.recoveryDue = false;
                    if (this.sameSource()) {
                        this.transaction.retryReturn(this.source(this.transaction.getKey()));
                    }
                    if (!this.transaction.isPending()) {
                        this.stop("recovered_to_source");
                        this.topologyChanged();
                    }
                }
                return this.recoveryDue;
            }
            if (!this.active) {
                return false;
            }
            if (this.energyAccount.isPending()) {
                this.stop("recovery_UNCERTAIN");
                return false;
            }
            if (this.tile.hasCellInventoryQuarantine() || this.quarantinedRouting != null) {
                this.stop("invalid_routing_configuration");
                return false;
            }
            if (!this.tile.getProxy()
                .isActive()) {
                this.stop("source_offline");
                return false;
            }
            if (!budget.hasTime()) {
                return true;
            }
            if (this.cycle == null) {
                this.cycle = new RoutingCycle<>(this.registry, this::sourceGrid, this::resolveEndpoint);
            }
            if (!this.cycle.isPrepared()) {
                this.cycle.prepare(TARGETS_PER_TICK);
                this.status = "resolving_targets";
                return true;
            }
            while (this.active && budget.nextStep()) {
                if (this.types == null) {
                    if (!budget.openCell()) {
                        break;
                    }
                    this.openNextSlot();
                    continue;
                }
                if (!this.sameSource()) {
                    this.finishSlot();
                    continue;
                }
                if (this.candidate == null && !this.nextType(budget)) {
                    if (this.types != null) {
                        break;
                    }
                    continue;
                }
                // Admit validation + transfer together: expensive proofs must not starve an already admitted attempt.
                if (!budget.transaction()) {
                    break;
                }
                if (!this.cycle.isTopologyValid()) {
                    this.stop("topology_changed_waiting_next_cycle");
                    break;
                }
                // Every transaction keeps its own fresh graph/topology checks, including within the same Tick.
                if (!this.prepareGraph()) {
                    break;
                }
                final IAEStackType<?> type = this.sourceInventory.getStackType();
                final IAEStack<?> identity = this.candidate.copy()
                    .setStackSize(1)
                    .setCraftable(false);
                final IAEStack current = this.sourceInventory
                    .getAvailableItem((IAEStack) identity, IterationCounter.fetchNewId());
                this.routed.limitRemaining(this.quantity(identity, current, Long.MAX_VALUE));
                if (this.routed.isFinished()) {
                    this.candidate = null;
                    this.routed = null;
                    continue;
                }
                this.unexpectedReturn = null;
                this.blockedByCycle = false;
                final long delivered = this.routed
                    .step(TransferStorageTypes.batchLimit(type), this.transaction, this.source(identity), target -> {
                        this.transactionTargetId = target.getBinding()
                            .getId();
                        return this.destination(identity, target);
                    }, this.energyFor(type.getAmountPerUnit()));
                if (this.transaction.isPending()) {
                    this.stop(
                        "recovery_" + this.transaction.getPhase()
                            .name());
                    GTNHMELogisticsEnhancementMod.LOG.warn(
                        "ME Transfer Drive stopped: {} at {},{},{}",
                        this.status,
                        this.tile.xCoord,
                        this.tile.yCoord,
                        this.tile.zCoord);
                    break;
                }
                if (!this.cycle.isTopologyValid()) {
                    this.stop("topology_changed_waiting_next_cycle");
                    break;
                }
                if (this.routed.isFinished()) {
                    this.candidate = null;
                    this.routed = null;
                }
                this.status = delivered > 0 ? "transferring"
                    : this.blockedByCycle ? "cycle_blocked" : "not_accepted_or_no_energy";
                if (this.energyAccount.isDeferred()) {
                    break;
                }
            }
        } catch (final RuntimeException failure) {
            this.stop("api_failure");
            GTNHMELogisticsEnhancementMod.LOG.warn("ME Transfer Drive stopped after an AE API failure", failure);
        } finally {
            this.ticking = false;
        }
        return this.active;
    }

    private void openNextSlot() {
        if (this.nextSlot >= DriveSlotLayout.TOTAL_SLOTS) {
            this.active = false;
            this.finishSlot();
            this.cycle = null;
            this.graph = null;
            this.status = "cycle_complete";
            return;
        }
        this.sourceSlot = this.nextSlot++;
        if (this.registry.getFillPercent(DriveSlotLayout.group(this.sourceSlot)) == 0
            || this.cycle.targetsForGroup(DriveSlotLayout.group(this.sourceSlot))
                .isEmpty()) {
            return;
        }
        this.sourceCell = this.tile.getStackInSlot(this.sourceSlot);
        this.sourceInventory = this.tile.getNativeCellHandler(this.sourceSlot);
        if (this.sourceCell == null || this.sourceInventory == null) {
            return;
        }
        final IAEStackType<?> type = this.sourceInventory.getStackType();
        if (TransferStorageTypes.batchLimit(type) == 0 || !SourceCellAccess.canTransferFrom(this.sourceInventory)) {
            return;
        }
        // The legacy API snapshots one cell synchronously. SourceCellAccess bounds this to 63 native types.
        final IItemList<?> snapshot = this.sourceInventory
            .getAvailableItems(type.createPrimitiveList(), IterationCounter.fetchNewId());
        SourceCellAccess.validateSnapshot(type, snapshot);
        this.types = snapshot.iterator();
    }

    private boolean nextType(final TransferScheduler.Slice budget) {
        while (budget.probeType()) {
            if (!this.types.hasNext()) {
                this.finishSlot();
                return false;
            }
            final IAEStack stack = (IAEStack) this.types.next();
            if (stack != null && stack.getStackSize() > 0) {
                this.candidate = stack.copy()
                    .setCraftable(false);
                this.routed = new RoutedTransfer<>(
                    stack.copy()
                        .setStackSize(1)
                        .setCraftable(false),
                    stack.getStackSize(),
                    this.cycle,
                    DriveSlotLayout.group(this.sourceSlot));
                return true;
            }
        }
        return false;
    }

    private boolean sameSource() {
        return !this.tile.isInvalid() && this.tile.getWorldObj() != null
            && this.tile.getWorldObj()
                .getTileEntity(this.tile.xCoord, this.tile.yCoord, this.tile.zCoord) == this.tile
            && this.sourceSlot >= 0
            && this.sourceSlot < DriveSlotLayout.TOTAL_SLOTS
            && this.tile.getStackInSlot(this.sourceSlot) == this.sourceCell
            && this.tile.getNativeCellHandler(this.sourceSlot) == this.sourceInventory;
    }

    private TransferTransaction.Source source(final IAEStack<?> identity) {
        return new TransferTransaction.Source() {

            @Override
            public long extract(final long amount) {
                checkOrigin();
                if (!tile.getProxy()
                    .isActive()) {
                    return 0;
                }
                final IAEStack result = sourceInventory
                    .extractItems(stack(identity, amount), Actionable.MODULATE, actionSource);
                final long extracted = quantity(identity, result, amount);
                sourceChanged(identity, -extracted);
                return extracted;
            }

            @Override
            public long restore(final long amount) {
                checkOrigin();
                final IAEStack leftover = sourceInventory
                    .injectItems(stack(identity, amount), Actionable.MODULATE, actionSource);
                final long restored = amount - quantity(identity, leftover, amount);
                sourceChanged(identity, restored);
                return restored;
            }

            private void checkOrigin() {
                if (!sameSource() || !SourceCellAccess.canTransferFrom(sourceInventory)) {
                    throw new IllegalStateException("Original cell is no longer available for this transaction");
                }
            }
        };
    }

    private TransferTransaction.Destination destination(final IAEStack<?> identity,
        final RoutingCycle.Target<IGrid> target) {
        final RoutingCycle<IGrid> currentCycle = this.cycle;
        this.blockedByCycle = this.graph != null && this.graph.blocks(target.getGrid());
        return new GridStorageDestination(
            identity,
            this.tile,
            this.actionSource,
            () -> this.cycle == currentCycle && currentCycle.isTopologyValid()
                && target.isAvailable()
                && this.graph != null
                && this.graph.isComplete()
                && this.graph.sameGeneration()
                && !this.graph.blocks(target.getGrid())
                    ? target.getGrid()
                        .<IStorageGrid>getCache(IStorageGrid.class)
                        .getMEMonitor(identity.getStackType())
                    : null,
            (result, maximum) -> this.quantity(identity, result, maximum),
            this.registry.getFillPercent(DriveSlotLayout.group(this.sourceSlot)));
    }

    private void sourceChanged(final IAEStack<?> identity, final long delta) {
        this.tile.saveChanges(this.sourceInventory);
        if (delta != 0 && this.tile.getProxy()
            .isActive()
            && this.tile.getAccessMode()
                .hasPermission(AccessRestriction.READ)) {
            try {
                this.tile.getProxy()
                    .getStorage()
                    .postAlterationOfStoredItems(
                        identity.getStackType(),
                        Collections.singletonList(stack(identity, delta)),
                        this.actionSource);
            } catch (final GridAccessException ignored) {
                // Re-registration after reconnect enumerates the native cells again.
            }
        }
    }

    private TransferTransaction.Energy energyFor(final int unit) {
        return new TransferTransaction.Energy() {

            @Override
            public long reserve(final long amount) {
                return reserveEnergy(amount, unit);
            }

            @Override
            public void settle(final long delivered, final boolean certain) {
                energyAccount.settle(delivered, certain);
            }

            @Override
            public boolean isDeferred() {
                return energyAccount.isDeferred();
            }
        };
    }

    private long reserveEnergy(final long amount, final int amountPerUnit) {
        if (amount <= 0) {
            this.energyAccount.skip(false);
            return 0;
        }
        try {
            if (!this.tile.getProxy()
                .isActive()) {
                this.energyAccount.skip(true);
                return 0;
            }
            final IGrid original = this.sourceGrid();
            final IEnergyGrid energy = this.tile.getProxy()
                .getEnergy();
            final double multiplier = PowerMultiplier.CONFIG.multiply(1.0);
            return this.energyAccount.bind(original, new TransferEnergyAccount.Port() {

                @Override
                public boolean valid() {
                    return !tile.isInvalid() && sourceGrid() == original
                        && original.<IEnergyGrid>getCache(IEnergyGrid.class) == energy;
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
                public double extract(final double physical, final boolean simulate) {
                    return energy.extractAEPower(
                        physical,
                        simulate ? Actionable.SIMULATE : Actionable.MODULATE,
                        PowerMultiplier.ONE);
                }

                @Override
                public double refund(final double physical) {
                    return energy.injectPower(physical, Actionable.MODULATE);
                }
            }, amountPerUnit, multiplier)
                .reserve(amount);
        } catch (final GridAccessException unavailable) {
            this.energyAccount.skip(true);
            return 0;
        }
    }

    private IGrid sourceGrid() {
        final IGridNode source = this.tile.getActionableNode();
        return source == null ? null : source.getGrid();
    }

    private boolean loaded(final TransferTarget target) {
        return this.tile.getWorldObj() != null && target.getDimension() == this.tile.getWorldObj().provider.dimensionId
            && this.tile.getWorldObj()
                .getChunkProvider()
                .chunkExists(target.getX() >> 4, target.getZ() >> 4);
    }

    private RoutingCycle.Endpoint<IGrid> resolveEndpoint(final TransferTarget target) {
        if (!this.loaded(target)) {
            return null;
        }
        final TileEntity targetTile = this.tile.getWorldObj()
            .getTileEntity(target.getX(), target.getY(), target.getZ());
        if (targetTile == null || targetTile.getClass() != TileInterface.class || targetTile.isInvalid()) {
            return null;
        }
        final IGridNode node = ((TileInterface) targetTile).getGridNode(ForgeDirection.UNKNOWN);
        if (node == null || node.getGrid() == null) {
            return null;
        }
        final IGrid grid = node.getGrid();
        return new RoutingCycle.Endpoint<>(
            grid,
            () -> this.loaded(target) && !targetTile.isInvalid()
                && ((TileInterface) targetTile).getGridNode(ForgeDirection.UNKNOWN) == node ? node.getGrid() : null,
            () -> this.loaded(target) && !targetTile.isInvalid()
                && node.isActive()
                && this.tile.getWorldObj()
                    .getTileEntity(target.getX(), target.getY(), target.getZ()) == targetTile
                && ((TileInterface) targetTile).getGridNode(ForgeDirection.UNKNOWN) == node
                && this.canInject(grid));
    }

    private boolean canInject(final IGrid grid) {
        final IGridNode source = this.tile.getActionableNode();
        if (source == null || !source.isActive()
            || source.getGrid() == grid
            || !this.tile.getProxy()
                .isActive()) {
            return false;
        }
        final ISecurityGrid security = grid.getCache(ISecurityGrid.class);
        final ISecurityGrid sourceSecurity = source.getGrid()
            .getCache(ISecurityGrid.class);
        final int player = sourceSecurity.isAvailable() ? sourceSecurity.getOwner() : source.getPlayerID();
        return security.hasPermission(player, SecurityPermissions.INJECT);
    }

    private static IAEStack<?> stack(final IAEStack<?> identity, final long amount) {
        return identity.copy()
            .setStackSize(amount)
            .setCraftable(false);
    }

    private long quantity(final IAEStack<?> identity, final IAEStack<?> result, final long maximum) {
        try {
            return GridStorageDestination.checkedQuantity(identity, result, maximum);
        } catch (final IllegalStateException invalid) {
            if (this.transaction.isPending()) {
                this.unexpectedReturn = new NBTTagCompound();
                result.writeToNBT(this.unexpectedReturn);
                this.unexpectedReturn.setString(
                    "type",
                    result.getStackType()
                        .getId());
                this.tile.markDirty();
            }
            throw invalid;
        }
    }

    private void finishSlot() {
        this.types = null;
        this.candidate = null;
        this.routed = null;
        this.sourceSlot = -1;
        this.sourceCell = null;
        this.sourceInventory = null;
    }

    private void stop(final String reason) {
        TransferScheduler.remove(this.scheduledWork);
        this.recoveryDue = false;
        if (!this.energyAccount.isPending()) {
            this.energyAccount.detach();
        }
        this.active = false;
        this.graph = null;
        this.cycle = null;
        if (!this.transaction.isPending()) {
            this.finishSlot();
        } else {
            this.types = null;
            this.candidate = null;
            this.routed = null;
        }
        this.clock.reset();
        this.status = reason;
    }

    public boolean hasRecovery() {
        return this.transaction.isPending() || this.energyAccount.isPending();
    }

    public void suspend() {
        this.topologyChanged();
        this.cycle = null;
        this.stop("suspended");
    }

    public UUID registerTarget(final TransferTarget target) {
        this.requireEditable();
        this.validateBinding(target);
        final UUID id = this.registry.register(target);
        this.routingChanged();
        return id;
    }

    public List<TransferTarget> getTargets() {
        return this.registry.getTargets();
    }

    public void updateTarget(final UUID id, final String name, final int priority) {
        this.requireEditable();
        this.registry.updateMetadata(id, name, priority);
        this.routingChanged();
    }

    public void removeTarget(final UUID id) {
        this.requireEditable();
        this.registry.remove(id);
        this.routingChanged();
    }

    public void setRoute(final int group, final TargetRegistry.Route route) {
        this.requireEditable();
        this.registry.setRoute(group, route);
        this.routingChanged();
    }

    public TargetRegistry.Route getRoute(final int group) {
        return this.registry.getRoute(group);
    }

    public int getFillPercent(final int group) {
        return this.registry.getFillPercent(group);
    }

    public void setFillPercent(final int group, final int percent) {
        this.requireEditable();
        TransferFillLimit.validate(percent);
        if (this.registry.getFillPercent(group) != percent) {
            this.registry.setFillPercent(group, percent);
            this.routingChanged();
        }
    }

    public RoutingCycle.Status getTargetStatus(final UUID id) {
        if (this.graph != null && this.graph.isComplete()
            && this.graph.sameGeneration()
            && this.graphAge < 20
            && this.cycle != null
            && this.graph.blocks(this.cycle.getResolvedGrid(id))) {
            return RoutingCycle.Status.CYCLE_BLOCKED;
        }
        return this.cycle == null ? this.lastTargetStatuses.getOrDefault(id, RoutingCycle.Status.UNVALIDATED)
            : this.cycle.getStatus(id);
    }

    /** Explicit discard of configuration, including quarantined malformed NBT. Does not clear a transaction. */
    public void resetRoutingConfiguration() {
        if (this.ticking || this.hasRecovery()) {
            throw new IllegalStateException("Cannot reset routing during a transfer or recovery");
        }
        this.registry = new TargetRegistry();
        this.quarantinedRouting = null;
        this.routingChanged();
    }

    private void requireEditable() {
        if (this.isConfigurationLocked() || this.tile.hasCellInventoryQuarantine()) {
            throw new IllegalStateException("Cannot edit pending, active-call, or damaged transfer state");
        }
    }

    public boolean isConfigurationLocked() {
        return this.ticking || this.hasRecovery() || this.quarantinedRouting != null;
    }

    private void validateBinding(final TransferTarget target) {
        if (target == null || this.tile.getWorldObj() != null
            && target.getDimension() != this.tile.getWorldObj().provider.dimensionId) {
            throw new IllegalArgumentException("Target must be in the same dimension");
        }
        if (this.loaded(target)) {
            final TileEntity targetTile = this.tile.getWorldObj()
                .getTileEntity(target.getX(), target.getY(), target.getZ());
            if (targetTile == null || targetTile.getClass() != TileInterface.class || targetTile.isInvalid()) {
                throw new IllegalArgumentException("Loaded target must be an original block ME interface");
            }
            final RoutingCycle.Endpoint<IGrid> endpoint = this.resolveEndpoint(target);
            if (endpoint != null && endpoint.getGrid() == this.sourceGrid()) {
                throw new IllegalArgumentException("Loaded target must be an interface on another grid");
            }
        }
    }

    private void routingChanged() {
        this.routingRevision++;
        this.topologyChanged();
        this.stop("routing_changed");
        this.cycle = null;
        this.tile.markDirty();
    }

    public int getPeriodSeconds() {
        return this.clock.getSeconds();
    }

    public void setPeriodSeconds(final int value) {
        this.requireEditable();
        this.clock.setSeconds(value);
        this.routingChanged();
    }

    public void sourceAccessChanging() {
        this.requireEditable();
        this.routingChanged();
    }

    public String getStatus() {
        return this.status;
    }

    public void writeToNBT(final NBTTagCompound data) {
        data.setInteger("transferPeriodSeconds", this.clock.getSeconds());
        data.removeTag("transferRouting");
        data.removeTag("invalidTransferRouting");
        if (this.quarantinedRouting != null) {
            data.setTag("invalidTransferRouting", this.quarantinedRouting.copy());
        } else {
            data.setTag("transferRouting", TargetRegistryNbt.write(this.registry));
        }
        data.removeTag("transferRecovery");
        if (this.quarantinedRecord != null) {
            RecoveryEvidence.write(data, this.quarantinedRecord);
        } else if (this.transaction.isPending()) {
            data.setTag(
                "transferRecovery",
                RecoveryEvidence
                    .snapshot(this.transaction, this.sourceSlot, this.transactionTargetId, this.unexpectedReturn));
        }
        this.energyAccount.write(data);
    }

    public void readFromNBT(final NBTTagCompound data) {
        this.stop("loaded");
        this.energyAccount.read(data);
        final int seconds = data.getInteger("transferPeriodSeconds");
        this.clock.setSeconds(
            seconds >= TransferClock.MIN_SECONDS && seconds <= TransferClock.MAX_SECONDS ? seconds
                : TransferClock.DEFAULT_SECONDS);
        this.readRouting(data);
        if (data.hasKey("transferRecovery")) {
            this.quarantinedRecord = RecoveryEvidence.read(data);
            this.sourceSlot = this.quarantinedRecord instanceof NBTTagCompound
                ? ((NBTTagCompound) this.quarantinedRecord).getInteger("slot")
                : -1;
            // Preserve the exact serialized record, including unknown or damaged fields. It is evidence, not stock.
            this.transaction.quarantine(null, TransferTransaction.Phase.UNCERTAIN, 0, 0, 0);
            this.status = "recovery_requires_reconciliation";
        }
    }

    private void readRouting(final NBTTagCompound data) {
        this.routingRevision++;
        this.topologyChanged();
        this.registry = new TargetRegistry();
        this.cycle = null;
        this.quarantinedRouting = null;
        if (data.hasKey("invalidTransferRouting")) {
            this.quarantinedRouting = new NBTTagCompound();
            this.quarantinedRouting.setTag(
                "invalidTransferRouting",
                data.getTag("invalidTransferRouting")
                    .copy());
            if (data.hasKey("invalidTransferRouting", 10)) {
                this.quarantinedRouting = (NBTTagCompound) data.getCompoundTag("invalidTransferRouting")
                    .copy();
            }
            this.status = "invalid_routing_configuration";
            return;
        }
        try {
            if (data.hasKey("transferRouting")) {
                if (!data.hasKey("transferRouting", 10)) {
                    throw new IllegalArgumentException("Invalid routing tag type");
                }
                this.registry = TargetRegistryNbt.read(data.getCompoundTag("transferRouting"));
            }
        } catch (final RuntimeException invalid) {
            this.quarantinedRouting = new NBTTagCompound();
            this.quarantinedRouting.setTag(
                "transferRouting",
                data.getTag("transferRouting")
                    .copy());
            this.status = "invalid_routing_configuration";
        }
    }

    /** Invalidate this device's proofs; unrelated drives must not invalidate one another globally. */
    public void topologyChanged() {
        this.witnessRevision++;
        this.graph = null;
        this.lastTargetStatuses.clear();
    }

    public long getConfigurationRevision() {
        return this.routingRevision;
    }

    public RouteGraph.Device<IGrid> routingWitness(final IAEStackType<?> transferType) {
        return new RouteGraph.Device<IGrid>() {

            @Override
            public IGrid sourceGrid() {
                return tile.getWorldObj() != null && !tile.getWorldObj().isRemote
                    && !tile.isInvalid()
                    && tile.getWorldObj()
                        .getChunkProvider()
                        .chunkExists(tile.xCoord >> 4, tile.zCoord >> 4)
                    && tile.getWorldObj()
                        .getTileEntity(tile.xCoord, tile.yCoord, tile.zCoord) == tile
                    && tile.getProxy()
                        .isActive()
                    && !tile.hasTransferRecovery()
                    && quarantinedRouting == null ? AETransferController.this.sourceGrid() : null;
            }

            @Override
            public long revision() {
                return witnessRevision;
            }

            @Override
            public List<TransferTarget> candidates() {
                return registry.routedBindings(transferableGroups(transferType));
            }

            @Override
            public RouteGraph.Link<IGrid> resolve(final TransferTarget target) {
                final RoutingCycle.Endpoint<IGrid> endpoint = resolveEndpoint(target);
                return endpoint == null ? null : new RouteGraph.Link<>(endpoint.getGrid(), endpoint::isAvailable);
            }
        };
    }

    private boolean prepareGraph() {
        final IAEStackType<?> transferType = this.sourceInventory.getStackType();
        if (this.graphType != transferType) {
            this.graph = null;
        }
        if (this.graph != null && (!this.graph.sameGeneration()
            || this.graph.isComplete() && (this.graphAge >= 20 || !this.graph.isFresh()))) {
            this.graph = null;
        }
        if (this.graph == null) {
            final List<RouteGraph.Link<IGrid>> roots = new ArrayList<>();
            final IdentityHashMap<IGrid, Boolean> seen = new IdentityHashMap<>();
            final boolean[] eligible = this.transferableGroups(transferType);
            for (int group = 0; group < DriveSlotLayout.GROUP_COUNT; group++) {
                if (!eligible[group]) {
                    continue;
                }
                for (final RoutingCycle.Target<IGrid> target : this.cycle.targetsForGroup(group)) {
                    if (seen.put(target.getGrid(), Boolean.TRUE) == null && target.isAvailable()) {
                        roots.add(new RouteGraph.Link<>(target.getGrid(), target::isAvailable));
                    }
                }
            }
            this.graph = new RouteGraph<>(this.sourceGrid(), roots, new RouteGraph.Topology<IGrid>() {

                @Override
                public long generation() {
                    return witnessRevision;
                }

                @Override
                public Iterator<RouteGraph.Device<IGrid>> devices(final IGrid grid) {
                    final Iterator<IGridNode> nodes = grid.getMachines(TileMETransferDrive.class)
                        .iterator();
                    return new Iterator<RouteGraph.Device<IGrid>>() {

                        @Override
                        public boolean hasNext() {
                            return nodes.hasNext();
                        }

                        @Override
                        public RouteGraph.Device<IGrid> next() {
                            return ((TileMETransferDrive) nodes.next()
                                .getMachine()).getTransferRoutingWitness(transferType);
                        }
                    };
                }
            });
            this.graphAge = 0;
            this.graphType = transferType;
        }
        if (!this.graph.isComplete()) {
            this.graph.step(TARGETS_PER_TICK);
            this.status = "checking_route_cycles";
            if (this.graph.isComplete()) {
                this.lastTargetStatuses.clear();
                for (final TransferTarget target : this.registry.getTargets()) {
                    this.lastTargetStatuses.put(
                        target.getId(),
                        this.graph.blocks(this.cycle.getResolvedGrid(target.getId()))
                            ? RoutingCycle.Status.CYCLE_BLOCKED
                            : this.cycle.getStatus(target.getId()));
                }
            }
            if (this.graph.isLimited()) {
                this.stop("cycle_check_limit");
                return false;
            }
            // A device may wait over 20 world ticks for the next slice. Use a newly completed proof now, after
            // validation.
            if (this.graph.isComplete() && !this.graph.isFresh()) {
                this.graph = null;
                return false;
            }
            return this.graph.isComplete();
        }
        return true;
    }

    private boolean[] transferableGroups(final IAEStackType<?> type) {
        final boolean[] groups = new boolean[DriveSlotLayout.GROUP_COUNT];
        if (TransferStorageTypes.batchLimit(type) == 0) {
            return groups;
        }
        for (int slot = 0; slot < DriveSlotLayout.TOTAL_SLOTS; slot++) {
            final IMEInventory<?> inventory = this.tile.getNativeCellHandler(slot);
            if (inventory != null && inventory.getStackType() == type
                && SourceCellAccess.canTransferFrom(inventory)
                && this.registry.getFillPercent(DriveSlotLayout.group(slot)) > 0) {
                groups[DriveSlotLayout.group(slot)] = true;
            }
        }
        return groups;
    }
}
