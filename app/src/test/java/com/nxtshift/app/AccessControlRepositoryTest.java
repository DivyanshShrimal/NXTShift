package com.nxtshift.app;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.nxtshift.app.data.local.DutyDao;
import com.nxtshift.app.data.local.DutyEntity;
import com.nxtshift.app.data.model.AccessState;
import com.nxtshift.app.data.model.GlobalAccessConfig;
import com.nxtshift.app.data.model.UserAccessRecord;
import com.nxtshift.app.data.remote.FirestoreService;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.SnapshotMetadata;
import com.nxtshift.app.data.local.StaffDao;
import com.nxtshift.app.data.local.StaffMemberEntity;
import com.nxtshift.app.data.model.MonthlyRoster;
import com.nxtshift.app.data.remote.CentralRosterService;
import com.nxtshift.app.data.repository.AccessControlRepository;
import com.nxtshift.app.data.repository.DutyRepository;
import com.nxtshift.app.data.repository.StaffRepository;
import com.nxtshift.app.domain.Duty;
import com.nxtshift.app.domain.DutyStatus;
import com.nxtshift.app.domain.ShiftType;
import com.nxtshift.app.ui.calendar.CalendarDayAdapter;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit test suite for Remote Access Control, evaluating:
 * 1. Global access enabled & disabled states
 * 2. Individual user revocation and restoration
 * 3. Fail-closed behavior on missing or malformed control documents
 * 4. Offline calendar availability and cloud operation gating
 * 5. Reconnection and state revalidation
 */
public class AccessControlRepositoryTest {

    private FirebaseFirestore mockDb;
    private FirebaseAuth mockAuth;
    private AccessControlRepository repository;

    private static final String TEST_UID_1 = "test_user_nurse_yamini";
    private static final String TEST_UID_2 = "test_user_nurse_priya";

    @Before
    public void setUp() {
        mockDb = mock(FirebaseFirestore.class);
        mockAuth = mock(FirebaseAuth.class);
        repository = new AccessControlRepository(mockDb, mockAuth);
    }

    @Test
    public void testGlobalAccessConfig_SerializationAndDefaults() {
        GlobalAccessConfig config = new GlobalAccessConfig(true, "All systems nominal", 1000L, "admin_1", 1);
        Map<String, Object> map = config.toMap();

        assertEquals(true, map.get("enabled"));
        assertEquals("All systems nominal", map.get("message"));
        assertEquals(1000L, map.get("updatedAt"));
        assertEquals("admin_1", map.get("updatedByUid"));
        assertEquals(1, map.get("minimumSupportedVersion"));

        GlobalAccessConfig deserialized = GlobalAccessConfig.fromMap(map);
        assertNotNull(deserialized);
        assertTrue(deserialized.isEnabled());
        assertEquals("All systems nominal", deserialized.getMessage());
        assertEquals(1000L, deserialized.getUpdatedAt());
        assertEquals("admin_1", deserialized.getUpdatedByUid());
        assertEquals(1, deserialized.getMinimumSupportedVersion());
    }

    @Test
    public void testGlobalAccessConfig_MalformedData_FailsClosed() {
        Map<String, Object> malformed = new HashMap<>();
        malformed.put("enabled", "not_a_boolean"); // invalid type

        GlobalAccessConfig config = GlobalAccessConfig.fromMap(malformed);
        assertNotNull(config);
        assertFalse("Malformed enabled field must fail closed to false", config.isEnabled());
    }

    @Test
    public void testUserAccessRecord_SerializationAndDefaults() {
        UserAccessRecord record = new UserAccessRecord(TEST_UID_1, false, "Security audit pending", 2000L, "admin_1");
        Map<String, Object> map = record.toMap();

        assertEquals(TEST_UID_1, map.get("uid"));
        assertEquals(false, map.get("enabled"));
        assertEquals("Security audit pending", map.get("reason"));

        UserAccessRecord deserialized = UserAccessRecord.fromMap(TEST_UID_1, map);
        assertNotNull(deserialized);
        assertEquals(TEST_UID_1, deserialized.getUid());
        assertFalse(deserialized.isEnabled());
        assertEquals("Security audit pending", deserialized.getReason());
    }

