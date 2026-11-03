package com.kyou.gtnhmelogisticsenhancement.gui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.junit.Test;

import com.kyou.gtnhmelogisticsenhancement.tile.DriveSlotLayout;
import com.kyou.gtnhmelogisticsenhancement.transfer.TargetRegistry;
import com.kyou.gtnhmelogisticsenhancement.transfer.TransferClock;
import com.kyou.gtnhmelogisticsenhancement.transfer.TransferTarget;

import appeng.api.config.AccessRestriction;

public class DriveGuiLogicTest {

    private final Context context = new Context();
    private final UUID first = this.context.registry.register(new TransferTarget(UUID.randomUUID(), 0, 10, 64, 0));
    private final UUID second = this.context.registry.register(new TransferTarget(UUID.randomUUID(), 0, 20, 64, 0));

    private DriveGuiAction action(DriveGuiAction.Kind kind, int group, UUID target, String text, String number) {
        return new DriveGuiAction(
            kind,
            this.context.session,
            this.context.settings.revision,
            group,
            target,
            text,
            number);
    }

    private String apply(DriveGuiAction.Kind kind, int group, UUID target, String text, String number) {
        return DriveGuiLogic.apply(this.action(kind, group, target, text, number), this.context);
    }

    @Test
    public void allPhysicalSlotsBelongToExactlyOneVisibleGroup() {
        for (int slot = 0; slot < 64; slot++) {
            int matches = 0;
            for (int page = 0; page < 8; page++) {
                matches += DriveGuiLogic.visibleSlot(slot, page) ? 1 : 0;
            }
            assertEquals(1, matches);
            assertTrue(DriveGuiLogic.visibleSlot(slot, slot / 8));
        }
        assertFalse(DriveGuiLogic.visibleSlot(-1, 0));
        assertFalse(DriveGuiLogic.visibleSlot(64, 7));
        assertFalse(DriveGuiLogic.visibleSlot(0, -1));
        assertFalse(DriveGuiLogic.visibleSlot(0, 8));
    }

    @Test
    public void pageSelectionDoesNotChangeRoutingOrConfiguration() {
        assertEquals("ok", this.apply(DriveGuiAction.Kind.PAGE, 7, null, "", ""));
        assertEquals(7, this.context.page);
        assertEquals(0, this.context.settings.mutations);
        assertEquals(
            TargetRegistry.Mode.UNASSIGNED,
            this.context.registry.getRoute(7)
                .getMode());
    }

    @Test
    public void invalidPagesAndRouteGroupsAreRejected() {
        for (int group : new int[] { -1, 8, Integer.MAX_VALUE }) {
            assertEquals("invalid", this.apply(DriveGuiAction.Kind.PAGE, group, null, "", ""));
            assertEquals("invalid", this.apply(DriveGuiAction.Kind.AUTOMATIC, group, null, "", ""));
        }
        assertEquals(0, this.context.settings.mutations);
    }

    @Test
    public void wrongContainerSessionCannotModifyAnything() {
        final DriveGuiAction request = new DriveGuiAction(
            DriveGuiAction.Kind.DELETE_TARGET,
            UUID.randomUUID(),
            this.context.settings.revision,
            0,
            this.first,
            "",
            "");
        assertEquals("denied", DriveGuiLogic.apply(request, this.context));
        assertEquals(
            2,
            this.context.registry.getTargets()
                .size());
    }

    @Test
    public void failedServerIdentityRangeOrBuildGateRejectsEveryOperation() {
        this.context.canUse = false;
        for (DriveGuiAction.Kind kind : DriveGuiAction.Kind.values()) {
            assertEquals("denied", this.apply(kind, 0, this.first, "READ", "60"));
        }
        assertEquals(0, this.context.settings.mutations);
    }

