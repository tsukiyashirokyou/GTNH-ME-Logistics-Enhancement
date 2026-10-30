package com.kyou.gtnhmelogisticsenhancement.transfer;

import static org.junit.Assert.assertEquals;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.junit.Test;

public class TargetRegistryNbtTest {

    @Test
    public void roundTripKeepsBindingsAliasesPrioritiesAndAllEightRoutes() {
        final TargetRegistry registry = new TargetRegistry();
        final TransferTarget first = target(0, 0);
        final TransferTarget second = target(1, 100);
        registry.register(first);
        registry.register(second);
        registry.setRoute(0, TargetRegistry.Route.automatic());
        registry.setRoute(7, TargetRegistry.Route.specific(first.getId()));
        final TargetRegistry loaded = TargetRegistryNbt.read(TargetRegistryNbt.write(registry));
        assertEquals(
            first.getId(),
            loaded.getTargets()
                .get(0)
                .getId());
        assertEquals(
            second.getId(),
            loaded.byPriority()
                .get(0)
                .getId());
        assertEquals(
            "Alias 1",
            loaded.getTargets()
                .get(1)
                .getDisplayName());
        assertEquals(
            100,
            loaded.getTargets()
                .get(1)
                .getPriority());
        assertEquals(
            TargetRegistry.Mode.AUTOMATIC,
            loaded.getRoute(0)
                .getMode());
        assertEquals(
            TargetRegistry.Mode.UNASSIGNED,
            loaded.getRoute(1)
                .getMode());
        assertEquals(
            first.getId(),
            loaded.getRoute(7)
                .getTargetId());
        assertEquals(TargetRegistryNbt.write(registry), TargetRegistryNbt.write(loaded));
    }

