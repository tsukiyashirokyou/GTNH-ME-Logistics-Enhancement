package com.kyou.gtnhmelogisticsenhancement.energy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import com.kyou.gtnhmelogisticsenhancement.energy.MEEnergySpec.Family;
import com.kyou.gtnhmelogisticsenhancement.energy.MEEnergySpec.Role;

public class MEEnergyCatalogTest {

    private static final long[] VOLTAGES = { 8192, 32768, 131072, 524288, 2097152, 8388608, 33554432, 134217728,
        536870912 };

    @Test
    public void exactIndependentSpecificationSetAndCandidateIds() {
        final Set<String> expected = new HashSet<>();
        final int[] allMultiAmps = { 4, 16, 64, 256, 1024, 4096, 16384, 65536, 262144, 1048576, 4194304, 16777216 };
        int id = 32552;
        for (int tier = 5; tier <= 13; tier++) {
            id = expectPair(expected, Family.STANDARD, tier, 0, id);
        }
        for (int tier = 5; tier <= 13; tier++) {
            for (int index = 0; index < tier - 1; index++) {
                id = expectPair(expected, Family.MULTI, tier, allMultiAmps[index], id);
            }
        }
        assertEquals(32714, id);
        assertEquals(162, expected.size());
        assertEquals(
            162,
            MEEnergyCatalog.entries()
                .size());
        final Set<Integer> ids = new HashSet<>();
        for (final MEEnergyCatalog.Entry entry : MEEnergyCatalog.entries()) {
            final MEEnergySpec spec = entry.spec();
            assertTrue(expected.remove(signature(spec)));
            assertTrue(ids.add(entry.candidateMteId()));
            assertSame(entry, MEEnergyCatalog.find(spec.key()));
            assertEquals(VOLTAGES[spec.tier() - 5], spec.voltage());
            assertEquals(spec.voltage() * spec.amperage(), spec.power());
            assertEquals(spec.power() * 24, spec.capacity());
            assertEquals(spec.family() == Family.STANDARD ? 512 : 128L * spec.amperage(), spec.nativeReserve());
        }
        assertTrue(expected.isEmpty());
        assertFalse(ids.contains(32550));
        assertFalse(ids.contains(32551));
        assertEquals(
            32713,
            ids.stream()
                .mapToInt(Integer::intValue)
                .max()
                .getAsInt());
    }

    private static int expectPair(final Set<String> expected, final Family family, final int tier, final int amp,
        final int energyId) {
        for (final Role role : Role.values()) {
            final int amperage = family == Family.STANDARD ? role == Role.ENERGY ? 2 : 1 : amp;
            final String wanted = role + ":" + family + ":" + tier + ":" + amperage;
            expected.add(wanted);
            final int id = energyId + (role == Role.ENERGY ? 0 : 1);
            assertEquals(
                wanted,
                signature(
                    MEEnergyCatalog.entries()
                        .get(id - 32552)
                        .spec()));
            assertEquals(
                id,
                MEEnergyCatalog.entries()
                    .get(id - 32552)
                    .candidateMteId());
        }
        return energyId + 2;
    }

    private static String signature(final MEEnergySpec spec) {
        return spec.role() + ":" + spec.family() + ":" + spec.tier() + ":" + spec.amperage();
    }

    @Test
    public void recipeMetadataRespectsDifferentNativeLimits() {
        int standard = 0;
        int multi = 0;
        int energy = 0;
        int twoIngredients = 0;
        for (final MEEnergyCatalog.Entry entry : MEEnergyCatalog.entries()) {
            final MEEnergySpec spec = entry.spec();
            if (spec.family() == Family.STANDARD) standard++;
            else multi++;
            if (spec.role() == Role.ENERGY) energy++;
            final int nativeLimit = spec.role() == Role.DYNAMO && spec.tier() >= 8 ? 256 : 64;
            assertEquals(
                spec.family() == Family.STANDARD ? spec.amperage() : Math.min(spec.amperage(), nativeLimit),
                spec.nativeBaseAmperage());
            if (!spec.requiresLaserIngredient()) twoIngredients++;
        }
        assertEquals(18, standard);
        assertEquals(144, multi);
        assertEquals(81, energy);
        assertEquals(78, twoIngredients);
        assertTrue(
            MEEnergySpec.multi(Role.DYNAMO, 5, 256)
                .requiresLaserIngredient());
        assertFalse(
            MEEnergySpec.multi(Role.DYNAMO, 8, 256)
                .requiresLaserIngredient());
        assertTrue(
            MEEnergySpec.multi(Role.ENERGY, 8, 256)
                .requiresLaserIngredient());
    }

    @Test
    public void immutableValuesRejectOutOfScopeLevelsAndAmperages() {
        for (final int tier : new int[] { -1, 0, 4, 14, Integer.MAX_VALUE }) {
            assertThrows(IllegalArgumentException.class, () -> MEEnergySpec.standard(Role.ENERGY, tier));
            assertFalse(MEEnergySpec.isMultiAmperage(tier, 4));
        }
        for (final int amp : new int[] { -1, 0, 1, 2, 8, 128, 512, 1024, 536870912 }) {
            assertThrows(IllegalArgumentException.class, () -> MEEnergySpec.multi(Role.ENERGY, 5, amp));
        }
        assertThrows(NullPointerException.class, () -> MEEnergySpec.standard(null, 5));
        assertThrows(
            UnsupportedOperationException.class,
            () -> MEEnergyCatalog.entries()
                .clear());
        assertNull(MEEnergyCatalog.find("missing"));
        final MEEnergySpec highest = MEEnergySpec.multi(Role.ENERGY, 13, 16777216);
        assertEquals(9007199254740992L, highest.power());
        assertEquals(216172782113783808L, highest.capacity());
        assertEquals(highest, MEEnergySpec.multi(Role.ENERGY, 13, 16777216));
        assertEquals(
            highest.hashCode(),
            MEEnergySpec.multi(Role.ENERGY, 13, 16777216)
                .hashCode());
        assertEquals("multi_energy_uxv_16777216a", highest.key());
        assertFalse(highest.equals(MEEnergySpec.multi(Role.DYNAMO, 13, 16777216)));
    }
}
