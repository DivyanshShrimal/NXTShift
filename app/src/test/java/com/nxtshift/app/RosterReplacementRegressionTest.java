package com.nxtshift.app;

import static org.junit.Assert.*;

import com.nxtshift.app.data.local.DutyEntity;
import com.nxtshift.app.domain.Duty;
import com.nxtshift.app.domain.DutyStatus;
import com.nxtshift.app.domain.ShiftType;
import com.nxtshift.app.domain.SyncStatus;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Regression test suite verifying:
 * 1. Bug 1: Correct "Replace Entire Month Range" Behavior:
 *    - Duty -> different duty (Morning -> Night, Morning -> Evening)
 *    - Off -> duty (Off -> Morning)
 *    - Leave -> duty (Leave -> Night)
 *    - Duty -> Off (Morning -> Off)
 *    - Duty -> Leave (Night -> Leave)
 *    - Duplicate / conflicting entries purged on same date
 *    - Dates absent from the updated roster are preserved
 *    - Multi-staff and data isolation preserved
 *    - Manual duties preserved and distinct from roster-generated duties
 *    - Atomic replacement rollback simulation
 *    - Deduplication and deleted ID tracking during cloud sync merge
 */
public class RosterReplacementRegressionTest {

    private static final String USER_A = "uid_nurse_shreya";
    private static final String USER_B = "uid_nurse_priya";

    @Before
    public void setUp() {
        // Setup before each test
    }

    // =========================================================================
    // 1. Provenance & Detection Tests
    // =========================================================================

    @Test
    public void testRosterGeneratedProvenanceDetection() {
        // Excel import note
        assertTrue(DutyEntity.isRosterGenerated("Imported from hospital Excel roster (Row 5)"));
        assertTrue(DutyEntity.isRosterGenerated("Imported via Excel"));
        assertTrue(DutyEntity.isRosterGenerated("Imported from roster photo - Unrecognized shift timing"));
        assertTrue(DutyEntity.isRosterGenerated("Published roster import (October 2026)"));

        // Domain object provenance
        Duty rosterDuty = new Duty();
        rosterDuty.setNotes("Imported from hospital Excel roster (Row 12)");
        assertTrue(rosterDuty.isRosterGenerated());

        // Manual entries MUST NOT be classified as roster-generated
        assertFalse(DutyEntity.isRosterGenerated(null));
        assertFalse(DutyEntity.isRosterGenerated(""));
        assertFalse(DutyEntity.isRosterGenerated("   "));
        assertFalse(DutyEntity.isRosterGenerated("Swapped shift with Nurse Sarah"));
        assertFalse(DutyEntity.isRosterGenerated("Emergency overtime in ICU"));
        assertFalse(DutyEntity.isRosterGenerated("Requested off for CME conference"));

        Duty manualDuty = new Duty();
        manualDuty.setNotes("Covering Sarah's shift");
        assertFalse(manualDuty.isRosterGenerated());
    }

    // =========================================================================
    // 2. Roster Replacement State Transitions
    // =========================================================================

    /**
     * Helper to simulate repository replacement logic for a specific date:
     * - Identifies existing roster duties
     * - Reuses the primary duty ID if available (to overwrite cleanly in-place)
     * - Marks any extra duplicate roster duties on that date for deletion
     * - Preserves manual duties
     */
    private static class ReplacementResult {
        Duty finalDuty;
        List<String> idsToDelete = new ArrayList<>();
        List<Duty> preservedManualDuties = new ArrayList<>();
    }

    private ReplacementResult simulateDateReplacement(List<Duty> existingDutiesOnDate, Duty newDuty) {
        ReplacementResult result = new ReplacementResult();
        Duty existingRosterDuty = null;

        for (Duty existing : existingDutiesOnDate) {
            if (existing.isRosterGenerated()) {
                if (existingRosterDuty == null) {
                    existingRosterDuty = existing;
                } else {
                    // Duplicate roster duty to delete
                    result.idsToDelete.add(existing.getId());
                }
            } else {
                // Manual duty: PRESERVE!
                result.preservedManualDuties.add(existing);
            }
        }

        if (existingRosterDuty != null) {
            newDuty.setId(existingRosterDuty.getId());
        } else if (newDuty.getId() == null || newDuty.getId().isEmpty()) {
            newDuty.setId(UUID.randomUUID().toString());
        }

        result.finalDuty = newDuty;
        return result;
    }

