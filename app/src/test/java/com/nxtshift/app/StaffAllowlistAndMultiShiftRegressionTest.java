package com.nxtshift.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import com.google.mlkit.vision.text.Text;
import com.nxtshift.app.domain.ShiftType;
import com.nxtshift.app.ocr.FullMonthRosterParser;
import com.nxtshift.app.ocr.RosterTextParser;
import com.nxtshift.app.ocr.StaffAllowlistManager;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.nxtshift.app.R;
import com.nxtshift.app.data.local.DutyEntity;
import com.nxtshift.app.domain.Duty;
import com.nxtshift.app.domain.DutyStatus;
import com.nxtshift.app.domain.SyncStatus;

/**
 * Automated regression tests for strict 13-member staff allowlist,
 * safe OCR variation normalization, compound name splitting,
 * multi-date preservation, and the 30-shift extraction bug fix.
 */
public class StaffAllowlistAndMultiShiftRegressionTest {

    @Before
    public void setUp() {
        StaffAllowlistManager.resetToDefaultAllowlist();
        StaffAllowlistManager.setYaminiEnabled(true);
    }

    @Test
    public void testCanonicalStaff_MuneshAndPradeep() {
        StaffAllowlistManager.StaffResolutionResult resMunesh =
                StaffAllowlistManager.resolveCandidate("Munesh");
        assertEquals(StaffAllowlistManager.MatchStatus.EXACT, resMunesh.status);
        assertEquals("Munesh", resMunesh.canonicalNames.get(0));

        StaffAllowlistManager.StaffResolutionResult resPradeep =
                StaffAllowlistManager.resolveCandidate("Pradeep");
        assertEquals(StaffAllowlistManager.MatchStatus.EXACT, resPradeep.status);
        assertEquals("Pradeep", resPradeep.canonicalNames.get(0));
    }

    @Test
    public void testYarminiNormalization_OnlyWhenYaminiConfigured() {
        // 1. When Yamini is enabled (default)
        StaffAllowlistManager.setYaminiEnabled(true);
        StaffAllowlistManager.StaffResolutionResult resEnabled =
                StaffAllowlistManager.resolveCandidate("Yarmini");
        assertEquals(StaffAllowlistManager.MatchStatus.NORMALIZED_OCR, resEnabled.status);
        assertEquals("Yamini", resEnabled.canonicalNames.get(0));

        // 2. When Yamini is disabled, flag inconsistency and do not match
        StaffAllowlistManager.setYaminiEnabled(false);
        StaffAllowlistManager.StaffResolutionResult resDisabled =
                StaffAllowlistManager.resolveCandidate("Yarmini");
        assertEquals(StaffAllowlistManager.MatchStatus.UNKNOWN_SKIPPED, resDisabled.status);
        assertTrue(resDisabled.canonicalNames.isEmpty());
    }

    @Test
    public void testCompoundNameSplitting_HansrajNaveen() {
        StaffAllowlistManager.StaffResolutionResult res =
                StaffAllowlistManager.resolveCandidate("Hansraj Naveen");
        assertEquals(StaffAllowlistManager.MatchStatus.COMPOUND_SPLIT, res.status);
        assertEquals(2, res.canonicalNames.size());
        assertEquals("Hansraj", res.canonicalNames.get(0));
        assertEquals("Naveen", res.canonicalNames.get(1));
    }

    @Test
    public void testDistinctAjayStaff_AjayJangidVsAjayJindoliya() {
        StaffAllowlistManager.StaffResolutionResult resJangid =
                StaffAllowlistManager.resolveCandidate("Ajay Jangid");
        assertEquals(StaffAllowlistManager.MatchStatus.EXACT, resJangid.status);
        assertEquals("Ajay Jangid", resJangid.canonicalNames.get(0));

        StaffAllowlistManager.StaffResolutionResult resJindoliya =
                StaffAllowlistManager.resolveCandidate("Ajay Jindoliya");
        assertEquals(StaffAllowlistManager.MatchStatus.EXACT, resJindoliya.status);
        assertEquals("Ajay Jindoliya", resJindoliya.canonicalNames.get(0));

        // Must never match each other
        String normJangid = FullMonthRosterParser.normalizeForComparison("Ajay Jangid");
        String normJindoliya = FullMonthRosterParser.normalizeForComparison("Ajay Jindoliya");
        assertFalse(normJangid.equals(normJindoliya));
        assertFalse(FullMonthRosterParser.matchesEmployee("Ajay Jangid", normJindoliya, "Ajay Jindoliya"));
        assertFalse(FullMonthRosterParser.matchesEmployee("Ajay Jindoliya", normJangid, "Ajay Jangid"));
    }

    @Test
    public void testAmbiguousAjay_FlaggedAndNotAutoAssigned() {
        StaffAllowlistManager.StaffResolutionResult res =
                StaffAllowlistManager.resolveCandidate("Ajay");
        assertEquals(StaffAllowlistManager.MatchStatus.AMBIGUOUS, res.status);
        assertFalse("Ambiguous Ajay must not be approved automatically", res.isApproved());
        assertTrue(res.canonicalNames.isEmpty());
    }

    @Test
    public void testNonStaffAndHeaders_SkippedFromDirectory() {
        StaffAllowlistManager.StaffResolutionResult resMonthOct =
                StaffAllowlistManager.resolveCandidate("Month Oct");
        assertEquals(StaffAllowlistManager.MatchStatus.UNKNOWN_SKIPPED, resMonthOct.status);

        StaffAllowlistManager.StaffResolutionResult resMonthLegend =
                StaffAllowlistManager.resolveCandidate("Month Sam Spm");
        assertEquals(StaffAllowlistManager.MatchStatus.UNKNOWN_SKIPPED, resMonthLegend.status);

        StaffAllowlistManager.StaffResolutionResult resUnknown =
                StaffAllowlistManager.resolveCandidate("Unknown Person");
        assertEquals(StaffAllowlistManager.MatchStatus.UNKNOWN_SKIPPED, resUnknown.status);
    }

    @Test
    public void testVishal_PreservesDutiesOnMultipleDates() {
        String roster = "DUTY ROSTER - OCTOBER 2026\n" +
                "02/10/2026 | MORNING: Vishal | EVENING: Pradeep\n" +
                "05/10/2026 | MORNING: Munesh | EVENING: Vishal\n";

        List<RosterTextParser.ExtractedShiftItem> duties =
                FullMonthRosterParser.extractDutiesForEmployee(null, roster, "Vishal", 2026, 10);

        assertEquals(2, duties.size());
        assertEquals("2026-10-02", duties.get(0).date);
        assertEquals(ShiftType.MORNING, duties.get(0).shiftType);
        assertEquals("2026-10-05", duties.get(1).date);
        assertEquals(ShiftType.EVENING, duties.get(1).shiftType);
    }

    @Test
    public void testNaveen_MultipleDatesPreserved() {
        String roster = "01/10/2026 | MORNING: Naveen\n" +
                "03/10/2026 | EVENING: Naveen\n" +
                "07/10/2026 | NIGHT: Naveen\n";

        List<RosterTextParser.ExtractedShiftItem> duties =
                FullMonthRosterParser.extractDutiesForEmployee(null, roster, "Naveen", 2026, 10);

        assertEquals(3, duties.size());
        assertEquals("2026-10-01", duties.get(0).date);
        assertEquals("2026-10-03", duties.get(1).date);
        assertEquals("2026-10-07", duties.get(2).date);
    }

