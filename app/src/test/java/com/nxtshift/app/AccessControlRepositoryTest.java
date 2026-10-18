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
import com.nxtshift.app.data.repository.AccessControlRepository;
import com.nxtshift.app.data.repository.DutyRepository;
import com.nxtshift.app.domain.Duty;
import com.nxtshift.app.domain.DutyStatus;
import com.nxtshift.app.domain.ShiftType;
import com.nxtshift.app.ui.calendar.CalendarDayAdapter;

import org.junit.Before;
import org.junit.Test;

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
    public void testCheckCloudAccess_WhenAllowed() {
        // Initial state is ALLOWED by default
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
}