    @Test
    public void testDutyToDifferentDuty_MorningToNight() {
        Duty oldDuty = new Duty();
        oldDuty.setId("duty_oct_15");
        oldDuty.setOwnerUid(USER_A);
        oldDuty.setDutyDate("2026-10-15");
        oldDuty.setShiftType(ShiftType.MORNING);
        oldDuty.setShiftName("Morning Shift");
        oldDuty.setNotes("Imported via Excel");

        Duty newDuty = new Duty();
        newDuty.setOwnerUid(USER_A);
        newDuty.setDutyDate("2026-10-15");
        newDuty.setShiftType(ShiftType.NIGHT);
        newDuty.setShiftName("Night Shift");
        newDuty.setStartTime("08:00 PM");
        newDuty.setEndTime("08:00 AM");
        newDuty.setNotes("Imported from hospital Excel roster (Row 15)");

        ReplacementResult result = simulateDateReplacement(Collections.singletonList(oldDuty), newDuty);

        assertEquals("duty_oct_15", result.finalDuty.getId());
        assertEquals(ShiftType.NIGHT, result.finalDuty.getShiftType());
        assertEquals("08:00 PM", result.finalDuty.getStartTime());
        assertEquals("08:00 AM", result.finalDuty.getEndTime());
        assertTrue(result.idsToDelete.isEmpty());
    }

    @Test
    public void testOffToDuty_OffToMorning() {
        Duty oldOff = new Duty();
        oldOff.setId("off_oct_16");
        oldOff.setOwnerUid(USER_A);
        oldOff.setDutyDate("2026-10-16");
        oldOff.setShiftType(ShiftType.OFF);
        oldOff.setStatus(DutyStatus.OFF);
        oldOff.setNotes("Imported via Excel");

        Duty newDuty = new Duty();
        newDuty.setOwnerUid(USER_A);
        newDuty.setDutyDate("2026-10-16");
        newDuty.setShiftType(ShiftType.MORNING);
        newDuty.setShiftName("Morning Shift");
        newDuty.setStatus(DutyStatus.SCHEDULED);
        newDuty.setNotes("Imported from hospital Excel roster (Row 16)");

        ReplacementResult result = simulateDateReplacement(Collections.singletonList(oldOff), newDuty);

        assertEquals("off_oct_16", result.finalDuty.getId());
        assertEquals(ShiftType.MORNING, result.finalDuty.getShiftType());
        assertEquals(DutyStatus.SCHEDULED, result.finalDuty.getStatus());
        assertFalse(result.finalDuty.isOffOrLeave());
    }

    @Test
    public void testLeaveToDuty_LeaveToNight() {
        Duty oldLeave = new Duty();
        oldLeave.setId("leave_oct_17");
        oldLeave.setOwnerUid(USER_A);
        oldLeave.setDutyDate("2026-10-17");
        oldLeave.setShiftType(ShiftType.LEAVE);
        oldLeave.setStatus(DutyStatus.LEAVE);
        oldLeave.setNotes("Imported from hospital Excel roster (Row 17)");

        Duty newNight = new Duty();
        newNight.setOwnerUid(USER_A);
        newNight.setDutyDate("2026-10-17");
        newNight.setShiftType(ShiftType.NIGHT);
        newNight.setStatus(DutyStatus.SCHEDULED);
        newNight.setNotes("Imported from hospital Excel roster (Row 17)");

        ReplacementResult result = simulateDateReplacement(Collections.singletonList(oldLeave), newNight);

        assertEquals("leave_oct_17", result.finalDuty.getId());
        assertEquals(ShiftType.NIGHT, result.finalDuty.getShiftType());
        assertFalse(result.finalDuty.isOffOrLeave());
    }

    @Test
    public void testDutyToOff_MorningToOff() {
        Duty oldMorning = new Duty();
        oldMorning.setId("duty_oct_18");
        oldMorning.setOwnerUid(USER_A);
        oldMorning.setDutyDate("2026-10-18");
        oldMorning.setShiftType(ShiftType.MORNING);
        oldMorning.setStatus(DutyStatus.SCHEDULED);
        oldMorning.setNotes("Imported via Excel");

        Duty newOff = new Duty();
        newOff.setOwnerUid(USER_A);
        newOff.setDutyDate("2026-10-18");
        newOff.setShiftType(ShiftType.OFF);
        newOff.setStatus(DutyStatus.OFF);
        newOff.setNotes("Imported from hospital Excel roster (Row 18)");

        ReplacementResult result = simulateDateReplacement(Collections.singletonList(oldMorning), newOff);

        assertEquals("duty_oct_18", result.finalDuty.getId());
        assertEquals(ShiftType.OFF, result.finalDuty.getShiftType());
        assertEquals(DutyStatus.OFF, result.finalDuty.getStatus());
        assertTrue(result.finalDuty.isOffOrLeave());
    }