    @Test
    public void freshRegistryRoundTripKeepsAllGroupsUnassignedAndAtFullFill() {
        final TargetRegistry restored = TargetRegistryNbt.read(TargetRegistryNbt.write(new TargetRegistry()));
        assertEquals(
            0,
            restored.getTargets()
                .size());
        for (int group = 0; group < 8; group++) {
            assertEquals(
                TargetRegistry.Mode.UNASSIGNED,
                restored.getRoute(group)
                    .getMode());
            assertEquals(100, restored.getFillPercent(group));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownVersionCannotSilentlyResetRoutes() {
        final NBTTagCompound data = TargetRegistryNbt.write(new TargetRegistry());
        data.setInteger("version", 99);
        TargetRegistryNbt.read(data);
    }

    @Test(expected = IllegalArgumentException.class)
    public void danglingSpecificReferenceIsRejectedRatherThanChangedToAutomatic() {
        final NBTTagCompound data = TargetRegistryNbt.write(new TargetRegistry());
        final NBTTagCompound route = data.getTagList("routes", 10)
            .getCompoundTagAt(0);
        route.setString("mode", "SPECIFIC");
        route.setString(
            "targetId",
            UUID.randomUUID()
                .toString());
        TargetRegistryNbt.read(data);
    }

    @Test(expected = IllegalArgumentException.class)
    public void repeatedGroupsAreRejected() {
        final NBTTagCompound data = TargetRegistryNbt.write(new TargetRegistry());
        data.getTagList("routes", 10)
            .getCompoundTagAt(1)
            .setInteger("group", 0);
        TargetRegistryNbt.read(data);
    }

    @Test(expected = IllegalArgumentException.class)
    public void malformedTargetListIsNotReadAsAnEmptyRegistry() {
        final NBTTagCompound data = TargetRegistryNbt.write(new TargetRegistry());
        final NBTTagList invalid = new NBTTagList();
        invalid.appendTag(new net.minecraft.nbt.NBTTagString("invalid entry"));
        data.setTag("targets", invalid);
        TargetRegistryNbt.read(data);
    }

    @Test(expected = IllegalArgumentException.class)
    public void duplicateInterfaceCoordinatesCannotLoseOneBindingOnLoad() {
        final TargetRegistry registry = new TargetRegistry();
        registry.register(target(0, 0));
        final NBTTagCompound data = TargetRegistryNbt.write(registry);
        final NBTTagList targets = data.getTagList("targets", 10);
        final NBTTagCompound duplicate = (NBTTagCompound) targets.getCompoundTagAt(0)
            .copy();
        duplicate.setString(
            "id",
            UUID.randomUUID()
                .toString());
        targets.appendTag(duplicate);
        TargetRegistryNbt.read(data);
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingCoordinateCannotAccidentallyBindPositionZero() {
        final TargetRegistry registry = new TargetRegistry();
        registry.register(target(0, 0));
        final NBTTagCompound data = TargetRegistryNbt.write(registry);
        data.getTagList("targets", 10)
            .getCompoundTagAt(0)
            .removeTag("x");
        TargetRegistryNbt.read(data);
    }

    @Test(expected = IllegalArgumentException.class)
    public void singleTargetFieldCannotReplaceTheCurrentSelectionSet() {
        final TargetRegistry registry = new TargetRegistry();
        final TransferTarget target = target(0, 0);
        registry.register(target);
        final NBTTagCompound old = TargetRegistryNbt.write(registry);
        final NBTTagCompound route = old.getTagList("routes", 10)
            .getCompoundTagAt(4);
        route.setString("mode", "SPECIFIC");
        route.setString(
            "targetId",
            target.getId()
                .toString());
        TargetRegistryNbt.read(old);
    }

    @Test
    public void multiSelectionAndExplicitEmptySelectionSurviveNbtRoundTrip() {
        final TargetRegistry registry = new TargetRegistry();
        final UUID first = registry.register(target(0, 0));
        final UUID second = registry.register(target(1, 0));
        registry.setRoute(0, TargetRegistry.Route.specific(java.util.Arrays.asList(first, second)));
        registry.setRoute(7, TargetRegistry.Route.specific(java.util.Collections.emptySet()));
        final TargetRegistry loaded = TargetRegistryNbt.read(TargetRegistryNbt.write(registry));
        assertEquals(
            registry.getRoute(0)
                .getTargetIds(),
            loaded.getRoute(0)
                .getTargetIds());
        assertEquals(
            TargetRegistry.Mode.SPECIFIC,
            loaded.getRoute(7)
                .getMode());
        assertEquals(
            0,
            loaded.getRoute(7)
                .getTargetIds()
                .size());
    }

    @Test
    public void invalidSelectionListsNeverSilentlyResetOrBecomeAutomatic() {
        final TargetRegistry registry = new TargetRegistry();
        final UUID first = registry.register(target(0, 0));
        registry.setRoute(0, TargetRegistry.Route.specific(first));
        for (String[] ids : new String[][] { { first.toString(), first.toString() }, { UUID.randomUUID()
            .toString() }, { "1-1-1-1-1" }, { "bad UUID" } }) {
            final NBTTagCompound data = TargetRegistryNbt.write(registry);
            final NBTTagList selected = new NBTTagList();
            for (String id : ids) {
                selected.appendTag(new net.minecraft.nbt.NBTTagString(id));
            }
            data.getTagList("routes", 10)
                .getCompoundTagAt(0)
                .setTag("targetIds", selected);
            try {
                TargetRegistryNbt.read(data);
                throw new AssertionError("Invalid route selection accepted");
            } catch (IllegalArgumentException expected) {
                // The controller preserves rejected raw configuration in quarantine.
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void wrongSelectionElementTypeIsRejected() {
        final TargetRegistry registry = new TargetRegistry();
        final UUID first = registry.register(target(0, 0));
        registry.setRoute(0, TargetRegistry.Route.specific(first));
        final NBTTagCompound data = TargetRegistryNbt.write(registry);
        final NBTTagList selected = new NBTTagList();
        selected.appendTag(new NBTTagCompound());
        data.getTagList("routes", 10)
            .getCompoundTagAt(0)
            .setTag("targetIds", selected);
        TargetRegistryNbt.read(data);
    }

    @Test
    public void fillPercentRoundTripsForEachGroupWithoutChangingRoutesOrTargetPriorities() {
        final TargetRegistry registry = new TargetRegistry();
        final TransferTarget target = target(0, -123);
        registry.register(target);
        for (int group = 0; group < 8; group++) {
            assertEquals(100, registry.getFillPercent(group));
            registry.setFillPercent(group, group == 7 ? 100 : group * 10);
            registry.setRoute(group, TargetRegistry.Route.specific(target.getId()));
        }
        final TargetRegistry restored = TargetRegistryNbt.read(TargetRegistryNbt.write(registry));
        for (int group = 0; group < 8; group++) {
            assertEquals(group == 7 ? 100 : group * 10, restored.getFillPercent(group));
            assertEquals(
                target.getId(),
                restored.getRoute(group)
                    .getTargetId());
        }
        assertEquals(
            -123,
            restored.getTargets()
                .get(0)
                .getPriority());
        restored.remove(target.getId());
        assertEquals(30, restored.getFillPercent(3));
        assertEquals(
            TargetRegistry.Mode.UNASSIGNED,
            restored.getRoute(3)
                .getMode());
    }

    @Test
    public void previousRoutingVersionsAreRejectedWithoutMigration() {
        for (final int version : new int[] { 1, 2 }) {
            final TargetRegistry registry = new TargetRegistry();
            final TransferTarget target = target(0, 0);
            registry.register(target);
            registry.setRoute(4, TargetRegistry.Route.specific(target.getId()));
            final NBTTagCompound old = TargetRegistryNbt.write(registry);
            old.setInteger("version", version);
            for (int group = 0; group < 8; group++) {
                final NBTTagCompound entry = old.getTagList("routes", 10)
                    .getCompoundTagAt(group);
                entry.removeTag("fillPercent");
                if (version == 1 && group == 4) {
                    entry.removeTag("targetIds");
                    entry.setString(
                        "targetId",
                        target.getId()
                            .toString());
                }
            }
            org.junit.Assert.assertThrows(IllegalArgumentException.class, () -> TargetRegistryNbt.read(old));
        }
    }

    @Test
    public void corruptPercentagesAreRejectedInsteadOfClampedAsUserInput() {
        for (final java.util.function.Consumer<NBTTagCompound> mutation : java.util.Arrays.<java.util.function.Consumer<NBTTagCompound>>asList(
            entry -> entry.removeTag("fillPercent"),
            entry -> entry.setString("fillPercent", "80"),
            entry -> entry.setLong("fillPercent", 80),
            entry -> entry.setInteger("fillPercent", -1),
            entry -> entry.setInteger("fillPercent", 101))) {
            final NBTTagCompound saved = TargetRegistryNbt.write(new TargetRegistry());
            mutation.accept(
                saved.getTagList("routes", 10)
                    .getCompoundTagAt(0));
            org.junit.Assert.assertThrows(IllegalArgumentException.class, () -> TargetRegistryNbt.read(saved));
        }
    }

    private static TransferTarget target(final int x, final int priority) {
        return new TransferTarget(UUID.randomUUID(), 0, x, 64, 0, "Alias " + x, priority);
    }
}
