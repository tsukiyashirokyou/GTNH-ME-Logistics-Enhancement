package com.kyou.gtnhmelogisticsenhancement.energy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.math.BigInteger;

import org.junit.Test;

public class MEEnergyMonitorWindowTest {

    @Test
    public void tickBoundaryIncludesAllSameTickCommitsAndPublishesOnlyCompletedWindows() {
        final MEEnergyMonitorWindow window = new MEEnergyMonitorWindow(0);
        final int cell = MEEnergyMonitorWindow.cell(5, 0);
        window.record(cell, 11, 10, 0);
        window.record(cell, 21, 20, 19);
        window.record(cell, 2, 1, 19);
        assertEquals(-1, window.completedEnd());
        window.record(cell, 5, 4, 20);
        assertEquals(20, window.completedEnd());
        assertEquals(BigInteger.valueOf(34), window.value(cell).gross);
        assertEquals(BigInteger.valueOf(31), window.value(cell).net);
        assertEquals(
            BigInteger.valueOf(3),
            window.value(cell)
                .loss());
        window.advance(39);
        assertEquals(BigInteger.valueOf(34), window.value(cell).gross);
        window.advance(40);
        assertEquals(BigInteger.valueOf(5), window.value(cell).gross);
        window.advance(60);
        assertEquals(BigInteger.ZERO, window.value(cell).gross);
    }

    @Test
    public void skippedWindowsAreZeroRatherThanReplayingOldTransferHistory() {
        final MEEnergyMonitorWindow window = new MEEnergyMonitorWindow(7);
        window.record(0, 20, 19, 7);
        window.advance(67);
        assertEquals(67, window.completedEnd());
        assertEquals(BigInteger.ZERO, window.value(0).gross);
        window.record(0, 11, 10, 68);
        window.advance(87);
        assertEquals(BigInteger.valueOf(11), window.value(0).gross);
        assertEquals(BigInteger.ZERO, new MEEnergyMonitorWindow(88).value(0).gross);
    }

    @Test
    public void colorsTiersAndAmountsAboveLongRemainIndependentAndExact() {
        final MEEnergyMonitorWindow window = new MEEnergyMonitorWindow(0);
        final int first = MEEnergyMonitorWindow.cell(5, 0), second = MEEnergyMonitorWindow.cell(13, 16);
        window.record(first, Long.MAX_VALUE, Long.MAX_VALUE - 1, 0);
        window.record(first, Long.MAX_VALUE, Long.MAX_VALUE - 1, 1);
        window.record(second, 11, 10, 2);
        window.advance(20);
        assertEquals(
            BigInteger.valueOf(Long.MAX_VALUE)
                .multiply(BigInteger.valueOf(2)),
            window.value(first).gross);
        assertEquals(
            BigInteger.valueOf(2),
            window.value(first)
                .loss());
        assertEquals(BigInteger.valueOf(11), window.value(second).gross);
        assertEquals(BigInteger.ZERO, window.value(MEEnergyMonitorWindow.cell(6, 0)).gross);
        assertThrows(IllegalArgumentException.class, () -> window.record(first, 0, 1, 20));
        assertThrows(IllegalArgumentException.class, () -> window.advance(19));
        assertThrows(IllegalArgumentException.class, () -> MEEnergyMonitorWindow.cell(4, 0));
    }
}