    @Test
    public void testThirtyShiftsPreserved_YaminiWithOct31ColleagueShiv() {
        // Construct a full 30-day roster for Yamini
        StringBuilder sb = new StringBuilder();
        sb.append("OCTOBER 2026 DUTY ROSTER\n");
        sb.append("Date | Morning | Evening | Night\n");

        for (int day = 1; day <= 30; day++) {
            sb.append(String.format("%02d/10/2026 | Morning: Yamini | Evening: Pradeep | Night: Vishal\n", day));
        }
        // Day 31: Morning duty with colleague Shiv
        sb.append("31/10/2026 | Morning: Yamini, Shiv | Evening: Rohit | Night: Vikas\n");

        String fullRoster = sb.toString();

        List<RosterTextParser.ExtractedShiftItem> duties =
                FullMonthRosterParser.extractDutiesForEmployee(null, fullRoster, "Yamini", 2026, 10);

        // All 31 shifts must be extracted, not just 1
        assertEquals(31, duties.size());

        // Check first duty
        assertEquals("2026-10-01", duties.get(0).date);
        assertEquals(ShiftType.MORNING, duties.get(0).shiftType);

        // Check day 31 duty: Morning with colleague Shiv
        RosterTextParser.ExtractedShiftItem lastDuty = duties.get(30);
        assertEquals("2026-10-31", lastDuty.date);
        assertEquals(ShiftType.MORNING, lastDuty.shiftType);
        assertEquals("Shiv", lastDuty.colleagues);
        assertFalse(lastDuty.colleagues.contains("Rohit"));
        assertFalse(lastDuty.colleagues.contains("Vikas"));

        // Verify distinct dates: all 31 dates must be unique
        Set<String> uniqueDates = new HashSet<>();
        for (RosterTextParser.ExtractedShiftItem item : duties) {
            uniqueDates.add(item.date);
        }
        assertEquals(31, uniqueDates.size());
    }

    @Test
    public void testDuplicateCellDetection_DeduplicatedWithoutCollapsingDistinctDates() {
        // Same date and shift repeated twice (OCR duplicate block detection)
        String roster = "01/10/2026 | MORNING: Munesh\n" +
                "01/10/2026 | MORNING: Munesh\n" +
                "02/10/2026 | MORNING: Munesh\n";

        List<RosterTextParser.ExtractedShiftItem> duties =
                FullMonthRosterParser.extractDutiesForEmployee(null, roster, "Munesh", 2026, 10);

        // Must collapse the duplicate on 01/10, but keep 02/10
        assertEquals(2, duties.size());
        assertEquals("2026-10-01", duties.get(0).date);
        assertEquals("2026-10-02", duties.get(1).date);
    }

    @Test
    public void testOctober2_YaminiAssignedEveningShift_ColleaguesExcludeShivFromOffColumn() {
        String roster = "Date | 9 AM-5 PM | 10 AM-6 PM | 12 PM-8 PM | 8 AM-2 PM | 2 PM-8 PM | 8 PM-8 AM | Off | Leave\n" +
                "02/10/2026 | | | | Pradeep, Vishal | Ajay Jangid, Yamini | Govind, Rohit | Ajay Jindoliya, Shiv, Munesh | Ajay Jindoliya\n";

        List<RosterTextParser.ExtractedShiftItem> duties =
                FullMonthRosterParser.extractDutiesForEmployee(null, roster, "Yamini", 2026, 10);

        assertEquals(1, duties.size());
        RosterTextParser.ExtractedShiftItem duty = duties.get(0);

        assertEquals("2026-10-02", duty.date);
        assertEquals(ShiftType.EVENING, duty.shiftType);
        assertEquals("02:00 PM", duty.startTime);
        assertEquals("08:00 PM", duty.endTime);
        assertFalse(duty.isOvernight);

        // Colleagues: Ajay Jangid only!
        assertEquals("Ajay Jangid", duty.colleagues);
        assertFalse("Shiv in Off column must NOT be included as colleague", duty.colleagues.contains("Shiv"));
        assertFalse("Munesh in Off column must NOT be included as colleague", duty.colleagues.contains("Munesh"));
        assertFalse("Ajay Jindoliya in Off/Leave column must NOT be included as colleague", duty.colleagues.contains("Ajay Jindoliya"));
        assertFalse("Pradeep in Morning column must NOT be included as colleague", duty.colleagues.contains("Pradeep"));
        assertFalse("Govind in Night column must NOT be included as colleague", duty.colleagues.contains("Govind"));
    }

    @Test
    public void testOctober2_PradeepAndVishal_RetainMorningShift() {
        String roster = "Date | 9 AM-5 PM | 10 AM-6 PM | 12 PM-8 PM | 8 AM-2 PM | 2 PM-8 PM | 8 PM-8 AM | Off | Leave\n" +
                "02/10/2026 | | | | Pradeep, Vishal | Ajay Jangid, Yamini | Govind, Rohit | Ajay Jindoliya, Shiv, Munesh | Ajay Jindoliya\n";

        // Pradeep
        List<RosterTextParser.ExtractedShiftItem> dutiesPradeep =
                FullMonthRosterParser.extractDutiesForEmployee(null, roster, "Pradeep", 2026, 10);
        assertEquals(1, dutiesPradeep.size());
        assertEquals("2026-10-02", dutiesPradeep.get(0).date);
        assertEquals(ShiftType.MORNING, dutiesPradeep.get(0).shiftType);
        assertEquals("08:00 AM", dutiesPradeep.get(0).startTime);
        assertEquals("02:00 PM", dutiesPradeep.get(0).endTime);
        assertEquals("Vishal", dutiesPradeep.get(0).colleagues);
        assertFalse(dutiesPradeep.get(0).colleagues.contains("Yamini"));
        assertFalse(dutiesPradeep.get(0).colleagues.contains("Shiv"));

        // Vishal
        List<RosterTextParser.ExtractedShiftItem> dutiesVishal =
                FullMonthRosterParser.extractDutiesForEmployee(null, roster, "Vishal", 2026, 10);
        assertEquals(1, dutiesVishal.size());
        assertEquals("2026-10-02", dutiesVishal.get(0).date);
        assertEquals(ShiftType.MORNING, dutiesVishal.get(0).shiftType);
        assertEquals("Pradeep", dutiesVishal.get(0).colleagues);
    }

    @Test
    public void testOctober2_GovindAndRohit_RetainNightShift() {
        String roster = "Date | 9 AM-5 PM | 10 AM-6 PM | 12 PM-8 PM | 8 AM-2 PM | 2 PM-8 PM | 8 PM-8 AM | Off | Leave\n" +
                "02/10/2026 | | | | Pradeep, Vishal | Ajay Jangid, Yamini | Govind, Rohit | Ajay Jindoliya, Shiv, Munesh | Ajay Jindoliya\n";

        List<RosterTextParser.ExtractedShiftItem> dutiesGovind =
                FullMonthRosterParser.extractDutiesForEmployee(null, roster, "Govind", 2026, 10);
        assertEquals(1, dutiesGovind.size());
        assertEquals("2026-10-02", dutiesGovind.get(0).date);
        assertEquals(ShiftType.NIGHT, dutiesGovind.get(0).shiftType);
        assertEquals("08:00 PM", dutiesGovind.get(0).startTime);
        assertEquals("08:00 AM", dutiesGovind.get(0).endTime);
        assertTrue(dutiesGovind.get(0).isOvernight);
        assertEquals("Rohit", dutiesGovind.get(0).colleagues);
        assertFalse(dutiesGovind.get(0).colleagues.contains("Yamini"));
        assertFalse(dutiesGovind.get(0).colleagues.contains("Shiv"));
    }

    @Test
    public void testOctober2_OffAndLeave_DoNotBecomeDuties() {
        String roster = "Date | 9 AM-5 PM | 10 AM-6 PM | 12 PM-8 PM | 8 AM-2 PM | 2 PM-8 PM | 8 PM-8 AM | Off | Leave\n" +
                "02/10/2026 | | | | Pradeep, Vishal | Ajay Jangid, Yamini | Govind, Rohit | Ajay Jindoliya, Shiv, Munesh | Ajay Jindoliya\n";

        // Shiv is in Off column -> no duty shifts
        List<RosterTextParser.ExtractedShiftItem> dutiesShiv =
                FullMonthRosterParser.extractDutiesForEmployee(null, roster, "Shiv", 2026, 10);
        assertTrue("Off entries must not become duty shifts", dutiesShiv.isEmpty());

        // Ajay Jindoliya is in Off and Leave columns -> no duty shifts
        List<RosterTextParser.ExtractedShiftItem> dutiesJindoliya =
                FullMonthRosterParser.extractDutiesForEmployee(null, roster, "Ajay Jindoliya", 2026, 10);
        assertTrue("Off and Leave entries must not become duty shifts", dutiesJindoliya.isEmpty());
    }