    @Test
    public void testUserAccessRecord_MalformedData_FailsClosed() {
        Map<String, Object> malformed = new HashMap<>();
        malformed.put("enabled", 12345); // invalid type

        UserAccessRecord record = UserAccessRecord.fromMap(TEST_UID_1, malformed);
        assertNotNull(record);
        assertFalse("Malformed user enabled field must fail closed to false", record.isEnabled());
    }

    @Test
    public void testInitialState_IsOfflineUnverified() {
        assertEquals("Initial state before server confirmation must be OFFLINE_UNVERIFIED",
                AccessState.OFFLINE_UNVERIFIED, repository.getCurrentAccessState());
    }

    @Test
    public void testCheckCloudAccess_WhenAllowed() {
        repository.setAccessStateForTesting(AccessState.ALLOWED);
        AtomicBoolean allowedCalled = new AtomicBoolean(false);
        AtomicBoolean blockedCalled = new AtomicBoolean(false);

        repository.checkCloudAccess(new AccessControlRepository.AccessCheckCallback() {
            @Override
            public void onAllowed() {
                allowedCalled.set(true);
            }

            @Override
            public void onBlocked(AccessState state, String message) {
                blockedCalled.set(true);
            }
        });

        assertTrue(allowedCalled.get());
        assertFalse(blockedCalled.get());
    }

    @Test
    public void testCheckCloudAccess_WhenGlobalDisabled_BlocksWithMaintenanceMessage() {
        // Simulate global disabled state
        GlobalAccessConfig disabledConfig = new GlobalAccessConfig(false, "Server maintenance in progress", 1000L, "admin_1", 1);
        AccessControlRepository testRepo = new AccessControlRepository(mockDb, mockAuth);
        testRepo.setGlobalConfigForTesting(disabledConfig);
        testRepo.setAccessStateForTesting(AccessState.GLOBAL_DISABLED);

        AtomicBoolean allowedCalled = new AtomicBoolean(false);
        AtomicReference<AccessState> blockedState = new AtomicReference<>();
        AtomicReference<String> blockedMsg = new AtomicReference<>();

        testRepo.checkCloudAccess(new AccessControlRepository.AccessCheckCallback() {
            @Override
            public void onAllowed() {
                allowedCalled.set(true);
            }

            @Override
            public void onBlocked(AccessState state, String message) {
                blockedState.set(state);
                blockedMsg.set(message);
            }
        });

        assertFalse(allowedCalled.get());
        assertEquals(AccessState.GLOBAL_DISABLED, blockedState.get());
        assertEquals("Server maintenance in progress", blockedMsg.get());
    }

    @Test
    public void testCheckCloudAccess_WhenUserRevoked_BlocksWithRevocationReason() {
        UserAccessRecord revokedRecord = new UserAccessRecord(TEST_UID_1, false, "Account flagged by admin", 1000L, "admin_1");
        AccessControlRepository testRepo = new AccessControlRepository(mockDb, mockAuth);
        testRepo.setUserAccessForTesting(revokedRecord);
        testRepo.setAccessStateForTesting(AccessState.USER_REVOKED);

        AtomicBoolean allowedCalled = new AtomicBoolean(false);
        AtomicReference<AccessState> blockedState = new AtomicReference<>();
        AtomicReference<String> blockedMsg = new AtomicReference<>();

        testRepo.checkCloudAccess(new AccessControlRepository.AccessCheckCallback() {
            @Override
            public void onAllowed() {
                allowedCalled.set(true);
            }

            @Override
            public void onBlocked(AccessState state, String message) {
                blockedState.set(state);
                blockedMsg.set(message);
            }
        });

        assertFalse(allowedCalled.get());
        assertEquals(AccessState.USER_REVOKED, blockedState.get());
        assertEquals("Account flagged by admin", blockedMsg.get());
    }

