package com.kyou.gtnhmelogisticsenhancement.energy;

import static org.junit.Assert.*;

import java.math.BigInteger;
import java.util.Arrays;

import org.junit.Test;

public class MEEnergyMonitorRowTest {

    @Test
    public void wireRoundTripKeepsExactWideCountsAndActualLoss() {
        final int[] counts = { 6, 4, 7, 5, 2, 1 };
        final BigInteger[] amounts = new BigInteger[9];
        Arrays.fill(amounts, BigInteger.ONE.shiftLeft(90));
        final MEEnergyMonitorRow source = new MEEnergyMonitorRow(counts, amounts);
        final MEEnergyMonitorWindow window = new MEEnergyMonitorWindow(0);
        window.record(0, 20, 19, 0);
        window.advance(20);
        final MEEnergyMonitorRow decoded = MEEnergyMonitorRow.decode(source.encode(window.value(0)));
        counts[0] = 0;
        amounts[0] = BigInteger.ZERO;
        assertEquals(6, decoded.sources());
        assertEquals(4, decoded.onlineSources());
        assertEquals(7, decoded.targets());
        assertEquals(2, decoded.sleepingTargets());
        assertEquals(BigInteger.ONE.shiftLeft(90), decoded.amount(0));
        assertEquals(BigInteger.valueOf(20), decoded.amount(7));
        assertEquals(BigInteger.valueOf(19), decoded.amount(8));
    }

    @Test
    public void malformedAndOversizedPayloadsFailClosed() {
        final String zero = MEEnergyMonitorRow.EMPTY.encode(MEEnergyMonitorWindow.Value.ZERO);
        for (final String value : new String[] { null, "", zero + ";0", zero.replaceFirst("0", "-1"),
            zero.replaceFirst("0", "2147483648"), zero.replaceFirst("0;0", "0;1"),
            zero.substring(0, zero.length() - 1) + "1", new String(new char[513]).replace('\0', '0') })
            assertSame(MEEnergyMonitorRow.EMPTY, MEEnergyMonitorRow.decode(value));
        final String wide = BigInteger.ONE.shiftLeft(128)
            .toString();
        assertSame(MEEnergyMonitorRow.EMPTY, MEEnergyMonitorRow.decode("0;0;0;0;0;0;" + wide + ";0;0;0;0;0;0;0;0"));
    }
}