    @Test
    public void testValidTimingColumns_9to5_10to6_12to8() {
        String roster = "Date | 9 AM-5 PM | 10 AM-6 PM | 12 PM-8 PM | 8 AM-2 PM | 2 PM-8 PM | 8 PM-8 AM | Off | Leave\n" +
                "05/10/2026 | Munesh | Pradeep | Vishal | | | | | \n";

        // Munesh: 9 AM-5 PM
        List<RosterTextParser.ExtractedShiftItem> dutiesMunesh =
                FullMonthRosterParser.extractDutiesForEmployee(null, roster, "Munesh", 2026, 10);
        assertEquals(1, dutiesMunesh.size());
        assertEquals("2026-10-05", dutiesMunesh.get(0).date);
        assertEquals(ShiftType.GENERAL, dutiesMunesh.get(0).shiftType);
        assertEquals("09:00 AM", dutiesMunesh.get(0).startTime);
        assertEquals("05:00 PM", dutiesMunesh.get(0).endTime);

        // Pradeep: 10 AM-6 PM
        List<RosterTextParser.ExtractedShiftItem> dutiesPradeep =
                FullMonthRosterParser.extractDutiesForEmployee(null, roster, "Pradeep", 2026, 10);
        assertEquals(1, dutiesPradeep.size());
        assertEquals("2026-10-05", dutiesPradeep.get(0).date);
        assertEquals(ShiftType.GENERAL, dutiesPradeep.get(0).shiftType);
        assertEquals("10:00 AM", dutiesPradeep.get(0).startTime);
        assertEquals("06:00 PM", dutiesPradeep.get(0).endTime);

        // Vishal: 12 PM-8 PM
        List<RosterTextParser.ExtractedShiftItem> dutiesVishal =
                FullMonthRosterParser.extractDutiesForEmployee(null, roster, "Vishal", 2026, 10);
        assertEquals(1, dutiesVishal.size());
        assertEquals("2026-10-05", dutiesVishal.get(0).date);
        assertEquals(ShiftType.EVENING, dutiesVishal.get(0).shiftType);
        assertEquals("12:00 PM", dutiesVishal.get(0).startTime);
        assertEquals("08:00 PM", dutiesVishal.get(0).endTime);
    }

    @Test
    public void testUnrecognizedShift_NotDefaultedToMorning_FlaggedForReview() {
        // Line with no shift timing information
        String roster = "02/10/2026 Yamini\n";

        List<RosterTextParser.ExtractedShiftItem> duties =
                FullMonthRosterParser.extractDutiesForEmployee(null, roster, "Yamini", 2026, 10);

        assertEquals(1, duties.size());
        RosterTextParser.ExtractedShiftItem duty = duties.get(0);
        assertTrue("Unrecognized shift must be flagged as ambiguous for review", duty.isAmbiguous);
        assertTrue("Notes must indicate unrecognized shift timing", duty.notes.contains("Unrecognized"));
    }
    private Text.Element mockElement(String text, int left, int top, int right, int bottom) {
        Text.Element element = Mockito.mock(Text.Element.class);
        Rect r = FullMonthRosterParser.createRect(left, top, right, bottom);
        Mockito.when(element.getText()).thenReturn(text);
        Mockito.when(element.getBoundingBox()).thenReturn(r);
        return element;
    }

    private Text.Line mockLine(String text, int left, int top, int right, int bottom, Text.Element... elements) {
        Text.Line line = Mockito.mock(Text.Line.class);
        Rect r = FullMonthRosterParser.createRect(left, top, right, bottom);
        Mockito.when(line.getText()).thenReturn(text);
        Mockito.when(line.getBoundingBox()).thenReturn(r);
        List<Text.Element> elList = new ArrayList<>(Arrays.asList(elements));
        Mockito.when(line.getElements()).thenReturn(elList);
        return line;
    }

    private Text mockVisionText(Text.Line... lines) {
        Text text = Mockito.mock(Text.class);
        Text.TextBlock block = Mockito.mock(Text.TextBlock.class);
        List<Text.Line> lineList = new ArrayList<>(Arrays.asList(lines));
        Mockito.when(block.getLines()).thenReturn(lineList);
        Mockito.when(text.getTextBlocks()).thenReturn(Collections.singletonList(block));
        StringBuilder sb = new StringBuilder();
        for (Text.Line l : lines) {
            sb.append(l.getText()).append("\n");
        }
        Mockito.when(text.getText()).thenReturn(sb.toString());
        return text;
    }