    @Test
    public void staleConfigurationCannotOverwriteNewerData() {
        final DriveGuiAction request = this.action(DriveGuiAction.Kind.UPDATE_TARGET, 0, this.first, "old edit", "1");
        this.context.settings.revision++;
        assertEquals("stale", DriveGuiLogic.apply(request, this.context));
        assertEquals(
            "ME Dual Interface",
            this.context.target(this.first)
                .getDisplayName());
    }

    @Test
    public void recoveryLocksAllMutationsButAllowsViewingOtherGroups() {
        this.context.locked = true;
        for (DriveGuiAction.Kind kind : DriveGuiAction.Kind.values()) {
            assertEquals(
                kind == DriveGuiAction.Kind.PAGE ? "ok" : "locked",
                this.apply(kind, 1, this.first, "READ", "60"));
        }
        assertEquals(1, this.context.page);
        assertEquals(0, this.context.settings.mutations);
    }

    @Test
    public void allThreeRouteModesUseTheExistingRegistry() {
        assertEquals("ok", this.apply(DriveGuiAction.Kind.SPECIFIC, 3, this.second, "", ""));
        assertEquals(
            this.second,
            this.context.registry.getRoute(3)
                .getTargetId());
        assertEquals("ok", this.apply(DriveGuiAction.Kind.AUTOMATIC, 3, null, "", ""));
        assertEquals(
            TargetRegistry.Mode.AUTOMATIC,
            this.context.registry.getRoute(3)
                .getMode());
        assertEquals("ok", this.apply(DriveGuiAction.Kind.UNASSIGNED, 3, null, "", ""));
        assertEquals(
            TargetRegistry.Mode.UNASSIGNED,
            this.context.registry.getRoute(3)
                .getMode());
        assertEquals(
            TargetRegistry.Mode.UNASSIGNED,
            this.context.registry.getRoute(4)
                .getMode());
    }

    @Test
    public void unknownAndDeletedTargetIdsCannotBeSelected() {
        assertEquals("stale", this.apply(DriveGuiAction.Kind.SPECIFIC, 0, UUID.randomUUID(), "", ""));
        assertEquals("stale", this.apply(DriveGuiAction.Kind.SPECIFIC, 0, null, "", ""));
        this.context.registry.remove(this.first);
        assertEquals("stale", this.apply(DriveGuiAction.Kind.UPDATE_TARGET, 0, this.first, "gone", "0"));
        assertEquals("stale", this.apply(DriveGuiAction.Kind.DELETE_TARGET, 0, this.first, "", ""));
    }

    @Test
    public void stableIdsSurvivePriorityReorderingAndOtherDeletion() {
        assertEquals("ok", this.apply(DriveGuiAction.Kind.UPDATE_TARGET, 0, this.second, "Chosen", "100"));
        assertEquals(
            this.second,
            this.context.registry.byPriority()
                .get(0)
                .getId());
        assertEquals("ok", this.apply(DriveGuiAction.Kind.DELETE_TARGET, 0, this.first, "", ""));
        assertEquals("ok", this.apply(DriveGuiAction.Kind.SPECIFIC, 5, this.second, "", ""));
        assertEquals(
            "Chosen",
            this.context.target(this.second)
                .getDisplayName());
        assertEquals(
            this.second,
            this.context.registry.getRoute(5)
                .getTargetId());
    }

    @Test
    public void deletionUsesExistingGroupReferenceCleanup() {
        this.context.registry.setRoute(0, TargetRegistry.Route.specific(this.first));
        this.context.registry.setRoute(1, TargetRegistry.Route.automatic());
        assertEquals("ok", this.apply(DriveGuiAction.Kind.DELETE_TARGET, 0, this.first, "", ""));
        assertEquals(
            TargetRegistry.Mode.UNASSIGNED,
            this.context.registry.getRoute(0)
                .getMode());
        assertEquals(
            TargetRegistry.Mode.AUTOMATIC,
            this.context.registry.getRoute(1)
                .getMode());
    }

