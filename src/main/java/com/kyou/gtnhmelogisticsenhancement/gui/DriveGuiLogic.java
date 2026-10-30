package com.kyou.gtnhmelogisticsenhancement.gui;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import com.kyou.gtnhmelogisticsenhancement.tile.DriveSlotLayout;
import com.kyou.gtnhmelogisticsenhancement.transfer.TargetRegistry;
import com.kyou.gtnhmelogisticsenhancement.transfer.TransferClock;

import appeng.api.config.AccessRestriction;

/** Validation shared by the real server container and ordinary JVM tests. */
public final class DriveGuiLogic {

    public interface Context {

        boolean canUse();

        boolean isLocked();

        UUID session();

        long revision();

        void setPage(int group);

        boolean hasTarget(UUID id);

        TargetRegistry.Route getRoute(int group);

        void setRoute(int group, TargetRegistry.Route route);

        void setPeriod(int seconds);

        void setAccess(AccessRestriction access);

        void setStoragePriority(int priority);

        void setFillPercent(int group, int percent);

        void updateTarget(UUID id, String name, int priority);

        void deleteTarget(UUID id);
    }

    private DriveGuiLogic() {}

    public static String apply(final DriveGuiAction action, final Context context) {
        if (action == null || action.kind == null
            || !context.session()
                .equals(action.session)
            || !context.canUse()) {
            return "denied";
        }
        if (action.revision != context.revision()) {
            return "stale";
        }
        if (action.kind == DriveGuiAction.Kind.PAGE) {
            if (!validGroup(action.group)) {
                return "invalid";
            }
            context.setPage(action.group);
            return "ok";
        }
        if (context.isLocked()) {
            return "locked";
        }
        try {
            switch (action.kind) {
                case UNASSIGNED:
                case AUTOMATIC:
                case SPECIFIC:
                case SPECIFIC_MODE:
                case TOGGLE_TARGET:
                    if (!validGroup(action.group)) {
                        return "invalid";
                    }
                    final TargetRegistry.Route route;
                    if (action.kind == DriveGuiAction.Kind.SPECIFIC_MODE) {
                        route = TargetRegistry.Route.specific(
                            context.getRoute(action.group)
                                .getTargetIds());
                    } else if (action.kind == DriveGuiAction.Kind.TOGGLE_TARGET) {
                        if (!context.hasTarget(action.target)) {
                            return "stale";
                        }
                        final Set<UUID> selected = new LinkedHashSet<>(
                            context.getRoute(action.group)
                                .getTargetIds());
                        if (!selected.remove(action.target)) {
                            selected.add(action.target);
                        }
                        route = TargetRegistry.Route.specific(selected);
                    } else if (action.kind == DriveGuiAction.Kind.SPECIFIC) {
                        if (!context.hasTarget(action.target)) {
                            return "stale";
                        }
                        route = TargetRegistry.Route.specific(action.target);
                    } else {
                        route = action.kind == DriveGuiAction.Kind.AUTOMATIC ? TargetRegistry.Route.automatic()
                            : TargetRegistry.Route.unassigned();
                    }
                    context.setRoute(action.group, route);
                    break;
                case PERIOD:
                    final int seconds = ConfigurationInput
                        .clampInteger(action.number, TransferClock.MIN_SECONDS, TransferClock.MAX_SECONDS);
                    context.setPeriod(seconds);
                    break;
                case ACCESS:
                    final AccessRestriction access = AccessRestriction.valueOf(action.text);
                    if (access == AccessRestriction.NO_ACCESS) {
                        return "invalid";
                    }
                    context.setAccess(access);
                    break;
                case UPDATE_TARGET:
                    if (!context.hasTarget(action.target)) {
                        return "stale";
                    }
                    context.updateTarget(
                        action.target,
                        ConfigurationInput.alias(action.text),
                        ConfigurationInput.clampInteger(action.number, Integer.MIN_VALUE, Integer.MAX_VALUE));
                    break;
                case FILL_PERCENT:
                    if (!validGroup(action.group)) return "invalid";
                    context.setFillPercent(action.group, ConfigurationInput.clampInteger(action.number, 0, 100));
                    break;
                case STORAGE_PRIORITY:
                    context.setStoragePriority(
                        ConfigurationInput.clampInteger(action.number, Integer.MIN_VALUE, Integer.MAX_VALUE));
                    break;
                case DELETE_TARGET:
                    if (!context.hasTarget(action.target)) {
                        return "stale";
                    }
                    context.deleteTarget(action.target);
                    break;
                default:
                    return "invalid";
            }
            return "ok";
        } catch (IllegalArgumentException | NullPointerException failure) {
            return "invalid";
        } catch (IllegalStateException failure) {
            return "locked";
        }
    }

    public static int integer(final String text) {
        if (text == null || !text.matches("-?[0-9]{1,10}")) {
            throw new IllegalArgumentException("Expected an integer");
        }
        return Integer.parseInt(text);
    }

    public static boolean validGroup(final int group) {
        return group >= 0 && group < DriveSlotLayout.GROUP_COUNT;
    }

    public static boolean visibleSlot(final int slot, final int group) {
        return validGroup(group) && slot >= 0
            && slot < DriveSlotLayout.TOTAL_SLOTS
            && DriveSlotLayout.group(slot) == group;
    }

    public static boolean validClick(final int slot, final int button, final int mode, final int group) {
        if (slot >= 100 || slot >= 0 && slot < 64 && !visibleSlot(slot, group)) {
            return false;
        }
        if (mode == 5) {
            final int stage = button & 3;
            return button >= 0 && button <= 6 && stage != 3 && (stage != 1 || slot >= 0) && (slot >= 0 || slot == -999);
        }
        if (mode == 0 || mode == 1) {
            return (button == 0 || button == 1) && (slot >= 0 || slot == -999);
        }
        if (slot < 0) {
            return false;
        }
        if (mode == 2) {
            return button >= 0 && button < 9;
        }
        return mode == 3 && (button == 0 || button == 2) || (mode == 4 || mode == 6) && (button == 0 || button == 1);
    }

    public static boolean validHotbarSwap(final int slot, final int mode, final int hotbarCount) {
        return mode != 2 || slot >= 64 || hotbarCount <= 1;
    }

    public static boolean validView(final long requestedPage, final long currentPage, final long sequence,
        final long lastSequence) {
        return requestedPage == currentPage && sequence > 0 && sequence > lastSequence;
    }
}