    @Test
    public void testSpatialOctober2_ActualBoundingBoxes_YaminiAssigned2pmTo8pm_ColleagueAjayJangid() {
        // Headers with actual bounding boxes from the 1280x720 image
        Text.Line lHeaders = mockLine("Month 9am - 5pm 10am - 6pm 12pm-8pm 8am - 2pm 2pm - 8pm 8pm - 8am Off Leave", 40, 45, 1120, 55,
                mockElement("Month", 45, 45, 90, 55),
                mockElement("9am", 95, 45, 125, 55),
                mockElement("-", 127, 45, 135, 55),
                mockElement("5pm", 137, 45, 165, 55),
                mockElement("10am", 335, 45, 365, 55),
                mockElement("-", 367, 45, 375, 55),
                mockElement("6pm", 377, 45, 395, 55),
                mockElement("12pm-8pm", 398, 45, 455, 55),
                mockElement("8am", 458, 45, 485, 55),
                mockElement("-", 487, 45, 495, 55),
                mockElement("2pm", 497, 45, 520, 55),
                mockElement("2pm", 580, 45, 608, 55),
                mockElement("-", 610, 45, 618, 55),
                mockElement("8pm", 620, 45, 645, 55),
                mockElement("8pm", 735, 45, 762, 55),
                mockElement("-", 764, 45, 772, 55),
                mockElement("8am", 774, 45, 800, 55),
                mockElement("Off", 842, 45, 868, 55),
                mockElement("Leave", 982, 45, 1025, 55)
        );

        // Row 01-Oct (y=62..72)
        Text.Line lOct01 = mockLine("01-Oct Munesh Pradeep Vishal Shiv Ajay Jangid Yamini Govind Rohit", 45, 62, 1120, 72,
                mockElement("01-Oct", 45, 62, 90, 72),
                mockElement("Munesh", 458, 62, 498, 72),
                mockElement("Pradeep", 502, 62, 545, 72),
                mockElement("Vishal", 550, 62, 578, 72),
                mockElement("Shiv", 584, 62, 610, 72),
                mockElement("Ajay Jangid", 615, 62, 680, 72),
                mockElement("Yamini", 685, 62, 730, 72),
                mockElement("Govind", 738, 62, 778, 72),
                mockElement("Rohit", 782, 62, 822, 72),
                mockElement("Ajay Jindoliya", 985, 62, 1060, 72)
        );

        // Row 02-Oct (y=80..90)
        Text.Line lOct02 = mockLine("02-Oct Kuldeep Pradeep Vishal Ajay Jangid Yamini Govind Rohit Ajay Jindoliya Shiv Munesh", 45, 80, 1120, 90,
                mockElement("02-Oct", 45, 80, 90, 90),
                mockElement("Kuldeep", 398, 80, 448, 90),
                mockElement("Pradeep", 458, 80, 510, 90),
                mockElement("Vishal", 518, 80, 560, 90),
                mockElement("Ajay", 584, 80, 608, 90),
                mockElement("Jangid", 610, 80, 642, 90),
                mockElement("Yamini", 650, 80, 705, 90),
                mockElement("Govind", 738, 80, 778, 90),
                mockElement("Rohit", 782, 80, 822, 90),
                mockElement("Ajay", 842, 80, 870, 90),
                mockElement("Jindoliya", 872, 80, 915, 90),
                mockElement("Shiv", 920, 80, 948, 90),
                mockElement("Munesh", 950, 80, 985, 90)
        );

        // Row 03-Oct (y=98..108): Yamini is in Off column!
        Text.Line lOct03 = mockLine("03-Oct Ram Vikas Naveen Narayan Prashant Munesh Pradeep Vishal Shiv Ajay Jangid Kuldeep Ajay Jindoliya Rohit Govind Yamini", 45, 98, 1120, 108,
                mockElement("03-Oct", 45, 98, 90, 108),
                mockElement("Govind", 842, 98, 885, 108),
                mockElement("Yamini", 890, 98, 935, 108) // in Off column!
        );

        Text visionText = mockVisionText(lHeaders, lOct01, lOct02, lOct03);

        // 1. Test Yamini extraction on Oct 2: Assigned 2pm-8pm, colleague strictly Ajay Jangid
        List<RosterTextParser.ExtractedShiftItem> yaminiDuties =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Yamini", 2026, 10);

        // Oct 3 was in Off column, so only Oct 1 and Oct 2 should produce duties
        assertEquals("Yamini should have 2 working duties (Oct 1 & Oct 2, Oct 3 is Off)", 2, yaminiDuties.size());

        RosterTextParser.ExtractedShiftItem dutyOct02 = null;
        for (RosterTextParser.ExtractedShiftItem d : yaminiDuties) {
            if ("2026-10-02".equals(d.date)) dutyOct02 = d;
        }
        assertNotNull("Must extract duty for 2026-10-02", dutyOct02);
        assertEquals(ShiftType.EVENING, dutyOct02.shiftType);
        assertEquals("02:00 PM", dutyOct02.startTime);
        assertEquals("08:00 PM", dutyOct02.endTime);
        assertFalse(dutyOct02.isOvernight);

        // Coworker isolation: Colleague must be Ajay Jangid, strictly excluding Govind, Rohit, and Shiv
        assertTrue("Colleagues must include Ajay Jangid", dutyOct02.colleagues.contains("Ajay Jangid"));
        assertFalse("Govind from Night column must NOT be Yamini's colleague", dutyOct02.colleagues.contains("Govind"));
        assertFalse("Rohit from Night column must NOT be Yamini's colleague", dutyOct02.colleagues.contains("Rohit"));
        assertFalse("Shiv from Off column must NOT be Yamini's colleague", dutyOct02.colleagues.contains("Shiv"));
        assertFalse("Ajay Jindoliya must NOT be Yamini's colleague", dutyOct02.colleagues.contains("Ajay Jindoliya"));

        // 2. Test Govind & Rohit extraction on Oct 2: 8pm-8am (Night)
        List<RosterTextParser.ExtractedShiftItem> govindDuties =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Govind", 2026, 10);
        RosterTextParser.ExtractedShiftItem govindOct02 = null;
        for (RosterTextParser.ExtractedShiftItem d : govindDuties) {
            if ("2026-10-02".equals(d.date)) govindOct02 = d;
        }
        assertNotNull(govindOct02);
        assertEquals(ShiftType.NIGHT, govindOct02.shiftType);
        assertEquals("08:00 PM", govindOct02.startTime);
        assertEquals("08:00 AM", govindOct02.endTime);
        assertTrue(govindOct02.isOvernight);
        assertEquals("Rohit", govindOct02.colleagues);

        // 3. Test Shiv on Oct 2: In Off column -> 0 duties
        List<RosterTextParser.ExtractedShiftItem> shivDuties =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Shiv", 2026, 10);
        for (RosterTextParser.ExtractedShiftItem d : shivDuties) {
            assertFalse("Shiv in Off column on Oct 2 must NOT produce a duty", "2026-10-02".equals(d.date));
        }

        // 4. Test Pradeep & Vishal on Oct 2: 8am-2pm (Morning)
        List<RosterTextParser.ExtractedShiftItem> pradeepDuties =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Pradeep", 2026, 10);
        RosterTextParser.ExtractedShiftItem pradeepOct02 = null;
        for (RosterTextParser.ExtractedShiftItem d : pradeepDuties) {
            if ("2026-10-02".equals(d.date)) pradeepOct02 = d;
        }
        assertNotNull(pradeepOct02);
        assertEquals(ShiftType.MORNING, pradeepOct02.shiftType);
        assertEquals("08:00 AM", pradeepOct02.startTime);
        assertEquals("02:00 PM", pradeepOct02.endTime);
        assertEquals("Vishal", pradeepOct02.colleagues);
    }

    @Test
    public void testSpatial_TiltedImage_RowEffectiveYCompensated() {
        // Table has a ~20px slant from left to right (dx=1000, dy=20)
        Text.Line lHeaders = mockLine("Month 9am - 5pm 8am - 2pm 2pm - 8pm 8pm - 8am Off", 40, 45, 1120, 70,
                mockElement("Month", 45, 45, 90, 55),
                mockElement("9am-5pm", 100, 47, 180, 57),
                mockElement("8am-2pm", 460, 53, 530, 63),
                mockElement("2pm-8pm", 580, 56, 650, 66),
                mockElement("8pm-8am", 735, 60, 805, 70),
                mockElement("Off", 845, 63, 875, 73)
        );

        // Date anchor at x=45, y=100
        // Text at x=600 is at y=110 due to tilt, but should map to 10-Oct anchor (row height = 25)
        Text.Line lOct10 = mockLine("10-Oct Yamini Govind", 45, 100, 800, 125,
                mockElement("10-Oct", 45, 100, 90, 115),
                mockElement("Yamini", 600, 110, 645, 125) // tilted down by 10px
        );

        // Date anchor 11-Oct at x=45, y=125
        Text.Line lOct11 = mockLine("11-Oct Rohit", 45, 125, 800, 150,
                mockElement("11-Oct", 45, 125, 90, 140),
                mockElement("Rohit", 735, 137, 780, 150)
        );

        Text visionText = mockVisionText(lHeaders, lOct10, lOct11);

        List<RosterTextParser.ExtractedShiftItem> duties =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Yamini", 2026, 10);

        assertEquals(1, duties.size());
        assertEquals("2026-10-10", duties.get(0).date);
        assertEquals(ShiftType.EVENING, duties.get(0).shiftType);
    }

    @Test
    public void testSpatial_GroupedHeaderLines_ParsedViaElements() {
        // ML Kit produces a SINGLE Text.Line for all headers
        Text.Line lSingleHeaderLine = mockLine("Month 9am - 5pm 10am - 6pm 12pm-8pm 8am - 2pm 2pm - 8pm 8pm - 8am Off Leave", 40, 45, 1120, 55,
                mockElement("Month", 45, 45, 90, 55),
                mockElement("9am-5pm", 95, 45, 165, 55),
                mockElement("10am-6pm", 335, 45, 395, 55),
                mockElement("12pm-8pm", 398, 45, 455, 55),
                mockElement("8am-2pm", 458, 45, 520, 55),
                mockElement("2pm-8pm", 580, 45, 645, 55),
                mockElement("8pm-8am", 735, 45, 800, 55),
                mockElement("Off", 842, 45, 868, 55),
                mockElement("Leave", 982, 45, 1025, 55)
        );

        Text.Line lDate = mockLine("05-Oct Yamini", 45, 80, 700, 90,
                mockElement("05-Oct", 45, 80, 90, 90),
                mockElement("Yamini", 600, 80, 645, 90) // in 2pm-8pm
        );

        Text visionText = mockVisionText(lSingleHeaderLine, lDate);

        List<RosterTextParser.ExtractedShiftItem> duties =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Yamini", 2026, 10);

        assertEquals(1, duties.size());
        assertEquals("2026-10-05", duties.get(0).date);
        assertEquals(ShiftType.EVENING, duties.get(0).shiftType);
        assertEquals("02:00 PM", duties.get(0).startTime);
        assertEquals("08:00 PM", duties.get(0).endTime);
    }