    @Test
    public void periodAcceptsBothBoundsAndDefault() {
        for (String value : new String[] { "1", "60", "3600", "86400" }) {
            assertEquals("ok", this.apply(DriveGuiAction.Kind.PERIOD, 0, null, "", value));
            assertEquals(Integer.parseInt(value), this.context.settings.periods[0]);
        }
    }

    @Test
    public void malformedPeriodsDoNotMutate() {
        for (String value : new String[] { "1.5", "1e3", "", " 60", "1\n", "+2" }) {
            assertEquals("invalid", this.apply(DriveGuiAction.Kind.PERIOD, 0, null, "", value));
        }
        assertEquals(TransferClock.DEFAULT_SECONDS, this.context.settings.periods[0]);
        assertEquals(0, this.context.settings.mutations);
    }

    @Test
    public void allSupportedAccessModesReachTheExistingService() {
        for (AccessRestriction access : new AccessRestriction[] { AccessRestriction.READ, AccessRestriction.WRITE,
            AccessRestriction.READ_WRITE }) {
            assertEquals("ok", this.apply(DriveGuiAction.Kind.ACCESS, 0, null, access.name(), ""));
            assertEquals(access, this.context.access);
        }
    }

    @Test
    public void noAccessAndReshuffleNamesAreNotGuiAccessModes() {
        for (String value : new String[] { "NO_ACCESS", "RESHUFFLE_ACCESS", "read", "" }) {
            assertEquals("invalid", this.apply(DriveGuiAction.Kind.ACCESS, 0, null, value, ""));
        }
        assertEquals(0, this.context.settings.mutations);
    }

    @Test
    public void aliasesUseTheRegistryPrintableLengthValidation() {
        for (String value : new String[] { "", " ", "bad\nname" }) {
            assertEquals("invalid", this.apply(DriveGuiAction.Kind.UPDATE_TARGET, 0, this.first, value, "0"));
        }
        assertEquals("ok", this.apply(DriveGuiAction.Kind.UPDATE_TARGET, 0, this.first, " local name ", "-3"));
        assertEquals(
            "local name",
            this.context.target(this.first)
                .getDisplayName());
        assertEquals(
            -3,
            this.context.target(this.first)
                .getPriority());
        assertEquals(1, this.context.settings.mutations);
    }

    @Test
    public void prioritiesAcceptFullSignedIntegerRangeAndClampOverflow() {
        assertEquals("ok", this.apply(DriveGuiAction.Kind.UPDATE_TARGET, 0, this.first, "A", "-2147483648"));
        assertEquals(
            Integer.MIN_VALUE,
            this.context.target(this.first)
                .getPriority());
        assertEquals("ok", this.apply(DriveGuiAction.Kind.UPDATE_TARGET, 0, this.first, "A", "2147483647"));
        assertEquals("ok", this.apply(DriveGuiAction.Kind.UPDATE_TARGET, 0, this.first, "A", "2147483648"));
        assertEquals(
            Integer.MAX_VALUE,
            this.context.target(this.first)
                .getPriority());
    }

    @Test
    public void repeatedOldRequestCannotExecuteAgain() {
        final DriveGuiAction request = this.action(DriveGuiAction.Kind.UPDATE_TARGET, 0, this.first, "new", "5");
        assertEquals("ok", DriveGuiLogic.apply(request, this.context));
        assertEquals("stale", DriveGuiLogic.apply(request, this.context));
        assertEquals(1, this.context.settings.mutations);
    }