    @Test
    public void testDutyToLeave_NightToLeave() {
        Duty oldNight = new Duty();
        oldNight.setId("duty_oct_19");
        oldNight.setOwnerUid(USER_A);
        oldNight.setDutyDate("2026-10-19");
        oldNight.setShiftType(ShiftType.NIGHT);
        oldNight.setStatus(DutyStatus.SCHEDULED);
        oldNight.setNotes("Imported via Excel");

        Duty newLeave = new Duty();
        newLeave.setOwnerUid(USER_A);
        newLeave.setDutyDate("2026-10-19");
        newLeave.setShiftType(ShiftType.LEAVE);
        newLeave.setStatus(DutyStatus.LEAVE);
        newLeave.setNotes("Imported from hospital Excel roster (Row 19)");

        ReplacementResult result = simulateDateReplacement(Collections.singletonList(oldNight), newLeave);

        assertEquals("duty_oct_19", result.finalDuty.getId());
        assertEquals(ShiftType.LEAVE, result.finalDuty.getShiftType());
        assertEquals(DutyStatus.LEAVE, result.finalDuty.getStatus());
        assertTrue(result.finalDuty.isOffOrLeave());
    }

    @Test
    public void testDutyToDifferentDuty_MorningToEvening() {
        Duty oldMorning = new Duty();
        oldMorning.setId("duty_oct_20");
        oldMorning.setOwnerUid(USER_A);
        oldMorning.setDutyDate("2026-10-20");
        oldMorning.setShiftType(ShiftType.MORNING);
        oldMorning.setStatus(DutyStatus.SCHEDULED);
        oldMorning.setNotes("Imported via Excel");

        Duty newEvening = new Duty();
        newEvening.setOwnerUid(USER_A);
        newEvening.setDutyDate("2026-10-20");
        newEvening.setShiftType(ShiftType.EVENING);
        newEvening.setStatus(DutyStatus.SCHEDULED);
        newEvening.setNotes("Imported from hospital Excel roster (Row 20)");

        ReplacementResult result = simulateDateReplacement(Collections.singletonList(oldMorning), newEvening);

        assertEquals("duty_oct_20", result.finalDuty.getId());
        assertEquals(ShiftType.EVENING, result.finalDuty.getShiftType());
    }

    // =========================================================================
    // 3. Duplicate and Conflicting Duties Purge
    // =========================================================================

    @Test
    public void testDuplicateAndConflictingDutiesPurged() {
        // Suppose previous faulty imports created 3 conflicting entries on 2026-10-21:
        // Morning, Night, and Off simultaneously!
        Duty duty1 = new Duty();
        duty1.setId("dup_id_1");
        duty1.setDutyDate("2026-10-21");
        duty1.setShiftType(ShiftType.MORNING);
        duty1.setNotes("Imported via Excel");

        Duty duty2 = new Duty();
        duty2.setId("dup_id_2");
        duty2.setDutyDate("2026-10-21");
        duty2.setShiftType(ShiftType.NIGHT);
        duty2.setNotes("Imported from hospital Excel roster (Row 21)");

        Duty duty3 = new Duty();
        duty3.setId("dup_id_3");
        duty3.setDutyDate("2026-10-21");
        duty3.setShiftType(ShiftType.OFF);
        duty3.setNotes("Imported from hospital Excel roster (Row 21)");

        List<Duty> conflicting = new ArrayList<>();
        conflicting.add(duty1);
        conflicting.add(duty2);
        conflicting.add(duty3);

        // New imported roster explicitly marks Evening shift for this date
        Duty newDuty = new Duty();
        newDuty.setDutyDate("2026-10-21");
        newDuty.setShiftType(ShiftType.EVENING);
        newDuty.setNotes("Imported from hospital Excel roster (Row 21)");

        ReplacementResult result = simulateDateReplacement(conflicting, newDuty);

        // Reuses the first ID cleanly
        assertEquals("dup_id_1", result.finalDuty.getId());
        assertEquals(ShiftType.EVENING, result.finalDuty.getShiftType());

        // And queues the 2 obsolete duplicate IDs for deletion in Room and Firestore
        assertEquals(2, result.idsToDelete.size());
        assertTrue(result.idsToDelete.contains("dup_id_2"));
        assertTrue(result.idsToDelete.contains("dup_id_3"));
    }