    @Test
    public void testSpatial_MissingHeaders_InterpolatedWithUnequalWidths() {
        // Headers with 10am-6pm and 12pm-8pm missing (only 7 headers detected)
        Text.Line lHeaders = mockLine("Month 9am - 5pm 8am - 2pm 2pm - 8pm 8pm - 8am Off Leave", 40, 45, 1120, 55,
                mockElement("Month", 45, 45, 90, 55),
                mockElement("9am-5pm", 95, 45, 165, 55),
                mockElement("8am-2pm", 458, 45, 520, 55),
                mockElement("2pm-8pm", 580, 45, 645, 55),
                mockElement("8pm-8am", 735, 45, 800, 55),
                mockElement("Off", 842, 45, 868, 55),
                mockElement("Leave", 982, 45, 1025, 55)
        );

        Text.Line lRow = mockLine("15-Oct Pradeep Yamini", 45, 80, 700, 90,
                mockElement("15-Oct", 45, 80, 90, 90),
                mockElement("Pradeep", 480, 80, 530, 90), // 8am-2pm
                mockElement("Yamini", 600, 80, 645, 90)   // 2pm-8pm
        );

        Text visionText = mockVisionText(lHeaders, lRow);

        List<RosterTextParser.ExtractedShiftItem> duties =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Yamini", 2026, 10);

        assertEquals(1, duties.size());
        assertEquals("2026-10-15", duties.get(0).date);
        assertEquals(ShiftType.EVENING, duties.get(0).shiftType);
    }

    @Test
    public void testSpatial_MultipleDutiesOnSameDate_BothPreserved() {
        Text.Line lHeaders = mockLine("Month 9am - 5pm 8am - 2pm 2pm - 8pm 8pm - 8am Off Leave", 40, 45, 1120, 55,
                mockElement("Month", 45, 45, 90, 55),
                mockElement("9am-5pm", 95, 45, 165, 55),
                mockElement("8am-2pm", 458, 45, 520, 55),
                mockElement("2pm-8pm", 580, 45, 645, 55),
                mockElement("8pm-8am", 735, 45, 800, 55),
                mockElement("Off", 842, 45, 868, 55),
                mockElement("Leave", 982, 45, 1025, 55)
        );

        // Employee Yamini in 8am-2pm AND in 2pm-8pm on the same date (e.g. double shift)
        Text.Line lDoubleDuty = mockLine("20-Oct Yamini Yamini", 45, 80, 700, 90,
                mockElement("20-Oct", 45, 80, 90, 90),
                mockElement("Yamini", 470, 80, 515, 90), // 8am-2pm (Morning)
                mockElement("Yamini", 600, 80, 645, 90)  // 2pm-8pm (Evening)
        );

        Text visionText = mockVisionText(lHeaders, lDoubleDuty);

        List<RosterTextParser.ExtractedShiftItem> duties =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Yamini", 2026, 10);

        assertEquals("Both distinct duties on the same date must be preserved", 2, duties.size());
        Set<ShiftType> types = new HashSet<>();
        for (RosterTextParser.ExtractedShiftItem d : duties) {
            types.add(d.shiftType);
            assertEquals("2026-10-20", d.date);
        }
        assertTrue(types.contains(ShiftType.MORNING));
        assertTrue(types.contains(ShiftType.EVENING));
    }

    @Test
    public void testSpatial_FallbackNonUniformProportions_WhenNoHeadersDetected() {
        // No headers in header region (blurred or cropped photo)
        Text.Line lDate = mockLine("02-Oct Ajay Jangid Yamini Govind Rohit", 40, 80, 1120, 90,
                mockElement("02-Oct", 45, 80, 90, 90),
                mockElement("Ajay Jangid", 615, 80, 680, 90), // cx=647 -> Col 5 (2pm-8pm)
                mockElement("Yamini", 685, 80, 730, 90),      // cx=707 -> Col 5 (2pm-8pm)
                mockElement("Govind", 770, 80, 810, 90),      // cx=790 -> Col 6 (8pm-8am)
                mockElement("Rohit", 815, 80, 855, 90)        // cx=835 -> Col 6 (8pm-8am)
        );

        Text visionText = mockVisionText(lDate);

        List<RosterTextParser.ExtractedShiftItem> duties =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Yamini", 2026, 10);

        assertEquals(1, duties.size());
        assertEquals("2026-10-02", duties.get(0).date);
        assertEquals(ShiftType.EVENING, duties.get(0).shiftType);
        assertEquals("02:00 PM", duties.get(0).startTime);
        assertEquals("08:00 PM", duties.get(0).endTime);
        assertTrue(duties.get(0).colleagues.contains("Ajay Jangid"));
        assertFalse(duties.get(0).colleagues.contains("Govind"));
        assertFalse(duties.get(0).colleagues.contains("Rohit"));
    }

    private Text.Line mockStandardHeaders() {
        return mockLine("Month 9am - 5pm 10am - 6pm 12pm-8pm 8am - 2pm 2pm - 8pm 8pm - 8am Off Leave", 40, 45, 1120, 55,
                mockElement("Month", 45, 45, 90, 55),
                mockElement("9am-5pm", 95, 45, 165, 55),
                mockElement("10am-6pm", 335, 45, 395, 55),
                mockElement("12pm-8pm", 398, 45, 455, 55),
                mockElement("8am-2pm", 458, 45, 520, 55),
                mockElement("2pm-8pm", 580, 45, 645, 55),
                mockElement("8pm-8am", 735, 45, 800, 55),
                mockElement("Off", 842, 45, 868, 55),
                mockElement("Leave", 982, 45, 1025, 55)
        );
    }

    @Test
    public void testColleagueExtraction_1ApprovedStaff_SelfOnly_EmptyColleagues() {
        Text.Line lHeaders = mockStandardHeaders();
        Text.Line lDate = mockLine("04-Oct Yamini", 40, 80, 1120, 90,
                mockElement("04-Oct", 45, 80, 90, 90),
                mockElement("Yamini", 600, 80, 645, 90) // Col 5 (2pm-8pm)
        );
        Text visionText = mockVisionText(lHeaders, lDate);

        List<RosterTextParser.ExtractedShiftItem> duties =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Yamini", 2026, 10);

        assertEquals(1, duties.size());
        assertEquals("2026-10-04", duties.get(0).date);
        assertEquals(ShiftType.EVENING, duties.get(0).shiftType);
        assertEquals("Self must be excluded; colleagues list must be empty", "", duties.get(0).colleagues);
    }

    @Test
    public void testColleagueExtraction_2ApprovedStaff_ExcludesSelf_IncludesOther() {
        Text.Line lHeaders = mockStandardHeaders();
        Text.Line lDate = mockLine("04-Oct Yamini Ajay Jangid", 40, 80, 1120, 90,
                mockElement("04-Oct", 45, 80, 90, 90),
                mockElement("Yamini", 590, 80, 635, 90),       // Col 5 (2pm-8pm)
                mockElement("Ajay Jangid", 640, 80, 700, 90)  // Col 5 (2pm-8pm)
        );
        Text visionText = mockVisionText(lHeaders, lDate);

        // For Yamini: colleague must be Ajay Jangid
        List<RosterTextParser.ExtractedShiftItem> dutiesYamini =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Yamini", 2026, 10);
        assertEquals(1, dutiesYamini.size());
        assertEquals("Ajay Jangid", dutiesYamini.get(0).colleagues);

        // For Ajay Jangid: colleague must be Yamini
        List<RosterTextParser.ExtractedShiftItem> dutiesAjay =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Ajay Jangid", 2026, 10);
        assertEquals(1, dutiesAjay.size());
        assertEquals("Yamini", dutiesAjay.get(0).colleagues);
    }