    @Test
    public void testCheckCloudAccess_WhenOffline_BlocksWithOfflineNotice() {
        AccessControlRepository testRepo = new AccessControlRepository(mockDb, mockAuth);
        testRepo.setAccessStateForTesting(AccessState.OFFLINE_UNVERIFIED);

        AtomicBoolean allowedCalled = new AtomicBoolean(false);
        AtomicReference<AccessState> blockedState = new AtomicReference<>();

        testRepo.checkCloudAccess(new AccessControlRepository.AccessCheckCallback() {
            @Override
            public void onAllowed() {
                allowedCalled.set(true);
            }

            @Override
            public void onBlocked(AccessState state, String message) {
                blockedState.set(state);
            }
        });

        assertFalse(allowedCalled.get());
        assertEquals(AccessState.OFFLINE_UNVERIFIED, blockedState.get());
    }

    @Test
    public void testOfflineCalendarPolicy_LocalCalendarOperatesOffline() {
        // Create an offline duty in local domain
        Duty offlineDuty = new Duty();
        offlineDuty.setId("duty_offline_1");
        offlineDuty.setDutyDate("2026-10-20");
        offlineDuty.setShiftType(ShiftType.MORNING);
        offlineDuty.setStatus(DutyStatus.SCHEDULED);
        offlineDuty.setOwnerUid(TEST_UID_1);

        // Calendar Day resolution works purely in-memory / from local Room without cloud dependencies
        CalendarDayAdapter.DayVisualResolution resolution =
                CalendarDayAdapter.resolveDayDuties(Collections.singletonList(offlineDuty));

        assertEquals(CalendarDayAdapter.DayDutyVisualState.WORKING, resolution.state);
        assertEquals(offlineDuty, resolution.primaryDuty);
    }

    @Test
    public void testDutyRepository_SyncBlockedWhenAccessRevoked() {
        DutyDao mockDutyDao = mock(DutyDao.class);
        FirestoreService mockFirestoreService = mock(FirestoreService.class);

        AccessControlRepository revokedRepo = new AccessControlRepository(mockDb, mockAuth) {
            @Override
            public void checkCloudAccess(AccessCheckCallback callback) {
                callback.onBlocked(AccessState.USER_REVOKED, "Revoked by admin");
            }
        };

        DutyRepository dutyRepository = new DutyRepository(mockDutyDao, mockFirestoreService, revokedRepo);

        AtomicBoolean errorReported = new AtomicBoolean(false);
        dutyRepository.syncWithCloud(TEST_UID_1, new DutyRepository.RepositoryCallback<Boolean>() {
            @Override
            public void onComplete(Boolean result) {
                fail("Sync must not complete when access is revoked");
            }

            @Override
            public void onError(Exception e) {
                errorReported.set(true);
                assertTrue("Expected SecurityException on blocked sync", e instanceof SecurityException);
            }
        });

        assertTrue("Error callback must be invoked when sync is blocked", errorReported.get());
        // Verify Firestore was NEVER called
        verifyNoInteractions(mockFirestoreService);
    }

    @Test
    public void testDutyRepository_ListeningSuspendedWhenAccessRevoked() {
        DutyDao mockDutyDao = mock(DutyDao.class);
        FirestoreService mockFirestoreService = mock(FirestoreService.class);

        AccessControlRepository revokedRepo = new AccessControlRepository(mockDb, mockAuth) {
            @Override
            public synchronized AccessState getCurrentAccessState() {
                return AccessState.USER_REVOKED;
            }
        };

        DutyRepository dutyRepository = new DutyRepository(mockDutyDao, mockFirestoreService, revokedRepo);
        dutyRepository.startListeningToCloudUpdates(TEST_UID_1);

        // Firestore listener should NOT be attached
        verify(mockFirestoreService, never()).listenToDuties(anyString(), any());
    }