    @Test
    public void selectedTargetsToggleIndependentlyByStableIdAndEmptySelectionStaysOff() {
        assertEquals("ok", this.apply(DriveGuiAction.Kind.SPECIFIC_MODE, 2, null, "", ""));
        assertTrue(
            this.context.registry.getRoute(2)
                .getTargetIds()
                .isEmpty());
        assertEquals("ok", this.apply(DriveGuiAction.Kind.TOGGLE_TARGET, 2, this.first, "", ""));
        assertEquals("ok", this.apply(DriveGuiAction.Kind.TOGGLE_TARGET, 2, this.second, "", ""));
        assertEquals(
            2,
            this.context.registry.getRoute(2)
                .getTargetIds()
                .size());
        assertEquals("ok", this.apply(DriveGuiAction.Kind.TOGGLE_TARGET, 2, this.first, "", ""));
        assertEquals(
            java.util.Collections.singleton(this.second),
            this.context.registry.getRoute(2)
                .getTargetIds());
        assertTrue(
            this.context.registry.getRoute(1)
                .getTargetIds()
                .isEmpty());
        assertEquals("ok", this.apply(DriveGuiAction.Kind.TOGGLE_TARGET, 2, this.second, "", ""));
        assertEquals(
            TargetRegistry.Mode.SPECIFIC,
            this.context.registry.getRoute(2)
                .getMode());
        assertTrue(
            this.context.registry.getRoute(2)
                .getTargetIds()
                .isEmpty());
    }

    @Test
    public void staleToggleCannotUndoANewerSelectionAndDeletedTargetsAreRejected() {
        final DriveGuiAction request = this.action(DriveGuiAction.Kind.TOGGLE_TARGET, 1, this.first, "", "");
        assertEquals("ok", DriveGuiLogic.apply(request, this.context));
        assertEquals("stale", DriveGuiLogic.apply(request, this.context));
        assertTrue(
            this.context.registry.getRoute(1)
                .getTargetIds()
                .contains(this.first));
        this.context.registry.remove(this.first);
        assertEquals("stale", this.apply(DriveGuiAction.Kind.TOGGLE_TARGET, 1, this.first, "", ""));
    }

    @Test
    public void serverNormalizesOrdinaryInputWithoutRelyingOnTheClient() {
        assertEquals("ok", this.apply(DriveGuiAction.Kind.PERIOD, 0, null, "", "9223372036854775808"));
        assertEquals(86400, this.context.settings.periods[0]);
        assertEquals("ok", this.apply(DriveGuiAction.Kind.PERIOD, 0, null, "", "-9223372036854775809"));
        assertEquals(1, this.context.settings.periods[0]);
        final String alias = String.join("", java.util.Collections.nCopies(80, "x"));
        assertEquals(
            "ok",
            this.apply(DriveGuiAction.Kind.UPDATE_TARGET, 0, this.first, alias, "999999999999999999999999"));
        assertEquals(
            64,
            this.context.target(this.first)
                .getDisplayName()
                .length());
        assertEquals(
            Integer.MAX_VALUE,
            this.context.target(this.first)
                .getPriority());
        assertEquals(
            "ok",
            this.apply(DriveGuiAction.Kind.UPDATE_TARGET, 0, this.first, "A", "-99999999999999999999999"));
        assertEquals(
            Integer.MIN_VALUE,
            this.context.target(this.first)
                .getPriority());
    }

    @Test
    public void storagePriorityAcceptsSignedIntegersAndClampsBeyondLongWithoutChangingTargetPriority() {
        this.context.registry.updateMetadata(this.first, "Target", 123);
        this.context.registry.setRoute(3, TargetRegistry.Route.specific(this.first));
        final String[] inputs = { "0", "-1", "-2147483648", "2147483647", "999999999999999999999999",
            "-999999999999999999999999" };
        final int[] expected = { 0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE };
        for (int index = 0; index < inputs.length; index++) {
            assertEquals("ok", this.apply(DriveGuiAction.Kind.STORAGE_PRIORITY, 0, null, "", inputs[index]));
            assertEquals(expected[index], this.context.settings.storagePriorities[0]);
            assertEquals(
                123,
                this.context.target(this.first)
                    .getPriority());
            assertEquals(
                this.first,
                this.context.registry.getRoute(3)
                    .getTargetId());
            assertEquals(AccessRestriction.READ_WRITE, this.context.access);
            assertEquals(TransferClock.DEFAULT_SECONDS, this.context.settings.periods[0]);
        }
        assertEquals(inputs.length, this.context.settings.mutations);
    }

