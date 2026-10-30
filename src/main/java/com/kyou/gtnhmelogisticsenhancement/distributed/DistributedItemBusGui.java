package com.kyou.gtnhmelogisticsenhancement.distributed;

import com.cleanroommc.modularui.factory.PosGuiData;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;

/** Item facade for the shared read-only output-device GUI. */
public final class DistributedItemBusGui {

    private DistributedItemBusGui() {}

    public static ModularPanel build(final MTEDistributedMEOutputBus bus, final PosGuiData data,
        final PanelSyncManager sync, final UISettings settings) {
        return DistributedOutputGui.build(
            bus,
            data,
            sync,
            settings,
            false,
            identity -> DistributedOutputGui.DisplayIcon.item(identity.getItemStack()));
    }
}
