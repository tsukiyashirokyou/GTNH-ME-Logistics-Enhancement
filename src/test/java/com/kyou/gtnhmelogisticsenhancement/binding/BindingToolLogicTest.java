package com.kyou.gtnhmelogisticsenhancement.binding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.Test;

import com.kyou.gtnhmelogisticsenhancement.transfer.TargetRegistry;
import com.kyou.gtnhmelogisticsenhancement.transfer.TransferTarget;

public class BindingToolLogicTest {

    private final Context context = new Context();
    private final Source source = new Source();
    private final BindingToolLogic.Position sourcePosition = position(1);
    private final BindingToolLogic.Position targetPosition = position(5);

    public BindingToolLogicTest() {
        this.context.sources.put(1, this.source);
        this.context.grids.put(5, new Object());
    }

    @Test
    public void firstGestureSelectsTheDriveAndSecondRegistersWithoutAssigningGroups() {
        final BindingToolLogic.Result selection = this.use(null, this.sourcePosition);
        assertEquals(BindingToolLogic.Outcome.SOURCE_SELECTED, selection.outcome);
        assertEquals(this.source.id, selection.selection.deviceId);
        assertTrue(
            this.source.registry.getTargets()
                .isEmpty());
        assertEquals(BindingToolLogic.Outcome.BOUND, this.use(selection.selection, this.targetPosition).outcome);
        assertEquals(
            1,
            this.source.registry.getTargets()
                .size());
        for (int group = 0; group < 8; group++) {
            assertEquals(
                TargetRegistry.Mode.UNASSIGNED,
                this.source.registry.getRoute(group)
                    .getMode());
        }
    }

    @Test
    public void duplicateInterfaceReusesIdentityAndDoesNotChangeAliasPriorityOrRoutes() {
        this.use(this.selection(), this.targetPosition);
        final UUID id = this.source.registry.getTargets()
            .get(0)
            .getId();
        this.source.registry.updateMetadata(id, "Keep this alias", 99);
        this.source.registry.setRoute(0, TargetRegistry.Route.specific(id));
        this.source.registry.setRoute(7, TargetRegistry.Route.automatic());
        assertEquals(
            BindingToolLogic.Outcome.DUPLICATE_INTERFACE,
            this.use(this.selection(), this.targetPosition).outcome);
        assertEquals(1, this.source.registrations);
        assertEquals(
            1,
            this.source.registry.getTargets()
                .size());
        assertEquals(
            id,
            this.source.registry.getTargets()
                .get(0)
                .getId());
        assertEquals(
            "Keep this alias",
            this.source.registry.getTargets()
                .get(0)
                .getDisplayName());
        assertEquals(
            99,
            this.source.registry.getTargets()
                .get(0)
                .getPriority());
        assertEquals(
            id,
            this.source.registry.getRoute(0)
                .getTargetId());
        assertEquals(
            TargetRegistry.Mode.AUTOMATIC,
            this.source.registry.getRoute(7)
                .getMode());
    }

    @Test
    public void differentInterfacesOnTheSameGridKeepBothBindings() {
        this.use(this.selection(), this.targetPosition);
        this.context.grids.put(6, this.context.grids.get(5));
        assertEquals(BindingToolLogic.Outcome.BOUND_SAME_GRID, this.use(this.selection(), position(6)).outcome);
        assertEquals(
            2,
            this.source.registry.getTargets()
                .size());
    }

    @Test
    public void selectingAnotherSourceOverwritesOnlyTheTemporarySelection() {
        this.use(this.selection(), this.targetPosition);
        final Source other = new Source();
        this.context.sources.put(2, other);
        final BindingToolLogic.Result selection = this.use(this.selection(), position(2));
        assertEquals(other.id, selection.selection.deviceId);
        assertEquals(2, selection.selection.position.x);
        this.use(selection.selection, this.targetPosition);
        assertEquals(
            1,
            this.source.registry.getTargets()
                .size());
        assertEquals(
            1,
            other.registry.getTargets()
                .size());
    }

    @Test
    public void removedSourceCannotBeReplacedByCoordinateMatching() {
        final BindingToolLogic.SourceReference selection = this.selection();
        this.context.sources.remove(1);
        assertEquals(BindingToolLogic.Outcome.SOURCE_INVALID, this.use(selection, this.targetPosition).outcome);
        assertTrue(
            this.source.registry.getTargets()
                .isEmpty());
    }

