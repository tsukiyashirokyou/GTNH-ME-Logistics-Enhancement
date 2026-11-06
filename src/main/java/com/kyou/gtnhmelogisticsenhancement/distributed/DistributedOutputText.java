package com.kyou.gtnhmelogisticsenhancement.distributed;

import net.minecraft.util.StatCollector;

/** Player-facing descriptions; internal fault codes remain unchanged in saved data and logs. */
final class DistributedOutputText {

    private static final String PREFIX = "gtnhmelogisticsenhancement.output.";

    private DistributedOutputText() {}

    static String network(final boolean active, final boolean powered, final boolean booting) {
        return StatCollector.translateToLocal(networkKey(active, powered, booting));
    }

    static String networkKey(final boolean active, final boolean powered, final boolean booting) {
        return PREFIX + "network." + (active ? "online" : booting ? "starting" : powered ? "waiting" : "offline");
    }

    static String capacity(final boolean valid) {
        return StatCollector.translateToLocal(PREFIX + "capacity." + (valid ? "valid" : "invalid"));
    }

    static String diagnostic(final String fault) {
        return StatCollector.translateToLocal(diagnosticKey(fault));
    }

    static String diagnosticKey(final String fault) {
        final String status;
        switch (fault) {
            case "":
                status = "ok";
                break;
            case "UNCERTAIN_OUTPUT":
            case "UNCERTAIN_OUTPUT_RELOAD":
                status = "delivery_uncertain";
                break;
            case "INVALID_SAVED_DATA":
                status = "saved_data";
                break;
            case "AE_SECURITY_BREAK":
                status = "security";
                break;
            case "INVALID_CARD_INVENTORY":
                status = "capacity_cell";
                break;
            case "INVALID_AE_NODE":
                status = "connection_data";
                break;
            case "UNEXPECTED_SINGLE_TANK_DATA":
                status = "incompatible_fluid_data";
                break;
            case "UNSUPPORTED_FLUID_NBT":
                status = "unsupported_fluid";
                break;
            case "INPUT_ARITHMETIC":
                status = "quantity_limit";
                break;
            case "INPUT_PLAN_CONFLICT":
                status = "stock_changed";
                break;
            case "INPUT_INVALID_OR_CALLBACK":
                status = "input_failed";
                break;
            default:
                status = "paused";
                break;
        }
        return PREFIX + "status." + status;
    }
}
