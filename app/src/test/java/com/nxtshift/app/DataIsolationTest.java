package com.nxtshift.app;

import static org.junit.Assert.*;

import com.nxtshift.app.data.local.DutyEntity;
import com.nxtshift.app.domain.Duty;
import com.nxtshift.app.domain.ShiftType;
import com.nxtshift.app.domain.SyncStatus;

import org.junit.Test;

public class DataIsolationTest {

    @Test
    public void testOwnerUidIsolationPreservedInLocalEntity() {
        String uidNurseA = "firebase_uid_nurse_shreya";
        String uidNurseB = "firebase_uid_nurse_priya";

        Duty dutyA = new Duty();
        dutyA.setOwnerUid(uidNurseA);
        dutyA.setDutyDate("2026-10-14");
        dutyA.setShiftType(ShiftType.MORNING);
        dutyA.setWardOrDepartment("ICU");

        Duty dutyB = new Duty();
        dutyB.setOwnerUid(uidNurseB);
        dutyB.setDutyDate("2026-10-14");
        dutyB.setShiftType(ShiftType.NIGHT);
        dutyB.setWardOrDepartment("Emergency");

        DutyEntity entityA = DutyEntity.fromDomain(dutyA, uidNurseA);
        DutyEntity entityB = DutyEntity.fromDomain(dutyB, uidNurseB);

        // Verify independent ownership
        assertEquals(uidNurseA, entityA.getOwnerUid());
        assertEquals(uidNurseB, entityB.getOwnerUid());
        assertNotEquals(entityA.getOwnerUid(), entityB.getOwnerUid());

        // Verify domain restoration preserves UID isolation
        Duty restoredA = entityA.toDomain();
        Duty restoredB = entityB.toDomain();
        assertEquals(uidNurseA, restoredA.getOwnerUid());
        assertEquals(uidNurseB, restoredB.getOwnerUid());
        assertEquals(ShiftType.MORNING, restoredA.getShiftType());
        assertEquals(ShiftType.NIGHT, restoredB.getShiftType());
    }

    @Test
    public void testSyncStatusHandlingOnEntityConversion() {
        Duty duty = new Duty();
        duty.setOwnerUid("nurse_1");
        duty.setSyncStatus(SyncStatus.PENDING_UPLOAD);

        DutyEntity entity = DutyEntity.fromDomain(duty, "nurse_1");
        assertEquals("PENDING_UPLOAD", entity.getSyncStatus());

        entity.setSyncStatus(SyncStatus.SYNCED.name());
        Duty synced = entity.toDomain();
        assertEquals(SyncStatus.SYNCED, synced.getSyncStatus());
    }
}