    @Test
    public void replacementDriveAtTheSamePositionRejectsTheOldUuid() {
        final BindingToolLogic.SourceReference selection = this.selection();
        final Source replacement = new Source();
        this.context.sources.put(1, replacement);
        assertEquals(BindingToolLogic.Outcome.SOURCE_ID_MISMATCH, this.use(selection, this.targetPosition).outcome);
        assertTrue(
            replacement.registry.getTargets()
                .isEmpty());
    }

    @Test
    public void unloadedSourceIsNotLookedUpOrForceLoaded() {
        this.context.unloaded.add(1);
        assertEquals(BindingToolLogic.Outcome.SOURCE_UNLOADED, this.use(this.selection(), this.targetPosition).outcome);
        assertTrue(
            this.source.registry.getTargets()
                .isEmpty());
    }

    @Test
    public void unloadedTargetIsNotLookedUp() {
        this.context.unloaded.add(5);
        assertEquals(BindingToolLogic.Outcome.TARGET_UNLOADED, this.use(this.selection(), this.targetPosition).outcome);
    }

    @Test
    public void crossDimensionReferenceCannotAccessTheOtherWorld() {
        final BindingToolLogic.SourceReference otherWorld = new BindingToolLogic.SourceReference(
            new BindingToolLogic.Position(7, 1, 64, 0),
            this.source.id);
        assertEquals(BindingToolLogic.Outcome.DIFFERENT_DIMENSION, this.use(otherWorld, this.targetPosition).outcome);
        assertEquals(1, this.context.lookups); // Only the clicked block was inspected for source reselection.
    }

    @Test
    public void invalidTargetIncludingCableInterfacePartsDoesNotRegister() {
        this.context.grids.remove(5);
        assertEquals(BindingToolLogic.Outcome.INVALID_TARGET, this.use(this.selection(), this.targetPosition).outcome);
        assertTrue(
            this.source.registry.getTargets()
                .isEmpty());
    }

    @Test
    public void missingSourceSelectionDoesNotCreateAnImplicitBinding() {
        assertEquals(BindingToolLogic.Outcome.NO_SOURCE, this.use(null, this.targetPosition).outcome);
    }

    @Test
    public void transactionLockBlocksBothSelectionAndRegistration() {
        this.source.locked = true;
        assertEquals(BindingToolLogic.Outcome.LOCKED, this.use(null, this.sourcePosition).outcome);
        assertEquals(BindingToolLogic.Outcome.LOCKED, this.use(this.selection(), this.targetPosition).outcome);
        assertEquals(0, this.source.registrations);
    }

    @Test
    public void sourcePermissionIsRevalidatedAtTheSecondGesture() {
        final BindingToolLogic.SourceReference selection = this.use(null, this.sourcePosition).selection;
        this.source.permission = false;
        assertEquals(BindingToolLogic.Outcome.PERMISSION_DENIED, this.use(selection, this.targetPosition).outcome);
        assertEquals(0, this.source.registrations);
    }

    @Test
    public void targetPermissionDenialDoesNotModifyTheSource() {
        this.context.targetPermission = false;
        assertEquals(
            BindingToolLogic.Outcome.PERMISSION_DENIED,
            this.use(this.selection(), this.targetPosition).outcome);
        assertTrue(
            this.source.registry.getTargets()
                .isEmpty());
    }

    @Test
    public void sameActualSourceGridIsRejected() {
        this.context.grids.put(5, this.source.grid);
        assertEquals(BindingToolLogic.Outcome.SELF_GRID, this.use(this.selection(), this.targetPosition).outcome);
    }

    @Test
    public void inactiveGridCanRemainAPendingBindingWhenTheInterfaceIsValidAndPermitted() {
        this.context.grids.put(5, null);
        assertEquals(BindingToolLogic.Outcome.BOUND_PENDING, this.use(this.selection(), this.targetPosition).outcome);
        assertEquals(
            1,
            this.source.registry.getTargets()
                .size());
    }

