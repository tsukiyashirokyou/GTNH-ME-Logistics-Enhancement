package com.kyou.metransferdrive.gui;

import java.util.UUID;

import com.kyou.metransferdrive.tile.DriveSlotLayout;
import com.kyou.metransferdrive.transfer.TargetRegistry;
import com.kyou.metransferdrive.transfer.TransferClock;

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

        void setRoute(int group, TargetRegistry.Route route);

        void setPeriod(int seconds);

        void setAccess(AccessRestriction access);

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
                    if (!validGroup(action.group)) {
                        return "invalid";
                    }
                    final TargetRegistry.Route route;
                    if (action.kind == DriveGuiAction.Kind.SPECIFIC) {
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
                    final int seconds = integer(action.number);
                    if (seconds < TransferClock.MIN_SECONDS || seconds > TransferClock.MAX_SECONDS) {
                        return "invalid";
                    }
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
                    context.updateTarget(action.target, action.text, integer(action.number));
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
