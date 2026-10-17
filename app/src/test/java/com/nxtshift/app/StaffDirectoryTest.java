package com.nxtshift.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.nxtshift.app.data.local.StaffMemberEntity;
import com.nxtshift.app.ocr.FullMonthRosterParser;

import org.junit.Test;

import java.util.List;

public class StaffDirectoryTest {

    @Test
    public void testCleanName_SpecialCharsAndSpacing() {
        assertEquals("Yamini", FullMonthRosterParser.cleanName("1. Yamini*"));
        assertEquals("Priya Sharma", FullMonthRosterParser.cleanName("Priya   Sharma"));
        assertEquals("Sunita", FullMonthRosterParser.cleanName("- Sunita (ICU)"));
        assertEquals("Munesh", FullMonthRosterParser.cleanName("#Munesh:"));
    }

    @Test
    public void testIsValidStaffName() {
        // Valid names
        assertTrue(FullMonthRosterParser.isValidStaffName("Yamini"));
        assertTrue(FullMonthRosterParser.isValidStaffName("Dr. Priya"));
        assertTrue(FullMonthRosterParser.isValidStaffName("Sunita"));

        // Administrative keywords & header text must be rejected
        assertFalse(FullMonthRosterParser.isValidStaffName("Month Oct"));
        assertFalse(FullMonthRosterParser.isValidStaffName("Month Sam Spm"));
        assertFalse(FullMonthRosterParser.isValidStaffName("DUTY"));
        assertFalse(FullMonthRosterParser.isValidStaffName("ROSTER"));
        assertFalse(FullMonthRosterParser.isValidStaffName("MORNING"));
        assertFalse(FullMonthRosterParser.isValidStaffName("EVENING"));
        assertFalse(FullMonthRosterParser.isValidStaffName("NIGHT"));
        assertFalse(FullMonthRosterParser.isValidStaffName("OFF"));
        assertFalse(FullMonthRosterParser.isValidStaffName("ICU"));
        assertFalse(FullMonthRosterParser.isValidStaffName("SUNDAY"));
        assertFalse(FullMonthRosterParser.isValidStaffName("TOTAL"));

        // Too short or numbers
        assertFalse(FullMonthRosterParser.isValidStaffName("A"));
        assertFalse(FullMonthRosterParser.isValidStaffName("12"));
        assertFalse(FullMonthRosterParser.isValidStaffName("08:00"));
    }

    @Test
    public void testNormalizeForComparison() {
        assertEquals("yamini", FullMonthRosterParser.normalizeForComparison("Yamini"));
        assertEquals("priyasharma", FullMonthRosterParser.normalizeForComparison("Priya-Sharma"));
        assertEquals("drkavita", FullMonthRosterParser.normalizeForComparison("Dr. Kavita"));
    }

    @Test
    public void testMatchesEmployee_ExactTokensOnly() {
        String normYamini = FullMonthRosterParser.normalizeForComparison("Yamini");

        // Exact match
        assertTrue(FullMonthRosterParser.matchesEmployee("Yamini", normYamini, "Yamini"));
        assertTrue(FullMonthRosterParser.matchesEmployee("Dr. Yamini", normYamini, "Yamini"));
        assertTrue(FullMonthRosterParser.matchesEmployee("Yamini, Priya", normYamini, "Yamini"));

        // Similar but different people must NOT match
        assertFalse("Yamini K is a distinct identity unless explicitly merged",
                FullMonthRosterParser.matchesEmployee("Yamini K", normYamini, "Yamini"));
        assertFalse("Sunita must not match Yamini", FullMonthRosterParser.matchesEmployee("Sunita", normYamini, "Yamini"));
        assertFalse("Anita must not match Yamini", FullMonthRosterParser.matchesEmployee("Anita", normYamini, "Yamini"));
    }

    @Test
    public void testStaffMemberEntity_UserIsolation() {
        long now = System.currentTimeMillis();
        StaffMemberEntity s1 = new StaffMemberEntity("user_A", "Yamini", "yamini", "N-101", "Yamini K", 15, now, now);
        StaffMemberEntity s2 = new StaffMemberEntity("user_B", "Yamini", "yamini", "N-202", "Yamini", 8, now, now);

        assertEquals("user_A", s1.getOwnerUid());
        assertEquals("user_B", s2.getOwnerUid());
        assertEquals("yamini", s1.getNormalizedName());
        assertEquals(15, s1.getDutyCount());
        assertEquals("Yamini K", s1.getAliases());
    }

    @Test
    public void testStaffDiscovery_KeepsDistinctPersonsSeparate() {
        com.nxtshift.app.ocr.StaffAllowlistManager.addConfiguredIdentity("Priya");
        com.nxtshift.app.ocr.StaffAllowlistManager.addConfiguredIdentity("Sunita");
        com.nxtshift.app.ocr.StaffAllowlistManager.addConfiguredIdentity("Yamini K");
        try {
            // Discovered occurrences of exact same person are merged, but distinct variants remain separate
            String roster = "01/10 | Yamini | Priya\n" +
                    "02/10 | Yamini | Sunita\n" +
                    "03/10 | Yamini K | Munesh\n";

            List<FullMonthRosterParser.DiscoveredStaffMember> list =
                    FullMonthRosterParser.discoverStaffDirectory(null, roster);

            assertNotNull(list);
            FullMonthRosterParser.DiscoveredStaffMember yaminiMember = null;
            FullMonthRosterParser.DiscoveredStaffMember yaminiKMember = null;
            for (FullMonthRosterParser.DiscoveredStaffMember m : list) {
                if (m.name.equals("Yamini")) {
                    yaminiMember = m;
                } else if (m.name.equals("Yamini K")) {
                    yaminiKMember = m;
                }
            }
            assertNotNull("Yamini must exist with exact count 2", yaminiMember);
            assertEquals(2, yaminiMember.dutyCount);

            assertNotNull("Yamini K must remain separate with count 1", yaminiKMember);
            assertEquals(1, yaminiKMember.dutyCount);
        } finally {
            com.nxtshift.app.ocr.StaffAllowlistManager.resetToDefaultAllowlist();
        }
    }
}