    @Test
    public void testColleagueExtraction_3ApprovedStaff_ExcludesSelf_IncludesOtherTwo() {
        Text.Line lHeaders = mockStandardHeaders();
        Text.Line lDate = mockLine("04-Oct Yamini Ajay Jangid Rohit", 40, 80, 1120, 90,
                mockElement("04-Oct", 45, 80, 90, 90),
                mockElement("Yamini", 560, 80, 595, 90),       // Col 5 (2pm-8pm)
                mockElement("Ajay Jangid", 600, 80, 650, 90),  // Col 5 (2pm-8pm)
                mockElement("Rohit", 655, 80, 685, 90)         // Col 5 (2pm-8pm)
        );
        Text visionText = mockVisionText(lHeaders, lDate);

        // Yamini: colleagues = Ajay Jangid, Rohit
        List<RosterTextParser.ExtractedShiftItem> dutiesYamini =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Yamini", 2026, 10);
        assertEquals(1, dutiesYamini.size());
        assertEquals("Ajay Jangid, Rohit", dutiesYamini.get(0).colleagues);

        // Ajay Jangid: colleagues = Yamini, Rohit
        List<RosterTextParser.ExtractedShiftItem> dutiesAjay =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Ajay Jangid", 2026, 10);
        assertEquals(1, dutiesAjay.size());
        assertEquals("Yamini, Rohit", dutiesAjay.get(0).colleagues);

        // Rohit: colleagues = Yamini, Ajay Jangid
        List<RosterTextParser.ExtractedShiftItem> dutiesRohit =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Rohit", 2026, 10);
        assertEquals(1, dutiesRohit.size());
        assertEquals("Yamini, Ajay Jangid", dutiesRohit.get(0).colleagues);
    }

    @Test
    public void testColleagueExtraction_4ApprovedStaff_ExcludesSelf_IncludesOtherThree() {
        Text.Line lHeaders = mockStandardHeaders();
        Text.Line lDate = mockLine("04-Oct Yamini Pradeep Vishal Munesh", 40, 80, 1120, 90,
                mockElement("04-Oct", 45, 80, 90, 90),
                mockElement("Yamini", 560, 80, 585, 90),   // Col 5 (2pm-8pm)
                mockElement("Pradeep", 590, 80, 620, 90),  // Col 5 (2pm-8pm)
                mockElement("Vishal", 625, 80, 655, 90),   // Col 5 (2pm-8pm)
                mockElement("Munesh", 660, 80, 685, 90)    // Col 5 (2pm-8pm)
        );
        Text visionText = mockVisionText(lHeaders, lDate);

        // Yamini: colleagues = Pradeep, Vishal, Munesh
        List<RosterTextParser.ExtractedShiftItem> dutiesYamini =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Yamini", 2026, 10);
        assertEquals(1, dutiesYamini.size());
        assertEquals("Pradeep, Vishal, Munesh", dutiesYamini.get(0).colleagues);

        // Pradeep: colleagues = Yamini, Vishal, Munesh
        List<RosterTextParser.ExtractedShiftItem> dutiesPradeep =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Pradeep", 2026, 10);
        assertEquals(1, dutiesPradeep.size());
        assertEquals("Yamini, Vishal, Munesh", dutiesPradeep.get(0).colleagues);

        // Vishal: colleagues = Yamini, Pradeep, Munesh
        List<RosterTextParser.ExtractedShiftItem> dutiesVishal =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Vishal", 2026, 10);
        assertEquals(1, dutiesVishal.size());
        assertEquals("Yamini, Pradeep, Munesh", dutiesVishal.get(0).colleagues);

        // Munesh: colleagues = Yamini, Pradeep, Vishal
        List<RosterTextParser.ExtractedShiftItem> dutiesMunesh =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Munesh", 2026, 10);
        assertEquals(1, dutiesMunesh.size());
        assertEquals("Yamini, Pradeep, Vishal", dutiesMunesh.get(0).colleagues);
    }

    @Test
    public void testColleagueExtraction_CompoundName_HansrajNaveen() {
        Text.Line lHeaders = mockStandardHeaders();
        Text.Line lDate = mockLine("04-Oct Yamini Hansraj Naveen", 40, 80, 1120, 90,
                mockElement("04-Oct", 45, 80, 90, 90),
                mockElement("Yamini", 560, 80, 610, 90),
                mockElement("Hansraj Naveen", 615, 80, 685, 90) // Compound element in Col 5
        );
        Text visionText = mockVisionText(lHeaders, lDate);

        // Yamini: colleagues = Hansraj, Naveen
        List<RosterTextParser.ExtractedShiftItem> dutiesYamini =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Yamini", 2026, 10);
        assertEquals(1, dutiesYamini.size());
        assertEquals("Hansraj, Naveen", dutiesYamini.get(0).colleagues);

        // Hansraj: colleagues = Yamini, Naveen
        List<RosterTextParser.ExtractedShiftItem> dutiesHansraj =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Hansraj", 2026, 10);
        assertEquals(1, dutiesHansraj.size());
        assertEquals("Yamini, Naveen", dutiesHansraj.get(0).colleagues);

        // Naveen: colleagues = Yamini, Hansraj
        List<RosterTextParser.ExtractedShiftItem> dutiesNaveen =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Naveen", 2026, 10);
        assertEquals(1, dutiesNaveen.size());
        assertEquals("Yamini, Hansraj", dutiesNaveen.get(0).colleagues);
    }

    @Test
    public void testColleagueExtraction_SplitAcrossAdjacentOcrElements() {
        Text.Line lHeaders = mockStandardHeaders();
        Text.Line lDate = mockLine("04-Oct Ajay Jangid Yamini", 40, 80, 1120, 90,
                mockElement("04-Oct", 45, 80, 90, 90),
                mockElement("Ajay", 560, 80, 590, 90),    // Split first name
                mockElement("Jangid", 595, 80, 635, 90),  // Split surname
                mockElement("Yamini", 640, 80, 685, 90)   // Second colleague
        );
        Text visionText = mockVisionText(lHeaders, lDate);

        // Yamini: colleague = Ajay Jangid (assembled across elements)
        List<RosterTextParser.ExtractedShiftItem> dutiesYamini =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Yamini", 2026, 10);
        assertEquals(1, dutiesYamini.size());
        assertEquals("Ajay Jangid", dutiesYamini.get(0).colleagues);

        // Ajay Jangid: colleague = Yamini
        List<RosterTextParser.ExtractedShiftItem> dutiesAjay =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Ajay Jangid", 2026, 10);
        assertEquals(1, dutiesAjay.size());
        assertEquals("Yamini", dutiesAjay.get(0).colleagues);
    }