    @Test
    public void malformedStoragePriorityCannotMutateAnyConfiguration() {
        for (final String input : new String[] { null, "", "-", "1.5", "+2", " 7", "1e3", "1\n",
            "999999999999999999999999999999999" }) {
            assertEquals("invalid", this.apply(DriveGuiAction.Kind.STORAGE_PRIORITY, 0, null, "", input));
        }
        assertEquals(0, this.context.settings.storagePriorities[0]);
        assertEquals(0, this.context.settings.mutations);
    }

    @Test
    public void storagePriorityStillRequiresCurrentSessionRevisionPermissionAndUnlockedState() {
        final DriveGuiAction original = this.action(DriveGuiAction.Kind.STORAGE_PRIORITY, 0, null, "", "-42");
        this.context.canUse = false;
        assertEquals("denied", DriveGuiLogic.apply(original, this.context));
        this.context.canUse = true;
        this.context.locked = true;
        assertEquals("locked", DriveGuiLogic.apply(original, this.context));
        this.context.locked = false;
        assertEquals("ok", DriveGuiLogic.apply(original, this.context));
        assertEquals("stale", DriveGuiLogic.apply(original, this.context));
        assertEquals(-42, this.context.settings.storagePriorities[0]);
        assertEquals(1, this.context.settings.mutations);
    }

    @Test
    public void fillPercentUsesIndependentGroupsAndClampsUserInputWithoutChangingRouting() {
        final TargetRegistry.Route route = TargetRegistry.Route.specific(this.first);
        this.context.registry.setRoute(4, route);
        for (final String number : new String[] { "80", "-99999999999999999999", "99999999999999999999" }) {
            assertEquals("ok", this.apply(DriveGuiAction.Kind.FILL_PERCENT, 4, null, "", number));
            assertEquals(
                number.equals("80") ? 80 : number.startsWith("-") ? 0 : 100,
                this.context.registry.getFillPercent(4));
            assertEquals(100, this.context.registry.getFillPercent(0));
            assertEquals(route, this.context.registry.getRoute(4));
        }
        assertEquals(TransferClock.DEFAULT_SECONDS, this.context.settings.periods[0]);
        assertEquals(AccessRestriction.READ_WRITE, this.context.access);
        assertEquals(0, this.context.settings.storagePriorities[0]);
    }

    @Test
    public void fillPercentRetainsTheExistingSessionPermissionLockAndRevisionGates() {
        final DriveGuiAction request = this.action(DriveGuiAction.Kind.FILL_PERCENT, 7, null, "", "80");
        this.context.canUse = false;
        assertEquals("denied", DriveGuiLogic.apply(request, this.context));
        this.context.canUse = true;
        this.context.locked = true;
        assertEquals("locked", DriveGuiLogic.apply(request, this.context));
        this.context.locked = false;
        assertEquals("ok", DriveGuiLogic.apply(request, this.context));
        assertEquals("stale", DriveGuiLogic.apply(request, this.context));
        assertEquals(80, this.context.registry.getFillPercent(7));
        assertEquals(1, this.context.settings.mutations);
    }

    @Test
    public void fillPercentRejectsInvalidGroupsAndMalformedNumbers() {
        for (final int group : new int[] { -1, 8, Integer.MAX_VALUE }) {
            assertEquals("invalid", this.apply(DriveGuiAction.Kind.FILL_PERCENT, group, null, "", "80"));
        }
        for (final String number : new String[] { "x", "80%", "80.5", "", null,
            "99999999999999999999999999999999999" }) {
            assertEquals("invalid", this.apply(DriveGuiAction.Kind.FILL_PERCENT, 0, null, "", number));
        }
        assertEquals(0, this.context.settings.mutations);
        assertEquals(100, this.context.registry.getFillPercent(0));
    }