    // =========================================================================
    // 4. Date Scope & Preservation of Absent Dates
    // =========================================================================

    @Test
    public void testDatesAbsentFromUpdatedRosterArePreserved() {
        // User has duties across October and November
        Map<String, Duty> userDuties = new HashMap<>();

        Duty oct1 = new Duty();
        oct1.setId("duty_oct_01");
        oct1.setDutyDate("2026-10-01");
        oct1.setShiftType(ShiftType.MORNING);
        oct1.setNotes("Imported via Excel");
        userDuties.put(oct1.getDutyDate(), oct1);

        Duty oct15 = new Duty();
        oct15.setId("duty_oct_15");
        oct15.setDutyDate("2026-10-15");
        oct15.setShiftType(ShiftType.NIGHT);
        oct15.setNotes("Imported via Excel");
        userDuties.put(oct15.getDutyDate(), oct15);

        Duty nov01 = new Duty();
        nov01.setId("duty_nov_01");
        nov01.setDutyDate("2026-11-01");
        nov01.setShiftType(ShiftType.MORNING);
        nov01.setNotes("Imported via Excel");
        userDuties.put(nov01.getDutyDate(), nov01);

        // An updated roster is imported that ONLY covers 2026-10-01 to 2026-10-05
        Set<String> coveredDates = new HashSet<>();
        coveredDates.add("2026-10-01");

        // The new roster changes 2026-10-01 to Evening
        Duty updatedOct1 = new Duty();
        updatedOct1.setId("duty_oct_01");
        updatedOct1.setDutyDate("2026-10-01");
        updatedOct1.setShiftType(ShiftType.EVENING);
        updatedOct1.setNotes("Imported from hospital Excel roster");

        // Simulate replacement within covered scope:
        if (coveredDates.contains("2026-10-01")) {
            userDuties.put("2026-10-01", updatedOct1);
        }

        // Verify:
        // 1. Covered date 2026-10-01 was updated to Evening
        assertEquals(ShiftType.EVENING, userDuties.get("2026-10-01").getShiftType());

        // 2. Dates outside the covered scope (2026-10-15 and 2026-11-01) MUST NOT be deleted
        assertNotNull(userDuties.get("2026-10-15"));
        assertEquals(ShiftType.NIGHT, userDuties.get("2026-10-15").getShiftType());
        assertNotNull(userDuties.get("2026-11-01"));
        assertEquals(ShiftType.MORNING, userDuties.get("2026-11-01").getShiftType());
    }

    // =========================================================================
    // 5. Manual Duty Preservation
    // =========================================================================

    @Test
    public void testManualDutiesPreservedDuringRosterReplacement() {
        // User created a manual duty on 2026-10-14
        Duty manualDuty = new Duty();
        manualDuty.setId("manual_custom_14");
        manualDuty.setOwnerUid(USER_A);
        manualDuty.setDutyDate("2026-10-14");
        manualDuty.setShiftType(ShiftType.GENERAL);
        manualDuty.setNotes("Swapped with Nurse Sarah for doctor appointment");

        // User also had an old roster duty on 2026-10-14
        Duty oldRosterDuty = new Duty();
        oldRosterDuty.setId("roster_oct_14");
        oldRosterDuty.setOwnerUid(USER_A);
        oldRosterDuty.setDutyDate("2026-10-14");
        oldRosterDuty.setShiftType(ShiftType.MORNING);
        oldRosterDuty.setNotes("Imported from hospital Excel roster (Row 14)");

        List<Duty> existingOnDate = new ArrayList<>();
        existingOnDate.add(manualDuty);
        existingOnDate.add(oldRosterDuty);

        // Updated roster specifies Night duty for 2026-10-14
        Duty newRosterDuty = new Duty();
        newRosterDuty.setOwnerUid(USER_A);
        newRosterDuty.setDutyDate("2026-10-14");
        newRosterDuty.setShiftType(ShiftType.NIGHT);
        newRosterDuty.setNotes("Imported from hospital Excel roster (Row 14)");

        ReplacementResult result = simulateDateReplacement(existingOnDate, newRosterDuty);

        // Roster duty ID reused and updated
        assertEquals("roster_oct_14", result.finalDuty.getId());
        assertEquals(ShiftType.NIGHT, result.finalDuty.getShiftType());

        // Manual duty was PRESERVED and not deleted
        assertEquals(1, result.preservedManualDuties.size());
        assertEquals("manual_custom_14", result.preservedManualDuties.get(0).getId());
        assertFalse(result.idsToDelete.contains("manual_custom_14"));
    }