    @Test
    public void testColleagueExtraction_AjayJangidVsAjayJindoliya_RemainStrictlyDistinct() {
        Text.Line lHeaders = mockStandardHeaders();
        Text.Line lDate = mockLine("04-Oct Ajay Jangid Yamini Ajay Jindoliya Rohit", 40, 80, 1120, 90,
                mockElement("04-Oct", 45, 80, 90, 90),
                mockElement("Ajay Jangid", 560, 80, 620, 90),     // In Col 5 (2pm-8pm)
                mockElement("Yamini", 630, 80, 680, 90),          // In Col 5 (2pm-8pm)
                mockElement("Ajay Jindoliya", 700, 80, 755, 90),  // In Col 6 (8pm-8am)
                mockElement("Rohit", 760, 80, 805, 90)            // In Col 6 (8pm-8am)
        );
        Text visionText = mockVisionText(lHeaders, lDate);

        // Yamini (Col 5): Colleague = Ajay Jangid strictly (NOT Ajay Jindoliya or Rohit)
        List<RosterTextParser.ExtractedShiftItem> dutiesYamini =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Yamini", 2026, 10);
        assertEquals(1, dutiesYamini.size());
        assertEquals(ShiftType.EVENING, dutiesYamini.get(0).shiftType);
        assertEquals("Ajay Jangid", dutiesYamini.get(0).colleagues);
        assertFalse(dutiesYamini.get(0).colleagues.contains("Ajay Jindoliya"));

        // Ajay Jindoliya (Col 6): Colleague = Rohit strictly (NOT Ajay Jangid or Yamini)
        List<RosterTextParser.ExtractedShiftItem> dutiesJindoliya =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Ajay Jindoliya", 2026, 10);
        assertEquals(1, dutiesJindoliya.size());
        assertEquals(ShiftType.NIGHT, dutiesJindoliya.get(0).shiftType);
        assertEquals("Rohit", dutiesJindoliya.get(0).colleagues);
        assertFalse(dutiesJindoliya.get(0).colleagues.contains("Ajay Jangid"));
        assertFalse(dutiesJindoliya.get(0).colleagues.contains("Yamini"));
    }

    @Test
    public void testColleagueExtraction_AmbiguousAjayAndUnapprovedStaff_SkippedWithoutSubstitution() {
        Text.Line lHeaders = mockStandardHeaders();
        Text.Line lDate = mockLine("04-Oct Yamini Ajay Dr.Kumar Rohit", 40, 80, 1120, 90,
                mockElement("04-Oct", 45, 80, 90, 90),
                mockElement("Yamini", 560, 80, 590, 90),       // Approved
                mockElement("Ajay", 595, 80, 620, 90),         // Standalone ambiguous Ajay -> skip!
                mockElement("Dr.Kumar", 625, 80, 655, 90),     // Unapproved non-person/token -> skip!
                mockElement("Rohit", 660, 80, 685, 90)         // Approved -> preserved!
        );
        Text visionText = mockVisionText(lHeaders, lDate);

        List<RosterTextParser.ExtractedShiftItem> duties =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Yamini", 2026, 10);

        assertEquals(1, duties.size());
        // Only Rohit should be preserved; ambiguous Ajay and unapproved Dr.Kumar must be skipped
        assertEquals("Rohit", duties.get(0).colleagues);
        assertFalse("Ambiguous Ajay must not be substituted or present", duties.get(0).colleagues.contains("Ajay"));
        assertFalse("Unapproved staff must not be present", duties.get(0).colleagues.contains("Kumar"));
    }

    @Test
    public void testColleagueExtraction_OcrVariant_YarminiNormalizedToYamini() {
        Text.Line lHeaders = mockStandardHeaders();
        Text.Line lDate = mockLine("04-Oct Yarmini Ajay Jangid", 40, 80, 1120, 90,
                mockElement("04-Oct", 45, 80, 90, 90),
                mockElement("Yarmini", 585, 80, 630, 90),      // OCR variant of Yamini
                mockElement("Ajay Jangid", 635, 80, 700, 90)
        );
        Text visionText = mockVisionText(lHeaders, lDate);

        // When queried for "Yamini", Yarmini is recognized as self and excluded
        List<RosterTextParser.ExtractedShiftItem> dutiesYamini =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Yamini", 2026, 10);
        assertEquals(1, dutiesYamini.size());
        assertEquals("Ajay Jangid", dutiesYamini.get(0).colleagues);

        // When queried for "Ajay Jangid", colleague is normalized to canonical Yamini
        List<RosterTextParser.ExtractedShiftItem> dutiesAjay =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Ajay Jangid", 2026, 10);
        assertEquals(1, dutiesAjay.size());
        assertEquals("Yamini", dutiesAjay.get(0).colleagues);
    }

    @Test
    public void testColleagueExtraction_OverlappingBoundingBoxes_BothPreserved() {
        Text.Line lHeaders = mockStandardHeaders();
        // Overlapping bounding boxes: Yamini right=660, Ajay Jangid left=640 (20px horizontal overlap)
        Text.Line lDate = mockLine("04-Oct Yamini Ajay Jangid", 40, 80, 1120, 90,
                mockElement("04-Oct", 45, 80, 90, 90),
                mockElement("Yamini", 590, 80, 660, 90),
                mockElement("Ajay Jangid", 640, 80, 715, 90)
        );
        Text visionText = mockVisionText(lHeaders, lDate);

        List<RosterTextParser.ExtractedShiftItem> dutiesYamini =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Yamini", 2026, 10);
        assertEquals(1, dutiesYamini.size());
        assertEquals("Overlapping bounding boxes must not cause colleague to be discarded",
                "Ajay Jangid", dutiesYamini.get(0).colleagues);

        List<RosterTextParser.ExtractedShiftItem> dutiesAjay =
                FullMonthRosterParser.extractDutiesForEmployee(visionText, visionText.getText(), "Ajay Jangid", 2026, 10);
        assertEquals(1, dutiesAjay.size());
        assertEquals("Yamini", dutiesAjay.get(0).colleagues);
    }

    @Test
    public void testOffAndLeaveNeverInterchanged() {
        // 1. Column identification
        assertEquals(7, FullMonthRosterParser.identifyColumnType("Off"));
        assertEquals(7, FullMonthRosterParser.identifyColumnType("W/O"));
        assertEquals(7, FullMonthRosterParser.identifyColumnType("Weekly Off"));
        assertEquals(8, FullMonthRosterParser.identifyColumnType("Leave"));
        assertEquals(8, FullMonthRosterParser.identifyColumnType("On Leave"));
        assertEquals(8, FullMonthRosterParser.identifyColumnType("CL"));
        assertEquals(8, FullMonthRosterParser.identifyColumnType("EL"));

        // 2. TableColumn parsing
        FullMonthRosterParser.TableColumn colOff = new FullMonthRosterParser.TableColumn(7, "Off", null);
        assertTrue("Col 7 must be Off", colOff.isOff);
        assertFalse("Col 7 must NOT be Leave", colOff.isLeave);
        assertEquals(ShiftType.OFF, colOff.shiftType);
        assertFalse(colOff.shiftType == ShiftType.MORNING);
        assertFalse(colOff.shiftType == ShiftType.EVENING);
        assertFalse(colOff.shiftType == ShiftType.NIGHT);
        assertFalse(colOff.shiftType == ShiftType.GENERAL);

        FullMonthRosterParser.TableColumn colLeave = new FullMonthRosterParser.TableColumn(8, "Leave", null);
        assertFalse("Col 8 must NOT be Off", colLeave.isOff);
        assertTrue("Col 8 must be Leave", colLeave.isLeave);
        assertEquals(ShiftType.LEAVE, colLeave.shiftType);
        assertFalse(colLeave.shiftType == ShiftType.MORNING);
        assertFalse(colLeave.shiftType == ShiftType.EVENING);
        assertFalse(colLeave.shiftType == ShiftType.NIGHT);
        assertFalse(colLeave.shiftType == ShiftType.GENERAL);

        // 3. Extraction: target in Off or Leave does NOT create working duties
        String roster = "Date | 9 AM-5 PM | 10 AM-6 PM | 12 PM-8 PM | 8 AM-2 PM | 2 PM-8 PM | 8 PM-8 AM | Off | Leave\n" +
                "10/10/2026 | | | | | | | Shiv | Naveen\n";

        List<RosterTextParser.ExtractedShiftItem> dutiesShiv =
                FullMonthRosterParser.extractDutiesForEmployee(null, roster, "Shiv", 2026, 10);
        assertTrue("Target in Off must NOT produce working duty", dutiesShiv.isEmpty());

        List<RosterTextParser.ExtractedShiftItem> dutiesNaveen =
                FullMonthRosterParser.extractDutiesForEmployee(null, roster, "Naveen", 2026, 10);
        assertTrue("Target in Leave must NOT produce working duty", dutiesNaveen.isEmpty());

        // 4. Do not infer Leave from an empty cell or infer Off merely because a working cell is empty
        String emptyRoster = "Date | 9 AM-5 PM | 10 AM-6 PM | 12 PM-8 PM | 8 AM-2 PM | 2 PM-8 PM | 8 PM-8 AM | Off | Leave\n" +
                "11/10/2026 | | | | | | | | \n";
        List<RosterTextParser.ExtractedShiftItem> dutiesEmpty =
                FullMonthRosterParser.extractDutiesForEmployee(null, emptyRoster, "Yamini", 2026, 10);
        assertTrue("Empty row must NOT infer Off or Leave", dutiesEmpty.isEmpty());
    }