    @Test
    public void bindingLimitIsPreservedButDuplicatesAtTheLimitStillReuseTheRecord() {
        this.use(this.selection(), this.targetPosition);
        for (int index = 0; index < TargetRegistry.MAX_TARGETS - 1; index++) {
            this.source.registry.register(new TransferTarget(UUID.randomUUID(), 0, index + 100, 64, 0));
        }
        assertEquals(
            BindingToolLogic.Outcome.DUPLICATE_INTERFACE,
            this.use(this.selection(), this.targetPosition).outcome);
        assertEquals(1, this.source.registrations);
        this.context.grids.put(6, new Object());
        assertEquals(BindingToolLogic.Outcome.LIMIT_REACHED, this.use(this.selection(), position(6)).outcome);
        assertEquals(
            TargetRegistry.MAX_TARGETS,
            this.source.registry.getTargets()
                .size());
    }

    @Test
    public void ordinaryRightClickLeavesNormalBlockInteractionAndToolDataUntouched() {
        final BindingToolLogic.Result result = BindingToolLogic
            .use(false, this.selection(), this.sourcePosition, this.context);
        assertEquals(BindingToolLogic.Outcome.PASS, result.outcome);
        assertNull(result.selection);
        assertEquals(0, this.context.lookups);
        assertFalse(BindingToolLogic.handleOnServer(false, false));
        assertFalse(BindingToolLogic.consumeClientUse(true, false));
    }

    @Test
    public void onlyServerUseHandlesShiftAndClientCannotRegister() {
        assertFalse(BindingToolLogic.handleOnServer(true, true));
        assertTrue(BindingToolLogic.consumeClientUse(true, true));
        assertTrue(BindingToolLogic.handleOnServer(false, true));
        assertFalse(BindingToolLogic.consumeClientUse(false, true));
        this.context.server = false;
        assertEquals(BindingToolLogic.Outcome.PASS, this.use(this.selection(), this.targetPosition).outcome);
        assertEquals(0, this.context.lookups);
        assertEquals(0, this.source.registrations);
    }

    private BindingToolLogic.Result use(final BindingToolLogic.SourceReference selected,
        final BindingToolLogic.Position clicked) {
        return BindingToolLogic.use(true, selected, clicked, this.context);
    }

    private BindingToolLogic.SourceReference selection() {
        return new BindingToolLogic.SourceReference(this.sourcePosition, this.source.id);
    }

    private static BindingToolLogic.Position position(final int x) {
        return new BindingToolLogic.Position(0, x, 64, 0);
    }

    private static final class Source implements BindingToolLogic.Source {

        final UUID id = UUID.randomUUID();
        final Object grid = new Object();
        final TargetRegistry registry = new TargetRegistry();
        boolean locked;
        boolean permission = true;
        int registrations;

        @Override
        public UUID identity() {
            return this.id;
        }

        @Override
        public boolean isLocked() {
            return this.locked;
        }

        @Override
        public boolean canConfigure() {
            return this.permission;
        }

        @Override
        public Object grid() {
            return this.grid;
        }

        @Override
        public List<TransferTarget> targets() {
            return this.registry.getTargets();
        }

        @Override
        public UUID register(final TransferTarget target) {
            this.registrations++;
            return this.registry.register(target);
        }
    }

    /** A world contract double: any attempt to query an unloaded/cross-dimension block fails the test. */
    private static final class Context implements BindingToolLogic.Context {

        final Map<Integer, Source> sources = new HashMap<>();
        final Map<Integer, Object> grids = new HashMap<>();
        final Set<Integer> unloaded = new HashSet<>();
        boolean server = true;
        boolean targetPermission = true;
        int lookups;

        @Override
        public boolean isServer() {
            return this.server;
        }

        @Override
        public int dimension() {
            return 0;
        }

        @Override
        public boolean isLoaded(final BindingToolLogic.Position position) {
            return position.dimension == 0 && !this.unloaded.contains(position.x);
        }

        @Override
        public BindingToolLogic.Source sourceAt(final BindingToolLogic.Position position) {
            this.checkLoaded(position);
            this.lookups++;
            return this.sources.get(position.x);
        }

        @Override
        public boolean isInterface(final BindingToolLogic.Position position) {
            this.checkLoaded(position);
            return this.grids.containsKey(position.x);
        }

        @Override
        public boolean canUseInterface(final BindingToolLogic.Position position) {
            this.checkLoaded(position);
            return this.targetPermission;
        }

        @Override
        public Object interfaceGrid(final BindingToolLogic.Position position) {
            this.checkLoaded(position);
            return this.grids.get(position.x);
        }

        private void checkLoaded(final BindingToolLogic.Position position) {
            if (!this.isLoaded(position)) {
                throw new AssertionError("Unloaded or foreign world access");
            }
        }
    }
}