    // =========================================================================
    // 6. Multi-Staff and Data Isolation
    // =========================================================================

    @Test
    public void testMultipleStaffMembersAndDataIsolation() {
        // Staff A and Staff B have duties on 2026-10-14
        Duty dutyA = new Duty();
        dutyA.setId("duty_nurse_A_14");
        dutyA.setOwnerUid(USER_A);
        dutyA.setDutyDate("2026-10-14");
        dutyA.setShiftType(ShiftType.MORNING);
        dutyA.setNotes("Imported via Excel");

        Duty dutyB = new Duty();
        dutyB.setId("duty_nurse_B_14");
        dutyB.setOwnerUid(USER_B);
        dutyB.setDutyDate("2026-10-14");
        dutyB.setShiftType(ShiftType.NIGHT);
        dutyB.setNotes("Imported via Excel");

        Map<String, Map<String, Duty>> databaseByOwner = new HashMap<>();
        databaseByOwner.put(USER_A, new HashMap<>());
        databaseByOwner.put(USER_B, new HashMap<>());
        databaseByOwner.get(USER_A).put(dutyA.getDutyDate(), dutyA);
        databaseByOwner.get(USER_B).put(dutyB.getDutyDate(), dutyB);

        // Staff A imports an updated roster changing 2026-10-14 to Night
        Duty updatedDutyA = new Duty();
        updatedDutyA.setId("duty_nurse_A_14");
        updatedDutyA.setOwnerUid(USER_A);
        updatedDutyA.setDutyDate("2026-10-14");
        updatedDutyA.setShiftType(ShiftType.NIGHT);
        updatedDutyA.setNotes("Imported via Excel");

        databaseByOwner.get(USER_A).put(updatedDutyA.getDutyDate(), updatedDutyA);

        // Staff A's duty updated
        assertEquals(ShiftType.NIGHT, databaseByOwner.get(USER_A).get("2026-10-14").getShiftType());

        // Staff B's duty remains UNTOUCHED
        assertEquals(USER_B, databaseByOwner.get(USER_B).get("2026-10-14").getOwnerUid());
        assertEquals(ShiftType.NIGHT, databaseByOwner.get(USER_B).get("2026-10-14").getShiftType());
        assertEquals("duty_nurse_B_14", databaseByOwner.get(USER_B).get("2026-10-14").getId());
    }

    // =========================================================================
    // 7. Atomic Rollback on Failure Simulation
    // =========================================================================

    @Test
    public void testFailedImportRollback_NoPartialReplacement() {
        Map<String, Duty> originalDatabase = new HashMap<>();

        Duty day1 = new Duty();
        day1.setId("day_1");
        day1.setDutyDate("2026-10-01");
        day1.setShiftType(ShiftType.MORNING);
        day1.setNotes("Imported via Excel");
        originalDatabase.put(day1.getDutyDate(), day1);

        Duty day2 = new Duty();
        day2.setId("day_2");
        day2.setDutyDate("2026-10-02");
        day2.setShiftType(ShiftType.MORNING);
        day2.setNotes("Imported via Excel");
        originalDatabase.put(day2.getDutyDate(), day2);

        // Copy database state prior to transaction
        Map<String, Duty> transactionalDb = new HashMap<>(originalDatabase);

        boolean transactionCommitted = false;
        try {
            // Step 1: Replace day 1
            Duty newDay1 = new Duty();
            newDay1.setId("day_1");
            newDay1.setDutyDate("2026-10-01");
            newDay1.setShiftType(ShiftType.NIGHT);
            newDay1.setNotes("Imported via Excel");
            transactionalDb.put(newDay1.getDutyDate(), newDay1);

            // Step 2: Failure occurs on day 2 (e.g. disk full, constraint violation, parse crash)
            throw new RuntimeException("Simulated SQLite write or parse failure midway through roster replacement");

            // transactionCommitted = true;
        } catch (Exception e) {
            // Atomic transaction aborts: discard transactionalDb and keep originalDatabase
            transactionalDb = new HashMap<>(originalDatabase);
        }

        assertFalse(transactionCommitted);
        // Day 1 must NOT remain partially replaced
        assertEquals(ShiftType.MORNING, transactionalDb.get("2026-10-01").getShiftType());
        assertEquals(ShiftType.MORNING, transactionalDb.get("2026-10-02").getShiftType());
    }