    @Test
    public void testNightShiftVisualDistinction_ColorTokens() {
        // Night shift must map to its dedicated distinct color tokens
        assertEquals(R.color.shift_night, ShiftType.NIGHT.getColorRes());
        assertEquals(R.color.shift_night_bg, ShiftType.NIGHT.getBgRes());
        assertEquals(R.color.shift_night_text, ShiftType.NIGHT.getTextRes());

        // Morning and Evening must retain their existing separate color tokens
        assertEquals(R.color.shift_morning, ShiftType.MORNING.getColorRes());
        assertEquals(R.color.shift_morning_bg, ShiftType.MORNING.getBgRes());
        assertEquals(R.color.shift_morning_text, ShiftType.MORNING.getTextRes());

        assertEquals(R.color.shift_evening, ShiftType.EVENING.getColorRes());
        assertEquals(R.color.shift_evening_bg, ShiftType.EVENING.getBgRes());
        assertEquals(R.color.shift_evening_text, ShiftType.EVENING.getTextRes());

        // Shift timings and classifications must not be changed
        assertEquals("08:00 PM", ShiftType.NIGHT.getDefaultStartTime());
        assertEquals("08:00 AM", ShiftType.NIGHT.getDefaultEndTime());
        assertTrue(ShiftType.NIGHT.isDefaultOvernight());
    }

    @Test
    public void testRoomEntityRoundTrip_AllShiftTypesAndColleagueLists() {
        // Create a duty with multiple colleagues and Night shift
        Duty duty = new Duty();
        duty.setId("test-duty-night-001");
        duty.setOwnerUid("user-456");
        duty.setDutyDate("2026-10-02");
        duty.setShiftType(ShiftType.NIGHT);
        duty.setShiftName("Night");
        duty.setStartTime("08:00 PM");
        duty.setEndTime("08:00 AM");
        duty.setOvernight(true);
        duty.setWardOrDepartment("ICU");
        duty.setColleagues("Ajay Jangid, Rohit, Govind");
        duty.setNotes("Imported from hospital roster");
        duty.setStatus(DutyStatus.SCHEDULED);
        duty.setSyncStatus(SyncStatus.PENDING_UPLOAD);

        // Convert to Room Entity
        DutyEntity entity = DutyEntity.fromDomain(duty, "user-456");
        assertEquals("user-456", entity.getOwnerUid());
        assertEquals("2026-10-02", entity.getDutyDate());
        assertEquals("NIGHT", entity.getShiftType());
        assertEquals("08:00 PM", entity.getStartTime());
        assertEquals("08:00 AM", entity.getEndTime());
        assertTrue(entity.isOvernight());
        assertEquals("Ajay Jangid, Rohit, Govind", entity.getColleagues());
        assertEquals("SCHEDULED", entity.getStatus());

        // Convert back to domain Duty (simulating Room reload)
        Duty reloaded = entity.toDomain();
        assertEquals(duty.getId(), reloaded.getId());
        assertEquals(duty.getOwnerUid(), reloaded.getOwnerUid());
        assertEquals(duty.getDutyDate(), reloaded.getDutyDate());
        assertEquals(ShiftType.NIGHT, reloaded.getShiftType());
        assertEquals("Night", reloaded.getShiftName());
        assertEquals("08:00 PM", reloaded.getStartTime());
        assertEquals("08:00 AM", reloaded.getEndTime());
        assertTrue(reloaded.isOvernight());
        assertEquals("ICU", reloaded.getWardOrDepartment());
        assertEquals("Ajay Jangid, Rohit, Govind", reloaded.getColleagues());
        assertEquals(DutyStatus.SCHEDULED, reloaded.getStatus());
    }

    @Test
    public void testOctober2_CompleteFixture_YaminiEvening_GovindRohitNight_ShivOff() {
        // Complete October 2 test based on the actual roster fixture
        String roster = "Date | 9 AM-5 PM | 10 AM-6 PM | 12 PM-8 PM | 8 AM-2 PM | 2 PM-8 PM | 8 PM-8 AM | Off | Leave\n" +
                "02/10/2026 | | | Kuldeep | Pradeep, Vishal | Ajay Jangid, Yamini | Govind, Rohit | Shiv, Munesh | Ajay Jindoliya\n";

        // 1. Yamini: Evening, 2 PM–8 PM, colleague Ajay Jangid
        List<RosterTextParser.ExtractedShiftItem> yaminiDuties =
                FullMonthRosterParser.extractDutiesForEmployee(null, roster, "Yamini", 2026, 10);
        assertEquals(1, yaminiDuties.size());
        RosterTextParser.ExtractedShiftItem yDuty = yaminiDuties.get(0);
        assertEquals("2026-10-02", yDuty.date);
        assertEquals(ShiftType.EVENING, yDuty.shiftType);
        assertEquals("02:00 PM", yDuty.startTime);
        assertEquals("08:00 PM", yDuty.endTime);
        assertFalse(yDuty.isOvernight);
        assertEquals("Ajay Jangid", yDuty.colleagues);

        // 2. Govind: Night, 8 PM–8 AM, colleague Rohit
        List<RosterTextParser.ExtractedShiftItem> govindDuties =
                FullMonthRosterParser.extractDutiesForEmployee(null, roster, "Govind", 2026, 10);
        assertEquals(1, govindDuties.size());
        RosterTextParser.ExtractedShiftItem gDuty = govindDuties.get(0);
        assertEquals("2026-10-02", gDuty.date);
        assertEquals(ShiftType.NIGHT, gDuty.shiftType);
        assertEquals("08:00 PM", gDuty.startTime);
        assertEquals("08:00 AM", gDuty.endTime);
        assertTrue(gDuty.isOvernight);
        assertEquals("Rohit", gDuty.colleagues);

        // 3. Rohit: Night, 8 PM–8 AM, colleague Govind
        List<RosterTextParser.ExtractedShiftItem> rohitDuties =
                FullMonthRosterParser.extractDutiesForEmployee(null, roster, "Rohit", 2026, 10);
        assertEquals(1, rohitDuties.size());
        RosterTextParser.ExtractedShiftItem rDuty = rohitDuties.get(0);
        assertEquals("2026-10-02", rDuty.date);
        assertEquals(ShiftType.NIGHT, rDuty.shiftType);
        assertEquals(gDuty.startTime, rDuty.startTime);
        assertEquals(gDuty.endTime, rDuty.endTime);
        assertTrue(rDuty.isOvernight);
        assertEquals("Govind", rDuty.colleagues);

        // 4. Shiv: Off -> not a working duty
        List<RosterTextParser.ExtractedShiftItem> shivDuties =
                FullMonthRosterParser.extractDutiesForEmployee(null, roster, "Shiv", 2026, 10);
        assertTrue("Shiv in Off column must NOT produce a working duty", shivDuties.isEmpty());

        // 5. Ajay Jindoliya: in Leave column -> not a working duty
        List<RosterTextParser.ExtractedShiftItem> jindoliyaDuties =
                FullMonthRosterParser.extractDutiesForEmployee(null, roster, "Ajay Jindoliya", 2026, 10);
        assertTrue("Ajay Jindoliya in Leave column must NOT produce a working duty", jindoliyaDuties.isEmpty());
    }
}