    @Test
    public void testDutyRepository_ListeningStartsWhenAccessAllowed() {
        DutyDao mockDutyDao = mock(DutyDao.class);
        FirestoreService mockFirestoreService = mock(FirestoreService.class);

        AccessControlRepository allowedRepo = new AccessControlRepository(mockDb, mockAuth) {
            @Override
            public synchronized AccessState getCurrentAccessState() {
                return AccessState.ALLOWED;
            }
        };

        DutyRepository dutyRepository = new DutyRepository(mockDutyDao, mockFirestoreService, allowedRepo);
        dutyRepository.startListeningToCloudUpdates(TEST_UID_1);

        verify(mockFirestoreService, times(1)).listenToDuties(eq(TEST_UID_1), any());
    }

    @Test
    public void testSnapshotFromCache_DoesNotSetAllowed() {
        DocumentSnapshot mockGlobalDoc = mock(DocumentSnapshot.class);
        SnapshotMetadata mockMeta = mock(SnapshotMetadata.class);
        when(mockMeta.isFromCache()).thenReturn(true);
        when(mockGlobalDoc.getMetadata()).thenReturn(mockMeta);
        when(mockGlobalDoc.exists()).thenReturn(true);
        Map<String, Object> globalData = new HashMap<>();
        globalData.put("enabled", true);
        when(mockGlobalDoc.getData()).thenReturn(globalData);

        DocumentSnapshot mockUserDoc = mock(DocumentSnapshot.class);
        when(mockUserDoc.getMetadata()).thenReturn(mockMeta);
        when(mockUserDoc.exists()).thenReturn(true);
        Map<String, Object> userData = new HashMap<>();
        userData.put("enabled", true);
        when(mockUserDoc.getData()).thenReturn(userData);

        repository.handleGlobalSnapshot(mockGlobalDoc);
        repository.handleUserSnapshot(TEST_UID_1, mockUserDoc);

        // Cached snapshots must NEVER produce ALLOWED; must remain OFFLINE_UNVERIFIED
        assertEquals("Cached snapshots must not result in ALLOWED state",
                AccessState.OFFLINE_UNVERIFIED, repository.getCurrentAccessState());
        assertFalse("Server confirmation must be false when snapshots are from cache",
                repository.isServerConfirmed());
    }

    @Test
    public void testServerConfirmedSnapshots_SetsAllowed() {
        DocumentSnapshot mockGlobalDoc = mock(DocumentSnapshot.class);
        SnapshotMetadata mockServerMeta = mock(SnapshotMetadata.class);
        when(mockServerMeta.isFromCache()).thenReturn(false);
        when(mockGlobalDoc.getMetadata()).thenReturn(mockServerMeta);
        when(mockGlobalDoc.exists()).thenReturn(true);
        Map<String, Object> globalData = new HashMap<>();
        globalData.put("enabled", true);
        when(mockGlobalDoc.getData()).thenReturn(globalData);

        DocumentSnapshot mockUserDoc = mock(DocumentSnapshot.class);
        when(mockUserDoc.getMetadata()).thenReturn(mockServerMeta);
        when(mockUserDoc.exists()).thenReturn(true);
        Map<String, Object> userData = new HashMap<>();
        userData.put("enabled", true);
        when(mockUserDoc.getData()).thenReturn(userData);

        repository.handleGlobalSnapshot(mockGlobalDoc);
        repository.handleUserSnapshot(TEST_UID_1, mockUserDoc);

        // Server confirmed snapshots with enabled == true must set state to ALLOWED
        assertEquals("Server confirmed snapshots must result in ALLOWED state",
                AccessState.ALLOWED, repository.getCurrentAccessState());
        assertTrue("Server confirmation must be true", repository.isServerConfirmed());
    }