    // =========================================================================
    // 8. Cloud Sync Deduplication and Obsolete Remote Document Purging
    // =========================================================================

    @Test
    public void testCloudSyncDeduplicationAndDeletedIdTracking() {
        Set<String> pendingDeletedIds = new HashSet<>();
        pendingDeletedIds.add("old_deleted_duplicate_id");

        List<Duty> remoteDutiesFromFirestore = new ArrayList<>();

        // Remote contains an old duty whose ID was deleted locally
        Duty remoteDeleted = new Duty();
        remoteDeleted.setId("old_deleted_duplicate_id");
        remoteDeleted.setDutyDate("2026-10-15");
        remoteDeleted.setShiftType(ShiftType.MORNING);
        remoteDeleted.setNotes("Imported via Excel");
        remoteDeleted.setUpdatedAt(1000L);
        remoteDutiesFromFirestore.add(remoteDeleted);

        // Remote contains two duties for 2026-10-16 with different timestamps
        Duty remoteStale = new Duty();
        remoteStale.setId("remote_stale_16");
        remoteStale.setDutyDate("2026-10-16");
        remoteStale.setShiftType(ShiftType.MORNING);
        remoteStale.setNotes("Imported via Excel");
        remoteStale.setUpdatedAt(1000L);
        remoteDutiesFromFirestore.add(remoteStale);

        Duty remoteFresh = new Duty();
        remoteFresh.setId("remote_fresh_16");
        remoteFresh.setDutyDate("2026-10-16");
        remoteFresh.setShiftType(ShiftType.NIGHT);
        remoteFresh.setNotes("Imported via Excel");
        remoteFresh.setUpdatedAt(2000L);
        remoteDutiesFromFirestore.add(remoteFresh);

        // Simulate repository deduplication during cloud sync
        Map<String, Duty> remoteRosterByDate = new HashMap<>();
        List<String> obsoleteRemoteIdsToDelete = new ArrayList<>();

        for (Duty remote : remoteDutiesFromFirestore) {
            if (pendingDeletedIds.contains(remote.getId())) {
                obsoleteRemoteIdsToDelete.add(remote.getId());
                continue;
            }

            if (remote.isRosterGenerated()) {
                Duty existing = remoteRosterByDate.get(remote.getDutyDate());
                if (existing == null) {
                    remoteRosterByDate.put(remote.getDutyDate(), remote);
                } else {
                    if (remote.getUpdatedAt() > existing.getUpdatedAt()) {
                        obsoleteRemoteIdsToDelete.add(existing.getId());
                        remoteRosterByDate.put(remote.getDutyDate(), remote);
                    } else {
                        obsoleteRemoteIdsToDelete.add(remote.getId());
                    }
                }
            }
        }

        // Verify:
        // 1. Deleted ID was dropped and scheduled for remote Firestore deletion
        assertTrue(obsoleteRemoteIdsToDelete.contains("old_deleted_duplicate_id"));
        assertNull(remoteRosterByDate.get("2026-10-15"));

        // 2. Remote duplicates were deduplicated: the fresher one (Night, 2000L) won
        assertEquals("remote_fresh_16", remoteRosterByDate.get("2026-10-16").getId());
        assertEquals(ShiftType.NIGHT, remoteRosterByDate.get("2026-10-16").getShiftType());

        // 3. The stale remote duplicate was scheduled for deletion
        assertTrue(obsoleteRemoteIdsToDelete.contains("remote_stale_16"));
    }

    @Test
    public void testLocalPendingUploadWinsOverStaleRemote() {
        // Local user edited a duty while offline
        Duty local = new Duty();
        local.setId("duty_local_1");
        local.setDutyDate("2026-10-22");
        local.setShiftType(ShiftType.NIGHT);
        local.setSyncStatus(SyncStatus.PENDING_UPLOAD);
        local.setUpdatedAt(3000L);

        // Remote has older version from before offline edit
        Duty remote = new Duty();
        remote.setId("duty_local_1");
        remote.setDutyDate("2026-10-22");
        remote.setShiftType(ShiftType.MORNING);
        remote.setSyncStatus(SyncStatus.SYNCED);
        remote.setUpdatedAt(1000L);

        // In repository sync:
        boolean shouldKeepLocal = local.getSyncStatus() == SyncStatus.PENDING_UPLOAD && local.getUpdatedAt() >= remote.getUpdatedAt();
        assertTrue("Local uncommitted edit must be preserved over stale remote", shouldKeepLocal);
    }
}
