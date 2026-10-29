package com.kyou.gtnhmelogisticsenhancement.gui;

import java.util.UUID;

/** A request, never an authoritative copy of machine configuration. */
public final class DriveGuiAction {

    public enum Kind {
        PAGE,
        UNASSIGNED,
        AUTOMATIC,
        SPECIFIC,
        PERIOD,
        ACCESS,
        UPDATE_TARGET,
        DELETE_TARGET,
        TOGGLE_TARGET,
        SPECIFIC_MODE
    }

    public final Kind kind;
    public final UUID session;
    public final long revision;
    public final int group;
    public final UUID target;
    public final String text;
    public final String number;

    public DriveGuiAction(Kind kind, UUID session, long revision, int group, UUID target, String text, String number) {
        this.kind = kind;
        this.session = session;
        this.revision = revision;
        this.group = group;
        this.target = target;
        this.text = text;
        this.number = number;
    }
}
