package com.kyou.gtnhmelogisticsenhancement.energy;

import java.util.Locale;
import java.util.Objects;

/** Immutable GTNH 2.9.0 EU capability. No world, AE node or actual stored energy. */
public final class MEEnergySpec {

    public enum Role {
        ENERGY,
        DYNAMO
    }

    public enum Family {
        STANDARD,
        MULTI
    }

    public static final int MIN_TIER = 5;
    public static final int MAX_TIER = 13;
    public static final int BUFFER_TICKS = 24;
    private static final String[] TIER_NAMES = { "IV", "LuV", "ZPM", "UV", "UHV", "UEV", "UIV", "UMV", "UXV" };

    private final Role role;
    private final Family family;
    private final int tier;
    private final int amperage;
    private final long voltage;
    private final long power;
    private final long capacity;
    private final String key;

    private MEEnergySpec(final Role role, final Family family, final int tier, final int amperage) {
        this.role = Objects.requireNonNull(role, "role");
        this.family = Objects.requireNonNull(family, "family");
        checkTier(tier);
        if (family == Family.STANDARD ? amperage != (role == Role.ENERGY ? 2 : 1) : !isMultiAmperage(tier, amperage)) {
            throw new IllegalArgumentException("Unsupported ME energy amperage: " + amperage);
        }
        this.tier = tier;
        this.amperage = amperage;
        // Fixed GTValues.V[5..13], without loading GT classes in pure algorithm tests.
        this.voltage = 8L << (tier * 2);
        this.power = Math.multiplyExact(this.voltage, amperage);
        this.capacity = Math.multiplyExact(this.power, BUFFER_TICKS);
        this.key = family.name()
            .toLowerCase(Locale.ROOT) + "_"
            + role.name()
                .toLowerCase(Locale.ROOT)
            + "_"
            + tierName().toLowerCase(Locale.ROOT)
            + "_"
            + amperage
            + "a";
    }

    public static MEEnergySpec standard(final Role role, final int tier) {
        Objects.requireNonNull(role, "role");
        return new MEEnergySpec(role, Family.STANDARD, tier, role == Role.ENERGY ? 2 : 1);
    }

    public static MEEnergySpec multi(final Role role, final int tier, final int amperage) {
        return new MEEnergySpec(role, Family.MULTI, tier, amperage);
    }

    public static boolean isMultiAmperage(final int tier, final int amperage) {
        if (tier < MIN_TIER || tier > MAX_TIER) return false;
        if (amperage == 4 || amperage == 16 || amperage == 64) return true;
        int candidate = 256;
        for (int level = MIN_TIER; level <= tier; level++, candidate *= 4) {
            if (amperage == candidate) return true;
        }
        return false;
    }

    private static void checkTier(final int tier) {
        if (tier < MIN_TIER || tier > MAX_TIER) {
            throw new IllegalArgumentException("Unsupported ME energy tier: " + tier);
        }
    }

    public Role role() {
        return this.role;
    }

    public Family family() {
        return this.family;
    }

    public int tier() {
        return this.tier;
    }

    public String tierName() {
        return TIER_NAMES[this.tier - MIN_TIER];
    }

    public int amperage() {
        return this.amperage;
    }

    public long voltage() {
        return this.voltage;
    }

    public long power() {
        return this.power;
    }

    public long capacity() {
        return this.capacity;
    }

    public long nativeReserve() {
        return this.family == Family.STANDARD ? 512 : 128L * this.amperage;
    }

    /** Recipe metadata only; no native ItemStack is resolved in this phase. */
    public int nativeBaseAmperage() {
        if (this.family == Family.STANDARD) return this.amperage;
        final int maximum = this.role == Role.DYNAMO && this.tier >= 8 ? 256 : 64;
        return Math.min(this.amperage, maximum);
    }

    public boolean requiresLaserIngredient() {
        return this.amperage > nativeBaseAmperage();
    }

    public String key() {
        return this.key;
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) return true;
        if (!(other instanceof MEEnergySpec)) return false;
        final MEEnergySpec spec = (MEEnergySpec) other;
        return this.role == spec.role && this.family == spec.family
            && this.tier == spec.tier
            && this.amperage == spec.amperage;
    }

    @Override
    public int hashCode() {
        return ((this.role.ordinal() * 2 + this.family.ordinal()) * 31 + this.tier) * 31 + this.amperage;
    }
}