    @Test
    public void storagePrioritiesAndPeriodsAreIndependentForEveryGroup() {
        for (int group = 0; group < DriveSlotLayout.GROUP_COUNT; group++) {
            assertEquals("ok", this.apply(DriveGuiAction.Kind.STORAGE_PRIORITY, group, null, "", "-" + (group + 1)));
            assertEquals("ok", this.apply(DriveGuiAction.Kind.PERIOD, group, null, "", Integer.toString(group + 1)));
            for (int checked = 0; checked < DriveSlotLayout.GROUP_COUNT; checked++) {
                assertEquals(checked <= group ? -checked - 1 : 0, this.context.settings.storagePriorities[checked]);
                assertEquals(
                    checked <= group ? checked + 1 : TransferClock.DEFAULT_SECONDS,
                    this.context.settings.periods[checked]);
                assertEquals(
                    TargetRegistry.Mode.UNASSIGNED,
                    this.context.registry.getRoute(checked)
                        .getMode());
                assertEquals(100, this.context.registry.getFillPercent(checked));
            }
        }
        assertEquals(AccessRestriction.READ_WRITE, this.context.access);
    }

    @Test
    public void forgedStoragePriorityAndPeriodGroupsCannotMutateAnyConfiguration() {
        for (final int group : new int[] { Integer.MIN_VALUE, -1, DriveSlotLayout.GROUP_COUNT, Integer.MAX_VALUE }) {
            assertEquals("invalid", this.apply(DriveGuiAction.Kind.STORAGE_PRIORITY, group, null, "", "12"));
            assertEquals("invalid", this.apply(DriveGuiAction.Kind.PERIOD, group, null, "", "12"));
        }
        assertEquals(0, this.context.settings.mutations);
        for (int group = 0; group < DriveSlotLayout.GROUP_COUNT; group++) {
            assertEquals(0, this.context.settings.storagePriorities[group]);
            assertEquals(TransferClock.DEFAULT_SECONDS, this.context.settings.periods[group]);
        }
    }

    @Test
    public void delayedGroupSettingsKeepTheirOriginalGroupAfterPageChanges() {
        for (final DriveGuiAction.Kind kind : new DriveGuiAction.Kind[] { DriveGuiAction.Kind.STORAGE_PRIORITY,
            DriveGuiAction.Kind.PERIOD }) {
            final Context viewer = new Context();
            final DriveGuiAction pending = new DriveGuiAction(
                kind,
                viewer.session,
                viewer.revision(),
                3,
                null,
                "",
                "25");
            assertEquals(
                "ok",
                DriveGuiLogic.apply(
                    new DriveGuiAction(DriveGuiAction.Kind.PAGE, viewer.session, viewer.revision(), 6, null, "", ""),
                    viewer));
            assertEquals("ok", DriveGuiLogic.apply(pending, viewer));
            assertEquals(6, viewer.page);
            assertEquals(
                kind == DriveGuiAction.Kind.PERIOD ? 25 : TransferClock.DEFAULT_SECONDS,
                viewer.settings.periods[3]);
            assertEquals(kind == DriveGuiAction.Kind.STORAGE_PRIORITY ? 25 : 0, viewer.settings.storagePriorities[3]);
            assertEquals(TransferClock.DEFAULT_SECONDS, viewer.settings.periods[6]);
            assertEquals(0, viewer.settings.storagePriorities[6]);
            assertEquals("stale", DriveGuiLogic.apply(pending, viewer));
            assertEquals(1, viewer.settings.mutations);
        }
    }

