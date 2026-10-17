package com.nxtshift.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.nxtshift.app.ocr.OcrContextCorrection;

import org.junit.Test;

public class OcrContextCorrectionTest {

    @Test
    public void testNonPersonTokenExclusion() {
        // Real-device bug cases: Month/timing text detected as staff names
        assertTrue("Month Oct must be detected as non-person", OcrContextCorrection.isNonPersonToken("Month Oct"));
        assertTrue("Month Sam Spm must be detected as non-person", OcrContextCorrection.isNonPersonToken("Month Sam Spm"));
        assertTrue("Duty Roster must be detected as non-person", OcrContextCorrection.isNonPersonToken("Duty Roster"));
        assertTrue("Morning must be detected as non-person", OcrContextCorrection.isNonPersonToken("Morning"));
        assertTrue("Shift timings must be detected as non-person", OcrContextCorrection.isNonPersonToken("Shift Timings"));
        assertTrue("ICU Ward must be detected as non-person", OcrContextCorrection.isNonPersonToken("ICU Ward"));
        assertTrue("Sunday must be detected as non-person", OcrContextCorrection.isNonPersonToken("Sunday"));
        assertTrue("Total must be detected as non-person", OcrContextCorrection.isNonPersonToken("Total"));
        assertTrue("Signature must be detected as non-person", OcrContextCorrection.isNonPersonToken("Signature"));

        // Genuine staff names must NOT be flagged as non-person
        assertFalse("Yamini is a genuine staff member", OcrContextCorrection.isNonPersonToken("Yamini"));
        assertFalse("Priya Sharma is a genuine staff member", OcrContextCorrection.isNonPersonToken("Priya Sharma"));
        assertFalse("Sunita is a genuine staff member", OcrContextCorrection.isNonPersonToken("Sunita"));
        assertFalse("Munesh is a genuine staff member", OcrContextCorrection.isNonPersonToken("Munesh"));

        // Names with letters B, S, Z must NEVER be corrupted
        assertFalse("Bhavna has letter B and must remain a genuine name", OcrContextCorrection.isNonPersonToken("Bhavna"));
        assertFalse("Zoya has letter Z and must remain a genuine name", OcrContextCorrection.isNonPersonToken("Zoya"));
        assertFalse("Shreya has letter S and must remain a genuine name", OcrContextCorrection.isNonPersonToken("Shreya"));
    }

    @Test
    public void testHeaderOrLegendDetection() {
        assertTrue(OcrContextCorrection.isHeaderOrLegendLine("CITY HOSPITAL - DUTY ROSTER OCTOBER 2026"));
        assertTrue(OcrContextCorrection.isHeaderOrLegendLine("ROSTER FOR NOVEMBER 2026 - ICU"));
        assertTrue(OcrContextCorrection.isHeaderOrLegendLine("Shift Timings: Morning 8:00 AM - 2:00 PM, Evening 2:00 PM - 8:00 PM"));
        assertFalse(OcrContextCorrection.isHeaderOrLegendLine("01/10/2026 | Yamini | Priya | Sunita"));
    }

    @Test
    public void testContextualTimeRepair() {
        // Digits misread as letters in time strings
        assertEquals("08:00 AM", OcrContextCorrection.repairTimeString("Sam"));
        assertEquals("08:00 AM", OcrContextCorrection.repairTimeString("Bam"));
        assertEquals("05:00 PM", OcrContextCorrection.repairTimeString("Spm"));
        assertEquals("02:00 PM", OcrContextCorrection.repairTimeString("Zpm"));
        assertEquals("08:00 PM", OcrContextCorrection.repairTimeString("Bpm"));

        // Clock formats with OCR confusion
        assertEquals("08:00 AM", OcrContextCorrection.repairTimeString("B:00 AM"));
        assertEquals("02:00 PM", OcrContextCorrection.repairTimeString("Z:00 PM"));
        assertEquals("08:00 PM", OcrContextCorrection.repairTimeString("ZO:00")); // 20:00 -> 08:00 PM
        assertEquals("08:00 AM", OcrContextCorrection.repairTimeString("OB:00")); // 08:00 -> 08:00 AM
    }

    @Test
    public void testContextualDateRepairAndValidation() {
        // Date with B -> 8
        assertEquals("2026-10-28", OcrContextCorrection.repairDateString("ZB/10/2026", 2026, 10)); // Z->2, B->8
        assertEquals("2026-10-08", OcrContextCorrection.repairDateString("0B/10/2026", 2026, 10)); // B->8
        assertEquals("2026-10-18", OcrContextCorrection.repairDateString("1B-10-2026", 2026, 10)); // B->8

        // Date with S -> 5
        assertEquals("2026-10-15", OcrContextCorrection.repairDateString("1S/10/2026", 2026, 10)); // S->5
        assertEquals("2026-10-25", OcrContextCorrection.repairDateString("ZS/10/2026", 2026, 10)); // Z->2, S->5

        // Date with Z -> 2 in year
        assertEquals("2026-10-01", OcrContextCorrection.repairDateString("01/10/ZOZ6", 2026, 10)); // ZOZ6 -> 2026

        // Calendar bounds validation: impossible dates must return null
        assertNull("32nd of October does not exist", OcrContextCorrection.repairDateString("32/10/2026", 2026, 10));
        assertNull("30th of February does not exist", OcrContextCorrection.repairDateString("30/02/2026", 2026, 2));
    }
}
