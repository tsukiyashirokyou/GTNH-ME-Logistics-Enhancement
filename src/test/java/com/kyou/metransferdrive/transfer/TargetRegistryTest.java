package com.kyou.metransferdrive.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.Test;

public class TargetRegistryTest {

    private final TargetRegistry registry = new TargetRegistry();

    @Test
    public void registeringSameInterfaceReusesIdAndKeepsMetadata() {
        final TransferTarget first = target(0, 10);
        assertEquals(first.getId(), this.registry.register(first));
        assertEquals(first.getId(), this.registry.register(target(0, -1)));
        assertEquals(
            1,
            this.registry.getTargets()
                .size());
        assertSame(
            first,
            this.registry.getTargets()
                .get(0));
    }

    @Test
    public void differentInterfacesRemainIndependentPersistentBindings() {
        this.registry.register(target(0, 0));
        this.registry.register(target(1, 0));
        assertEquals(
            2,
            this.registry.getTargets()
                .size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void conflictingStableIdCannotReplaceAnInterface() {
        final TransferTarget original = target(0, 0);
        this.registry.register(original);
        this.registry.register(new TransferTarget(original.getId(), 0, 1, 64, 0));
    }

    @Test
    public void priorityIsDescendingStableAndDoesNotOverflow() {
        final TransferTarget first = target(0, 0);
        final TransferTarget second = target(1, 0);
        final TransferTarget low = target(2, Integer.MIN_VALUE);
        final TransferTarget high = target(3, Integer.MAX_VALUE);
        for (final TransferTarget target : new TransferTarget[] { first, second, low, high }) {
            this.registry.register(target);
        }
        assertEquals(java.util.Arrays.asList(high, first, second, low), this.registry.byPriority());
    }

    @Test
    public void deletionClearsOnlyGroupsThatExplicitlyReferenceTheDeletedTarget() {
        final TransferTarget first = target(0, 0);
        final TransferTarget second = target(1, 0);
        this.registry.register(first);
        this.registry.register(second);
        this.registry.setRoute(0, TargetRegistry.Route.specific(first.getId()));
        this.registry.setRoute(1, TargetRegistry.Route.specific(second.getId()));
        this.registry.setRoute(2, TargetRegistry.Route.automatic());
        this.registry.remove(first.getId());
        assertEquals(
            TargetRegistry.Mode.UNASSIGNED,
            this.registry.getRoute(0)
                .getMode());
        assertNull(
            this.registry.getRoute(0)
                .getTargetId());
        assertEquals(
            second.getId(),
            this.registry.getRoute(1)
                .getTargetId());
        assertEquals(
            TargetRegistry.Mode.AUTOMATIC,
            this.registry.getRoute(2)
                .getMode());
    }

    @Test
    public void metadataUpdatesDoNotMutateSnapshotsOrStableIdentity() {
        final TransferTarget target = target(0, 0);
        this.registry.register(target);
        final List<TransferTarget> snapshot = this.registry.byPriority();
        this.registry.updateMetadata(target.getId(), "Updated alias", 20);
        assertEquals(
            "Target 0",
            snapshot.get(0)
                .getDisplayName());
        assertEquals(
            "Updated alias",
            this.registry.getTargets()
                .get(0)
                .getDisplayName());
        assertEquals(
            target.getId(),
            this.registry.getTargets()
                .get(0)
                .getId());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void exposedBindingsCannotMutateTheRegistry() {
        this.registry.getTargets()
            .add(target(0, 0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownTargetCannotBeAssignedToAGroup() {
        this.registry.setRoute(0, TargetRegistry.Route.specific(UUID.randomUUID()));
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void ninthGroupIsRejected() {
        this.registry.setRoute(8, TargetRegistry.Route.automatic());
    }

    @Test(expected = IllegalArgumentException.class)
    public void controlCharactersAreRejectedInNames() {
        new TransferTarget(UUID.randomUUID(), 0, 0, 64, 0, "Bad\nName", 0);
    }

    @Test(expected = IllegalStateException.class)
    public void metadataAndResolutionAreBoundedByTheBindingLimit() {
        for (int index = 0; index <= TargetRegistry.MAX_TARGETS; index++) {
            this.registry.register(target(index, 0));
        }
    }

    @Test
    public void graphCandidatesIncludeOnlyConfiguredRoutesAndDoNotDuplicateBindingsAcrossGroups() {
        final UUID first = this.registry.register(target(1, 0));
        final UUID second = this.registry.register(target(2, 0));
        assertTrue(
            this.registry.routedBindings()
                .isEmpty());
        this.registry.setRoute(0, TargetRegistry.Route.specific(first));
        this.registry.setRoute(1, TargetRegistry.Route.specific(first));
        assertEquals(
            1,
            this.registry.routedBindings()
                .size());
        assertEquals(
            first,
            this.registry.routedBindings()
                .get(0)
                .getId());
        this.registry.setRoute(7, TargetRegistry.Route.automatic());
        assertEquals(
            2,
            this.registry.routedBindings()
                .size());
        this.registry.remove(second);
        assertEquals(
            1,
            this.registry.routedBindings()
                .size());
    }

    @Test
    public void typeSpecificWitnessesIgnoreFluidAndEssentiaOnlyGroupsWhenCheckingItemRoutes() {
        final UUID itemTarget = this.registry.register(target(1, 0));
        final UUID fluidTarget = this.registry.register(target(2, 0));
        this.registry.register(target(3, 0));
        this.registry.setRoute(0, TargetRegistry.Route.specific(itemTarget));
        this.registry.setRoute(1, TargetRegistry.Route.specific(fluidTarget));
        this.registry.setRoute(2, TargetRegistry.Route.automatic());
        final boolean[] items = new boolean[8];
        items[0] = true;
        final boolean[] fluids = new boolean[8];
        fluids[1] = true;
        assertEquals(
            1,
            this.registry.routedBindings(items)
                .size());
        assertEquals(
            itemTarget,
            this.registry.routedBindings(items)
                .get(0)
                .getId());
        assertEquals(
            1,
            this.registry.routedBindings(fluids)
                .size());
        assertEquals(
            fluidTarget,
            this.registry.routedBindings(fluids)
                .get(0)
                .getId());
        assertTrue(
            this.registry.routedBindings(new boolean[8])
                .isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidTypeEligibilityMasksCannotBeUsedAsRouteEvidence() {
        this.registry.routedBindings(new boolean[7]);
    }

    @Test
    public void deletionKeepsOtherSelectedTargetsAndEveryGroupOwnsItsSelection() {
        final UUID first = this.registry.register(target(0, 0));
        final UUID second = this.registry.register(target(1, 0));
        final java.util.Set<UUID> ids = new java.util.LinkedHashSet<>(java.util.Arrays.asList(first, second));
        this.registry.setRoute(0, TargetRegistry.Route.specific(ids));
        ids.clear();
        this.registry.setRoute(1, TargetRegistry.Route.specific(first));
        this.registry.remove(first);
        assertEquals(
            java.util.Collections.singleton(second),
            this.registry.getRoute(0)
                .getTargetIds());
        assertEquals(
            TargetRegistry.Mode.UNASSIGNED,
            this.registry.getRoute(1)
                .getMode());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void selectedTargetIdsCannotMutateARouteSnapshot() {
        TargetRegistry.Route.specific(java.util.Collections.emptySet())
            .getTargetIds()
            .add(UUID.randomUUID());
    }

    @Test
    public void allThreeTypeMasksUseOnlyTheirOwnMultiSelectedGroupsForCycleEvidence() {
        final UUID first = this.registry.register(target(0, 0));
        final UUID second = this.registry.register(target(1, 0));
        final UUID third = this.registry.register(target(2, 0));
        this.registry.setRoute(0, TargetRegistry.Route.specific(first));
        this.registry.setRoute(1, TargetRegistry.Route.specific(second));
        this.registry.setRoute(2, TargetRegistry.Route.specific(java.util.Arrays.asList(first, third)));
        for (int type = 0; type < 3; type++) {
            final boolean[] mask = new boolean[8];
            mask[type] = true;
            final java.util.List<UUID> expected = type == 0 ? java.util.Collections.singletonList(first)
                : type == 1 ? java.util.Collections.singletonList(second) : java.util.Arrays.asList(first, third);
            final java.util.List<UUID> actual = new java.util.ArrayList<>();
            for (TransferTarget target : this.registry.routedBindings(mask)) {
                actual.add(target.getId());
            }
            assertEquals(expected, actual);
        }
    }

    private static TransferTarget target(final int x, final int priority) {
        return new TransferTarget(UUID.randomUUID(), 0, x, 64, 0, "Target " + x, priority);
    }
}