    @Test
    public void testCachedGlobalDisabled_ImmediatelyBlocks() {
        DocumentSnapshot mockGlobalDoc = mock(DocumentSnapshot.class);
        SnapshotMetadata mockMeta = mock(SnapshotMetadata.class);
        when(mockMeta.isFromCache()).thenReturn(true);
        when(mockGlobalDoc.getMetadata()).thenReturn(mockMeta);
        when(mockGlobalDoc.exists()).thenReturn(true);
        Map<String, Object> globalData = new HashMap<>();
        globalData.put("enabled", false);
        globalData.put("message", "Maintenance active");
        when(mockGlobalDoc.getData()).thenReturn(globalData);

        repository.handleGlobalSnapshot(mockGlobalDoc);

        assertEquals("Cached global disable must immediately result in GLOBAL_DISABLED",
                AccessState.GLOBAL_DISABLED, repository.getCurrentAccessState());
    }

    @Test
    public void testCachedUserRevoked_ImmediatelyBlocks() {
        DocumentSnapshot mockUserDoc = mock(DocumentSnapshot.class);
        SnapshotMetadata mockMeta = mock(SnapshotMetadata.class);
        when(mockMeta.isFromCache()).thenReturn(true);
        when(mockUserDoc.getMetadata()).thenReturn(mockMeta);
        when(mockUserDoc.exists()).thenReturn(true);
        Map<String, Object> userData = new HashMap<>();
        userData.put("enabled", false);
        userData.put("reason", "Revoked account");
        when(mockUserDoc.getData()).thenReturn(userData);

        repository.handleUserSnapshot(TEST_UID_1, mockUserDoc);

        assertEquals("Cached user revocation must immediately result in USER_REVOKED",
                AccessState.USER_REVOKED, repository.getCurrentAccessState());
    }

    @Test
    public void testDutyRepository_RosterImportBlockedWhenRevoked() {
        DutyDao mockDutyDao = mock(DutyDao.class);
        FirestoreService mockFirestoreService = mock(FirestoreService.class);

        AccessControlRepository revokedRepo = new AccessControlRepository(mockDb, mockAuth) {
            @Override
            public void checkCloudAccess(AccessCheckCallback callback) {
                callback.onBlocked(AccessState.USER_REVOKED, "Revoked by admin");
            }
        };

        DutyRepository dutyRepository = new DutyRepository(mockDutyDao, mockFirestoreService, revokedRepo, Runnable::run, null);

        Duty importedDuty = new Duty();
        importedDuty.setId("import_1");
        importedDuty.setDutyDate("2026-10-25");
        importedDuty.setShiftType(ShiftType.MORNING);
        importedDuty.setOwnerUid(TEST_UID_1);

        AtomicBoolean completed = new AtomicBoolean(false);
        dutyRepository.importRosterDutiesDetailed(
                TEST_UID_1,
                Collections.singletonList(importedDuty),
                DutyRepository.ImportConflictPolicy.MERGE_REPLACE_CONFLICTS,
                "2026-10-01",
                "2026-10-31",
                new DutyRepository.RepositoryCallback<DutyRepository.BulkImportResult>() {
                    @Override
                    public void onComplete(DutyRepository.BulkImportResult result) {
                        completed.set(true);
                    }

                    @Override
                    public void onError(Exception e) {
                        fail("Local import should succeed even if cloud upload is blocked");
                    }
                }
        );

        assertTrue("Import must complete synchronously with direct executor", completed.get());
        // Local Room insert must succeed
        verify(mockDutyDao, times(1)).insertOrReplaceAll(anyList());
        // Firestore cloud upload must NEVER be invoked
        verify(mockFirestoreService, never()).uploadDutiesBatch(anyList(), any());
    }

