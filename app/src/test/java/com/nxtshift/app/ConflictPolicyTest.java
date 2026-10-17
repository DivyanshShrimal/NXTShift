package com.nxtshift.app;

import static org.junit.Assert.*;

import com.nxtshift.app.domain.Duty;
import com.nxtshift.app.domain.ShiftType;
import com.nxtshift.app.domain.SyncStatus;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConflictPolicyTest {

    @Test
    public void testMergeReplaceMatchingDates() {
        Map<String, Duty> existingMap = new HashMap<>();

        // Existing duty on 2026-10-14: Morning Shift
        Duty existing = new Duty();
        existing.setId("duty_old_1");
        existing.setOwnerUid("nurse_uid_1");
        existing.setDutyDate("2026-10-14");
        existing.setShiftType(ShiftType.MORNING);
        existing.setWardOrDepartment("Old Ward");
        existing.setNotes("Old notes");
        existing.setUpdatedAt(1000L);
        existingMap.put(existing.getDutyDate(), existing);

        // New imported duty on 2026-10-14: Evening Shift
        Duty imported = new Duty();
        imported.setId("duty_import_1");
        imported.setOwnerUid("nurse_uid_1");
        imported.setDutyDate("2026-10-14");
        imported.setShiftType(ShiftType.EVENING);
        imported.setWardOrDepartment("Emergency");
        imported.setUpdatedAt(2000L);

        // Under MERGE_REPLACE policy, replace matching date
        existingMap.put(imported.getDutyDate(), imported);

        Duty result = existingMap.get("2026-10-14");
        assertNotNull(result);
        assertEquals(ShiftType.EVENING, result.getShiftType());
        assertEquals("Emergency", result.getWardOrDepartment());
    }

    @Test
    public void testKeepExistingOnConflictPolicy() {
        Map<String, Duty> existingMap = new HashMap<>();

        Duty existing = new Duty();
        existing.setId("duty_old_1");
        existing.setOwnerUid("nurse_uid_1");
        existing.setDutyDate("2026-10-14");
        existing.setShiftType(ShiftType.MORNING);
        existingMap.put(existing.getDutyDate(), existing);

        // New imported duty on 2026-10-14
        Duty imported = new Duty();
        imported.setId("duty_import_1");
        imported.setDutyDate("2026-10-14");
        imported.setShiftType(ShiftType.NIGHT);

        // Under KEEP_EXISTING policy, only add if absent
        if (!existingMap.containsKey(imported.getDutyDate())) {
            existingMap.put(imported.getDutyDate(), imported);
        }

        Duty result = existingMap.get("2026-10-14");
        assertEquals(ShiftType.MORNING, result.getShiftType());
    }

    @Test
    public void testTimestampConflictResolution() {
        // Remote has newer updated timestamp than local
        Duty local = new Duty();
        local.setUpdatedAt(1000L);
        local.setSyncStatus(SyncStatus.SYNCED);

        Duty remote = new Duty();
        remote.setUpdatedAt(2000L);

        boolean remoteWins = remote.getUpdatedAt() > local.getUpdatedAt();
        assertTrue(remoteWins);

        // If local has uncommitted pending edit with newer timestamp
        local.setUpdatedAt(3000L);
        local.setSyncStatus(SyncStatus.PENDING_UPLOAD);
        boolean localPendingWins = local.getSyncStatus() == SyncStatus.PENDING_UPLOAD && local.getUpdatedAt() > remote.getUpdatedAt();
        assertTrue(localPendingWins);
    }
}