    @Test
    public void concurrentPlayersShareConfigurationButRetainSessionAndRevisionProtection() {
        final Context other = new Context(this.context.settings);
        other.page = 2;
        final DriveGuiAction pending = new DriveGuiAction(
            DriveGuiAction.Kind.PERIOD,
            other.session,
            other.revision(),
            2,
            null,
            "",
            "90");
        assertEquals("ok", this.apply(DriveGuiAction.Kind.STORAGE_PRIORITY, 7, null, "", "-42"));
        assertEquals("stale", DriveGuiLogic.apply(pending, other));
        assertEquals(TransferClock.DEFAULT_SECONDS, other.settings.periods[2]);
        assertEquals(
            "denied",
            DriveGuiLogic.apply(
                new DriveGuiAction(
                    DriveGuiAction.Kind.PERIOD,
                    this.context.session,
                    other.revision(),
                    2,
                    null,
                    "",
                    "90"),
                other));
        assertEquals(
            "ok",
            DriveGuiLogic.apply(
                new DriveGuiAction(DriveGuiAction.Kind.PERIOD, other.session, other.revision(), 2, null, "", "90"),
                other));
        assertEquals(90, this.context.settings.periods[2]);
        assertEquals(-42, this.context.settings.storagePriorities[7]);
        assertEquals(0, this.context.page);
        assertEquals(2, other.page);
        assertEquals(2, this.context.settings.mutations);
    }

    private static final class Context implements DriveGuiLogic.Context {

        final UUID session = UUID.randomUUID();
        final Settings settings;
        final TargetRegistry registry;
        boolean canUse = true;
        boolean locked;
        int page;
        AccessRestriction access = AccessRestriction.READ_WRITE;

        Context() {
            this(new Settings());
        }

        Context(final Settings settings) {
            this.settings = settings;
            this.registry = settings.registry;
        }

        @Override
        public boolean canUse() {
            return this.canUse;
        }

        @Override
        public boolean isLocked() {
            return this.locked;
        }

        @Override
        public UUID session() {
            return this.session;
        }

        @Override
        public long revision() {
            return this.settings.revision;
        }

        @Override
        public void setPage(int group) {
            this.page = group;
        }

        @Override
        public boolean hasTarget(UUID id) {
            return id != null && this.target(id) != null;
        }

        @Override
        public TargetRegistry.Route getRoute(int group) {
            return this.registry.getRoute(group);
        }

        @Override
        public void setRoute(int group, TargetRegistry.Route route) {
            this.registry.setRoute(group, route);
            this.changed();
        }

        @Override
        public void setPeriod(int group, int seconds) {
            this.settings.periods[group] = seconds;
            this.changed();
        }

        @Override
        public void setAccess(AccessRestriction access) {
            this.access = access;
            this.changed();
        }

        @Override
        public void setStoragePriority(int group, int priority) {
            this.settings.storagePriorities[group] = priority;
            this.changed();
        }

        @Override
        public void setFillPercent(int group, int percent) {
            this.registry.setFillPercent(group, percent);
            this.changed();
        }

        @Override
        public void updateTarget(UUID id, String name, int priority) {
            this.registry.updateMetadata(id, name, priority);
            this.changed();
        }

        @Override
        public void deleteTarget(UUID id) {
            this.registry.remove(id);
            this.changed();
        }

        private TransferTarget target(UUID id) {
            return this.registry.getTargets()
                .stream()
                .filter(
                    t -> t.getId()
                        .equals(id))
                .findFirst()
                .orElse(null);
        }

        private void changed() {
            this.settings.revision++;
            this.settings.mutations++;
        }
    }

    private static final class Settings {

        final TargetRegistry registry = new TargetRegistry();
        final int[] periods = new int[DriveSlotLayout.GROUP_COUNT];
        final int[] storagePriorities = new int[DriveSlotLayout.GROUP_COUNT];
        long revision = 1;
        int mutations;

        Settings() {
            java.util.Arrays.fill(this.periods, TransferClock.DEFAULT_SECONDS);
        }
    }
}
