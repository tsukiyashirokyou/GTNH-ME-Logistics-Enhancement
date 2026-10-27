package com.kyou.metransferdrive.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.UUID;

import org.junit.Test;

public class TransferTargetTest {

    private final Object sourceGrid = new Object();
    private final TransferTarget target = new TransferTarget(UUID.randomUUID(), 0, 16, 64, 32);
    private final Lookup lookup = new Lookup();

    @Test
    public void unloadedAndCrossDimensionTargetsNeverResolveTiles() {
        this.lookup.loaded = false;
        assertNull(this.target.resolve(0, this.sourceGrid, this.lookup));
        this.lookup.loaded = true;
        assertNull(this.target.resolve(1, this.sourceGrid, this.lookup));
        assertEquals(0, this.lookup.tileLookups);
    }

    @Test
    public void rejectsSameGridAndOfflineInterface() {
        this.lookup.grid = this.sourceGrid;
        assertNull(this.target.resolve(0, this.sourceGrid, this.lookup));
        this.lookup.grid = null;
        assertNull(this.target.resolve(0, this.sourceGrid, this.lookup));
    }

    @Test
    public void checksCurrentGridIdentityOnEveryResolution() {
        final Object otherGrid = new Object();
        this.lookup.grid = otherGrid;
        assertSame(otherGrid, this.target.resolve(0, this.sourceGrid, this.lookup));
        this.lookup.grid = this.sourceGrid;
        assertNull(this.target.resolve(0, this.sourceGrid, this.lookup));
    }

    @Test
    public void equalValuesDoNotReplaceRuntimeGridIdentity() {
        final String first = new String("grid");
        final String second = new String("grid");
        this.lookup.grid = second;
        assertSame(second, this.target.resolve(0, first, this.lookup));
    }

    private static final class Lookup implements TransferTarget.Lookup<Object> {

        boolean loaded = true;
        Object grid;
        int tileLookups;

        @Override
        public boolean isLoaded(final int x, final int y, final int z) {
            return this.loaded;
        }

        @Override
        public Object activeInterfaceGrid(final int x, final int y, final int z) {
            this.tileLookups++;
            return this.grid;
        }
    }
}