    @Test
    public void testStaffRepository_StaffSyncBlockedWhenRevoked() {
        StaffDao mockStaffDao = mock(StaffDao.class);
        FirestoreService mockFirestoreService = mock(FirestoreService.class);

        AccessControlRepository revokedRepo = new AccessControlRepository(mockDb, mockAuth) {
            @Override
            public void checkCloudAccess(AccessCheckCallback callback) {
                callback.onBlocked(AccessState.USER_REVOKED, "Revoked by admin");
            }
        };

        StaffRepository staffRepository = new StaffRepository(mockStaffDao, mockFirestoreService, revokedRepo, Runnable::run, null);

        StaffMemberEntity discovered = new StaffMemberEntity(
                "staff_1", TEST_UID_1, "Sister Mary", "sistermary", "", "Cardiology", "Nurse", 1, 1000L, 1000L, "PENDING"
        );

        AtomicBoolean staffSaved = new AtomicBoolean(false);
        staffRepository.saveOrMergeDiscoveredStaff(TEST_UID_1, Collections.singletonList(discovered), new StaffRepository.StaffCallback<Integer>() {
            @Override
            public void onComplete(Integer result) {
                assertEquals(Integer.valueOf(1), result);
                staffSaved.set(true);
            }

            @Override
            public void onError(Exception e) {
                fail("Local staff save should succeed even if cloud upload is blocked");
            }
        });

        assertTrue("Staff save must complete synchronously with direct executor", staffSaved.get());
        // Room save is invoked
        verify(mockStaffDao, times(1)).insertOrReplaceAll(anyList());
        // Cloud batch upload is NEVER invoked
        verify(mockFirestoreService, never()).uploadStaffBatch(anyString(), anyList(), any());
    }

    @Test
    public void testCentralRosterService_SaveDraft_FailsClosedWhenAccessBlocked() {
        AccessControlRepository blockedRepo = new AccessControlRepository(mockDb, mockAuth) {
            @Override
            public void checkCloudAccess(AccessCheckCallback callback) {
                callback.onBlocked(AccessState.GLOBAL_DISABLED, "Maintenance mode active");
            }
        };

        com.google.firebase.auth.FirebaseUser mockUser = mock(com.google.firebase.auth.FirebaseUser.class);
        when(mockUser.getUid()).thenReturn("admin_uid_1");
        when(mockAuth.getCurrentUser()).thenReturn(mockUser);

        CentralRosterService service = new CentralRosterService(mockDb, mockAuth, blockedRepo);
        MonthlyRoster draft = new MonthlyRoster();
        draft.setYear(2026);
        draft.setMonth(11);

        AtomicBoolean errorReported = new AtomicBoolean(false);
        service.saveDraftRoster(draft, new CentralRosterService.Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                fail("Draft save must fail when access is blocked");
            }

            @Override
            public void onError(Exception e) {
                errorReported.set(true);
                assertTrue(e instanceof SecurityException);
            }
        });

        assertTrue("Error must be reported to callback", errorReported.get());
    }

    @Test
    public void testCentralRosterService_Publish_FailsClosedWhenAccessBlocked() {
        AccessControlRepository blockedRepo = new AccessControlRepository(mockDb, mockAuth) {
            @Override
            public void checkCloudAccess(AccessCheckCallback callback) {
                callback.onBlocked(AccessState.USER_REVOKED, "Admin user revoked");
            }
        };

        com.google.firebase.auth.FirebaseUser mockUser = mock(com.google.firebase.auth.FirebaseUser.class);
        when(mockUser.getUid()).thenReturn("admin_uid_1");
        when(mockAuth.getCurrentUser()).thenReturn(mockUser);

        CentralRosterService service = new CentralRosterService(mockDb, mockAuth, blockedRepo);
        MonthlyRoster roster = new MonthlyRoster();
        roster.setYear(2026);
        roster.setMonth(11);

        AtomicBoolean errorReported = new AtomicBoolean(false);
        service.publishRoster(roster, "admin_uid_1", "admin@hospital.org", new CentralRosterService.Callback<Integer>() {
            @Override
            public void onSuccess(Integer result) {
                fail("Publication must fail when user is revoked");
            }

            @Override
            public void onError(Exception e) {
                errorReported.set(true);
                assertTrue(e instanceof SecurityException);
            }
        });

        assertTrue("Error must be reported to callback", errorReported.get());
    }
}
