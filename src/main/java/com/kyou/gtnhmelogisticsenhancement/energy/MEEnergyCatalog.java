package com.kyou.gtnhmelogisticsenhancement.energy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.kyou.gtnhmelogisticsenhancement.energy.MEEnergySpec.Role;

/** Explicit fixed save IDs. Registration audits the entire range; never allocates runtime free IDs. */
public final class MEEnergyCatalog {

    public static final int CANDIDATE_FIRST_ID = 32552;
    public static final int CANDIDATE_LAST_ID = 32713;
    private static final List<Entry> ENTRIES;
    private static final Map<String, Entry> BY_KEY;

    static {
        final Builder builder = new Builder();
        builder.standard(5, 32552);
        builder.standard(6, 32554);
        builder.standard(7, 32556);
        builder.standard(8, 32558);
        builder.standard(9, 32560);
        builder.standard(10, 32562);
        builder.standard(11, 32564);
        builder.standard(12, 32566);
        builder.standard(13, 32568);
        builder.multi(5, 4, 32570);
        builder.multi(5, 16, 32572);
        builder.multi(5, 64, 32574);
        builder.multi(5, 256, 32576);
        builder.multi(6, 4, 32578);
        builder.multi(6, 16, 32580);
        builder.multi(6, 64, 32582);
        builder.multi(6, 256, 32584);
        builder.multi(6, 1024, 32586);
        builder.multi(7, 4, 32588);
        builder.multi(7, 16, 32590);
        builder.multi(7, 64, 32592);
        builder.multi(7, 256, 32594);
        builder.multi(7, 1024, 32596);
        builder.multi(7, 4096, 32598);
        builder.multi(8, 4, 32600);
        builder.multi(8, 16, 32602);
        builder.multi(8, 64, 32604);
        builder.multi(8, 256, 32606);
        builder.multi(8, 1024, 32608);
        builder.multi(8, 4096, 32610);
        builder.multi(8, 16384, 32612);
        builder.multi(9, 4, 32614);
        builder.multi(9, 16, 32616);
        builder.multi(9, 64, 32618);
        builder.multi(9, 256, 32620);
        builder.multi(9, 1024, 32622);
        builder.multi(9, 4096, 32624);
        builder.multi(9, 16384, 32626);
        builder.multi(9, 65536, 32628);
        builder.multi(10, 4, 32630);
        builder.multi(10, 16, 32632);
        builder.multi(10, 64, 32634);
        builder.multi(10, 256, 32636);
        builder.multi(10, 1024, 32638);
        builder.multi(10, 4096, 32640);
        builder.multi(10, 16384, 32642);
        builder.multi(10, 65536, 32644);
        builder.multi(10, 262144, 32646);
        builder.multi(11, 4, 32648);
        builder.multi(11, 16, 32650);
        builder.multi(11, 64, 32652);
        builder.multi(11, 256, 32654);
        builder.multi(11, 1024, 32656);
        builder.multi(11, 4096, 32658);
        builder.multi(11, 16384, 32660);
        builder.multi(11, 65536, 32662);
        builder.multi(11, 262144, 32664);
        builder.multi(11, 1048576, 32666);
        builder.multi(12, 4, 32668);
        builder.multi(12, 16, 32670);
        builder.multi(12, 64, 32672);
        builder.multi(12, 256, 32674);
        builder.multi(12, 1024, 32676);
        builder.multi(12, 4096, 32678);
        builder.multi(12, 16384, 32680);
        builder.multi(12, 65536, 32682);
        builder.multi(12, 262144, 32684);
        builder.multi(12, 1048576, 32686);
        builder.multi(12, 4194304, 32688);
        builder.multi(13, 4, 32690);
        builder.multi(13, 16, 32692);
        builder.multi(13, 64, 32694);
        builder.multi(13, 256, 32696);
        builder.multi(13, 1024, 32698);
        builder.multi(13, 4096, 32700);
        builder.multi(13, 16384, 32702);
        builder.multi(13, 65536, 32704);
        builder.multi(13, 262144, 32706);
        builder.multi(13, 1048576, 32708);
        builder.multi(13, 4194304, 32710);
        builder.multi(13, 16777216, 32712);
        ENTRIES = Collections.unmodifiableList(builder.entries);
        BY_KEY = Collections.unmodifiableMap(builder.byKey);
    }

    private MEEnergyCatalog() {}

    public static List<Entry> entries() {
        return ENTRIES;
    }

    public static Entry find(final String key) {
        return BY_KEY.get(key);
    }

    public static final class Entry {

        private final MEEnergySpec spec;
        private final int candidateMteId;

        private Entry(final MEEnergySpec spec, final int candidateMteId) {
            this.spec = spec;
            this.candidateMteId = candidateMteId;
        }

        public MEEnergySpec spec() {
            return this.spec;
        }

        /** Fixed mapping; MEEnergyRegistration performs the full-range startup collision audit. */
        public int candidateMteId() {
            return this.candidateMteId;
        }
    }

    private static final class Builder {

        final List<Entry> entries = new ArrayList<>();
        final Map<String, Entry> byKey = new LinkedHashMap<>();
        final Set<Integer> ids = new HashSet<>();

        void standard(final int tier, final int energyId) {
            add(MEEnergySpec.standard(Role.ENERGY, tier), energyId);
            add(MEEnergySpec.standard(Role.DYNAMO, tier), energyId + 1);
        }

        void multi(final int tier, final int amperage, final int energyId) {
            add(MEEnergySpec.multi(Role.ENERGY, tier, amperage), energyId);
            add(MEEnergySpec.multi(Role.DYNAMO, tier, amperage), energyId + 1);
        }

        private void add(final MEEnergySpec spec, final int id) {
            if (id < CANDIDATE_FIRST_ID || id > CANDIDATE_LAST_ID
                || !this.ids.add(id)
                || this.byKey.containsKey(spec.key())) {
                throw new IllegalStateException("Duplicate or invalid ME energy candidate: " + spec.key());
            }
            final Entry entry = new Entry(spec, id);
            this.entries.add(entry);
            this.byKey.put(spec.key(), entry);
        }
    }
}
