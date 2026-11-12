package com.kyou.gtnhmelogisticsenhancement.energy;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.value.sync.IntSyncValue;
import com.cleanroommc.modularui.value.sync.LongSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.value.sync.StringSyncValue;

/** Read-only, server-to-client values; sampled only while a viewer has this panel open. */
public final class MEEnergyGui {

    private MEEnergyGui() {}

    public static ModularPanel build(final IMEEnergyEndpoint host, final PanelSyncManager sync,
        final UISettings settings) {
        settings.canInteractWith(host.nodeSupport()::canConfigure);
        final LongSyncValue stored = new LongSyncValue(host::readEU);
        final IntSyncValue network = new IntSyncValue(
            () -> host.isBooting() ? 1 : host.isActive() ? 2 : host.isPowered() ? 3 : 0);
        final IntSyncValue color = new IntSyncValue(host::businessColor);
        final IntSyncValue faces = new IntSyncValue(() -> host.connectsToAllSides() ? 1 : 0);
        final StringSyncValue fault = new StringSyncValue(() -> {
            final String data = host.energyState()
                .diagnostic();
            return data.isEmpty() ? host.nodeSupport()
                .diagnostic() : data;
        });
        sync.syncValue("eu", stored);
        sync.syncValue("network", network);
        sync.syncValue("color", color);
        sync.syncValue("faces", faces);
        sync.syncValue("fault", fault);
        final MEEnergySpec spec = host.spec();
        final LongSyncValue age = new LongSyncValue(host.nodeSupport()::groupServiceAge);
        final LongSyncValue expired = new LongSyncValue(host.nodeSupport()::expiredRounds);
        sync.syncValue("serviceAge", age);
        sync.syncValue("expiredRounds", expired);
        final ModularPanel panel = ModularPanel.defaultPanel("meEU", 286, 206);
        panel.child(
            IKey.lang(
                "gtnhmelogisticsenhancement.energy."
                    + (spec.role() == MEEnergySpec.Role.ENERGY ? "energy_name" : "dynamo_name"))
                .asWidget()
                .pos(8, 6));
        panel.child(
            IKey.dynamic(() -> MEEnergyPresentation.text("spec", spec.tierName(), spec.voltage(), spec.amperage()))
                .asWidget()
                .pos(8, 24)
                .scale(0.8f));
        panel.child(
            IKey.dynamic(() -> MEEnergyPresentation.text("stock", stored.getLongValue(), spec.capacity()))
                .asWidget()
                .pos(8, 40)
                .scale(0.7f)
                .maxWidth(270));
        panel.child(
            IKey.dynamic(
                () -> MEEnergyPresentation
                    .text("network", MEEnergyPresentation.text("network." + network.getIntValue())))
                .asWidget()
                .pos(8, 56));
        panel.child(
            IKey.dynamic(
                () -> MEEnergyPresentation.text(
                    "color",
                    color.getIntValue() == 0 ? MEEnergyPresentation.text("default_color")
                        : Integer.toString(color.getIntValue() - 1)))
                .asWidget()
                .pos(8, 72));
        panel.child(
            IKey.dynamic(() -> MEEnergyPresentation.text("faces." + faces.getIntValue()))
                .asWidget()
                .pos(8, 88));
        panel.child(
            IKey.dynamic(
                () -> fault.getStringValue()
                    .isEmpty() ? MEEnergyPresentation.text("ready")
                        : MEEnergyPresentation.text("fault", fault.getStringValue()))
                .asWidget()
                .pos(8, 104)
                .scale(0.8f)
                .maxWidth(270));
        panel.child(
            IKey.lang("gtnhmelogisticsenhancement.energy.loss_note")
                .asWidget()
                .pos(8, 122)
                .scale(0.65f)
                .maxWidth(270));
        panel.child(
            IKey.lang("gtnhmelogisticsenhancement.energy.offline_note")
                .asWidget()
                .pos(8, 140)
                .scale(0.65f)
                .maxWidth(270));
        panel.child(
            IKey.lang("gtnhmelogisticsenhancement.energy.removal_note")
                .asWidget()
                .pos(8, 158)
                .scale(0.65f)
                .maxWidth(270));
        panel.child(
            IKey.dynamic(
                () -> MEEnergyPresentation.text(
                    "service",
                    age.getLongValue() < 0 ? MEEnergyPresentation.text("never")
                        : MEEnergyPresentation.text("ticks_ago", age.getLongValue()),
                    expired.getLongValue()))
                .asWidget()
                .pos(8, 181)
                .scale(0.65f)
                .maxWidth(270));
        return panel;
    }
}
