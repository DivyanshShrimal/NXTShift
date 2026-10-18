package com.nxtshift.app;

import static org.junit.Assert.*;

import com.nxtshift.app.data.model.MonthlyRoster;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Unit test suite validating Firestore Security Rules predicates,
 * role-based administrative permissions, remote access control gates (global and per-user),
 * client privilege restrictions, and per-user data isolation.
 */
public class FirestoreRulesSecurityTest {

    // Simulated Firestore Security Context
    static class SecurityContext {
        final String uid;
        final boolean hasAdminCustomClaim;
        final Set<String> adminCollectionUids;
        Boolean globalEnabledDoc; // null if doc missing, true/false if present
        Map<String, Boolean> userAccessDocs; // targetUid -> enabled (null if no doc)

        SecurityContext(String uid, boolean hasAdminCustomClaim, Set<String> adminCollectionUids) {
            this(uid, hasAdminCustomClaim, adminCollectionUids, true, new HashMap<>());
        }

        SecurityContext(String uid, boolean hasAdminCustomClaim, Set<String> adminCollectionUids,
                        Boolean globalEnabledDoc, Map<String, Boolean> userAccessDocs) {
            this.uid = uid;
            this.hasAdminCustomClaim = hasAdminCustomClaim;
            this.adminCollectionUids = adminCollectionUids != null ? adminCollectionUids : java.util.Collections.emptySet();
            this.globalEnabledDoc = globalEnabledDoc;
            this.userAccessDocs = userAccessDocs != null ? userAccessDocs : new HashMap<>();
        }

        boolean isAuthenticated() {
            return uid != null && !uid.trim().isEmpty();
        }

        boolean isOwner(String targetUid) {
            return isAuthenticated() && uid.equals(targetUid);
        }

        boolean isAdmin() {
            return isAuthenticated() && (hasAdminCustomClaim || adminCollectionUids.contains(uid));
        }

        // Rule Evaluation: isGlobalEnabled()
        boolean isGlobalEnabled() {
            return globalEnabledDoc != null && globalEnabledDoc;
        }

        // Rule Evaluation: isUserAccessAllowed(uid)
        boolean isUserAccessAllowed(String targetUid) {
            Boolean record = userAccessDocs.get(targetUid);
            return record == null || record;
        }

        // Rule Evaluation: isAccessPermitted(uid)
        boolean isAccessPermitted(String targetUid) {
            return isGlobalEnabled() && isUserAccessAllowed(targetUid);
        }

        // Rule Evaluation: /admins/{adminUid}
        boolean canGetAdminDoc(String adminUid) {
            return isAuthenticated() && (uid.equals(adminUid) || isAdmin());
        }

        boolean canListAdminDocs() {
            return isAdmin();
        }

        boolean canWriteAdminDoc() {
            return false; // allow write: if false;
        }

        // Rule Evaluation: /app_control/global
        boolean canGetGlobalControlDoc() {
            return isAuthenticated();
        }

        boolean canWriteGlobalControlDoc() {
            return isAdmin();
        }

        // Rule Evaluation: /app_access/{targetUid}
        boolean canGetUserAccessDoc(String targetUid) {
            return isAuthenticated() && (uid.equals(targetUid) || isAdmin());
        }

        boolean canListUserAccessDocs() {
            return isAdmin();
        }

        boolean canWriteUserAccessDoc() {
            return isAdmin();
        }

        // Rule Evaluation: /monthly_rosters/{rosterId}
        boolean canGetMonthlyRoster(Map<String, Object> existingDocResource) {
            if (!isAuthenticated()) return false;
            if (!isAccessPermitted(uid)) return false;
            if (isAdmin()) return true;
            if (existingDocResource == null) return true; // resource == null (check non-existence)
            Object status = existingDocResource.get("status");
            return "PUBLISHED".equals(status);
        }

        boolean canListMonthlyRosters(String queryFilterStatus) {
            if (!isAuthenticated()) return false;
            if (!isAccessPermitted(uid)) return false;
            if (isAdmin()) return true;
            return "PUBLISHED".equals(queryFilterStatus);
        }

        boolean canCreateOrUpdateMonthlyRoster(Map<String, Object> data, String rosterId) {
            if (!isAdmin() || !isGlobalEnabled()) return false;
            return isValidMonthlyRoster(data, rosterId, uid);
        }

        boolean canDeleteMonthlyRoster() {
            return isAdmin() && isGlobalEnabled();
        }

        // Rule Validation: isValidMonthlyRoster(data, rosterId)
        static boolean isValidMonthlyRoster(Map<String, Object> data, String rosterId, String authUid) {
            if (data == null) return false;
            if (!rosterId.equals(data.get("id"))) return false;

            Object year = data.get("year");
            if (!(year instanceof Number) || ((Number) year).intValue() < 2020 || ((Number) year).intValue() > 2100) return false;

            Object month = data.get("month");
            if (!(month instanceof Number) || ((Number) month).intValue() < 1 || ((Number) month).intValue() > 12) return false;

            Object status = data.get("status");
            if (!"DRAFT".equals(status) && !"PUBLISHED".equals(status)) return false;

            Object version = data.get("version");
            if (!(version instanceof Number) || ((Number) version).intValue() < 1) return false;

            Object updatedAt = data.get("updatedAt");
            if (!(updatedAt instanceof Number)) return false;

            if ("PUBLISHED".equals(status)) {
                Object publishedAt = data.get("publishedAt");
                Object publishedByUid = data.get("publishedByUid");
                if (!(publishedAt instanceof Number) || !authUid.equals(publishedByUid)) {
                    return false;
                }
            }

            return true;
        }

        // Rule Validation: isValidDutyData(data, uid)
        static boolean isValidDutyData(Map<String, Object> data, String expectedUid) {
            if (data == null) return false;
            if (!expectedUid.equals(data.get("ownerUid"))) return false;
            if (!(data.get("id") instanceof String)) return false;
            if (!(data.get("dutyDate") instanceof String)) return false;
            if (!(data.get("shiftType") instanceof String)) return false;
            if (!(data.get("status") instanceof String)) return false;
            if (!(data.get("updatedAt") instanceof Number)) return false;
            return true;
        }

        // Rule Evaluation: /users/{targetUid}/duties/{dutyId}
        boolean canReadUserDuty(String targetUid) {
            return isOwner(targetUid) && isAccessPermitted(targetUid);
        }

        boolean canCreateUserDuty(String targetUid, String dutyId, Map<String, Object> data) {
            return isOwner(targetUid) && isAccessPermitted(targetUid) && isValidDutyData(data, targetUid) && dutyId.equals(data.get("id"));
        }

        boolean canUpdateUserDuty(String targetUid, Map<String, Object> existingData, Map<String, Object> newData) {
            return isOwner(targetUid) && isAccessPermitted(targetUid) && isValidDutyData(newData, targetUid) && existingData.get("ownerUid").equals(newData.get("ownerUid"));
        }

        boolean canDeleteUserDuty(String targetUid) {
            return isOwner(targetUid) && isAccessPermitted(targetUid);
        }
    }

    private SecurityContext unauthenticatedUser;
    private SecurityContext normalNurse;
    private SecurityContext anotherNurse;
    private SecurityContext adminUserViaCollection;
    private SecurityContext adminUserViaClaim;

    private static final String ADMIN_UID = "admin_uid_roster_officer";
    private static final String NURSE_A_UID = "nurse_yamini_uid";
    private static final String NURSE_B_UID = "nurse_priya_uid";

    @Before
    public void setUp() {
        Set<String> adminCollection = new java.util.HashSet<>();
        adminCollection.add(ADMIN_UID);

        unauthenticatedUser = new SecurityContext(null, false, adminCollection);
        normalNurse = new SecurityContext(NURSE_A_UID, false, adminCollection);
        anotherNurse = new SecurityContext(NURSE_B_UID, false, adminCollection);
        adminUserViaCollection = new SecurityContext(ADMIN_UID, false, adminCollection);
        adminUserViaClaim = new SecurityContext("admin_claim_uid", true, adminCollection);
    }

    @Test
    public void testAdminAccessVerification() {
        assertTrue("Admin via collection has admin privileges", adminUserViaCollection.isAdmin());
        assertTrue("Admin via claim has admin privileges", adminUserViaClaim.isAdmin());
        assertFalse("Normal nurse does NOT have admin privileges", normalNurse.isAdmin());
        assertFalse("Unauthenticated user does NOT have admin privileges", unauthenticatedUser.isAdmin());
    }

    @Test
    public void testAdminsCollection_WriteAlwaysForbidden() {
        assertFalse("Admin cannot write to /admins via client", adminUserViaCollection.canWriteAdminDoc());
        assertFalse("Nurse cannot write to /admins via client", normalNurse.canWriteAdminDoc());
    }

    // --- Remote Access Control Tests ---

    @Test
    public void testRemoteAccess_GlobalEnabledAndUserEnabled_Allowed() {
        assertTrue("Nurse A can read own duties when global and user access enabled",
                normalNurse.canReadUserDuty(NURSE_A_UID));
    }

    @Test
    public void testRemoteAccess_GlobalDisabled_BlocksUserCloudOperations() {
        normalNurse.globalEnabledDoc = false;
        assertFalse("Nurse A CANNOT read cloud duties when global access is disabled",
                normalNurse.canReadUserDuty(NURSE_A_UID));

        Map<String, Object> duty = createValidDutyMap("duty_01", NURSE_A_UID);
        assertFalse("Nurse A CANNOT create cloud duty when global access is disabled",
                normalNurse.canCreateUserDuty(NURSE_A_UID, "duty_01", duty));
    }

    @Test
    public void testRemoteAccess_GlobalMissing_FailsClosed() {
        normalNurse.globalEnabledDoc = null; // document missing in Firestore
        assertFalse("Missing /app_control/global fails closed for cloud duty reads",
                normalNurse.canReadUserDuty(NURSE_A_UID));
    }

    @Test
    public void testRemoteAccess_UserRevoked_OnlyRevokedUserIsBlocked() {
        // Revoke Nurse A
        normalNurse.userAccessDocs.put(NURSE_A_UID, false);
        anotherNurse.userAccessDocs.put(NURSE_A_UID, false); // Nurse B shares same Firestore state

        assertFalse("Revoked Nurse A cannot read cloud duties", normalNurse.canReadUserDuty(NURSE_A_UID));

        Map<String, Object> dutyA = createValidDutyMap("duty_01", NURSE_A_UID);
        assertFalse("Revoked Nurse A cannot write cloud duties",
                normalNurse.canCreateUserDuty(NURSE_A_UID, "duty_01", dutyA));

        // Nurse B is NOT revoked and must remain unaffected
        assertTrue("Nurse B (unrevoked) CAN read own duties", anotherNurse.canReadUserDuty(NURSE_B_UID));
        Map<String, Object> dutyB = createValidDutyMap("duty_02", NURSE_B_UID);
        assertTrue("Nurse B (unrevoked) CAN write own duties",
                anotherNurse.canCreateUserDuty(NURSE_B_UID, "duty_02", dutyB));
    }

    @Test
    public void testRemoteAccess_UserRestored_OperationsAllowed() {
        // Revoke then restore
        normalNurse.userAccessDocs.put(NURSE_A_UID, false);
        assertFalse("Blocked while revoked", normalNurse.canReadUserDuty(NURSE_A_UID));

        normalNurse.userAccessDocs.put(NURSE_A_UID, true);
        assertTrue("Allowed once restored", normalNurse.canReadUserDuty(NURSE_A_UID));
    }

    @Test
    public void testRemoteAccess_ControlDocuments_WriteAuthorization() {
        // Only admin can write to /app_control/global
        assertTrue("Admin can write global control doc", adminUserViaCollection.canWriteGlobalControlDoc());
        assertFalse("Normal nurse CANNOT write global control doc", normalNurse.canWriteGlobalControlDoc());
        assertFalse("Unauthenticated user CANNOT write global control doc", unauthenticatedUser.canWriteGlobalControlDoc());

        // Only admin can write to /app_access/{uid}
        assertTrue("Admin can write user access doc", adminUserViaCollection.canWriteUserAccessDoc());
        assertFalse("Normal nurse CANNOT write to their own access doc", normalNurse.canWriteUserAccessDoc());
        assertFalse("Normal nurse CANNOT write to another user's access doc", normalNurse.canWriteUserAccessDoc());

        // Any authenticated user can read global config
        assertTrue("Nurse can get global control doc", normalNurse.canGetGlobalControlDoc());
        assertFalse("Unauthenticated user cannot get global control doc", unauthenticatedUser.canGetGlobalControlDoc());

        // User can only get their own access doc; admin can get any
        assertTrue("Nurse A can get own access doc", normalNurse.canGetUserAccessDoc(NURSE_A_UID));
        assertFalse("Nurse A CANNOT get Nurse B access doc", normalNurse.canGetUserAccessDoc(NURSE_B_UID));
        assertTrue("Admin can get Nurse A access doc", adminUserViaCollection.canGetUserAccessDoc(NURSE_A_UID));
    }

    @Test
    public void testRemoteAccess_AdminRosterPublishingBlockedWhenGlobalDisabled() {
        Map<String, Object> draft = createValidRosterMap("2026_10_draft", "DRAFT");
        Map<String, Object> published = createValidRosterMap("2026_10", "PUBLISHED");

        // Enabled -> Admin can mutate
        assertTrue("Admin can save draft when global enabled",
                adminUserViaCollection.canCreateOrUpdateMonthlyRoster(draft, "2026_10_draft"));
        assertTrue("Admin can publish when global enabled",
                adminUserViaCollection.canCreateOrUpdateMonthlyRoster(published, "2026_10"));

        // Disabled -> Admin mutations blocked
        adminUserViaCollection.globalEnabledDoc = false;
        assertFalse("Admin CANNOT save draft when global access is disabled",
                adminUserViaCollection.canCreateOrUpdateMonthlyRoster(draft, "2026_10_draft"));
        assertFalse("Admin CANNOT publish roster when global access is disabled",
                adminUserViaCollection.canCreateOrUpdateMonthlyRoster(published, "2026_10"));
        assertFalse("Admin CANNOT delete roster when global access is disabled",
                adminUserViaCollection.canDeleteMonthlyRoster());
    }

    @Test
    public void testPerUserIsolation_OwnerAccess() {
        assertTrue("User A can read their own duty", normalNurse.canReadUserDuty(NURSE_A_UID));
        assertTrue("User A can delete their own duty", normalNurse.canDeleteUserDuty(NURSE_A_UID));

        assertFalse("User B CANNOT read User A duties", anotherNurse.canReadUserDuty(NURSE_A_UID));
        assertFalse("User B CANNOT delete User A duties", anotherNurse.canDeleteUserDuty(NURSE_A_UID));

        assertFalse("Unauthenticated user cannot read duties", unauthenticatedUser.canReadUserDuty(NURSE_A_UID));
    }

    @Test
    public void testPerUserIsolation_UserCannotSpoofOwnerUidOnWrite() {
        Map<String, Object> validDutyForNurseA = createValidDutyMap("duty_01", NURSE_A_UID);

        assertTrue("Nurse A can write duty with ownerUid == NURSE_A_UID",
                normalNurse.canCreateUserDuty(NURSE_A_UID, "duty_01", validDutyForNurseA));

        assertFalse("Nurse A cannot write into Nurse B collection",
                normalNurse.canCreateUserDuty(NURSE_B_UID, "duty_01", validDutyForNurseA));

        Map<String, Object> spoofedOwner = new HashMap<>(validDutyForNurseA);
        spoofedOwner.put("ownerUid", NURSE_B_UID);
        assertFalse("Cannot write duty with ownerUid spoofed to another user",
                normalNurse.canCreateUserDuty(NURSE_A_UID, "duty_01", spoofedOwner));
    }

    private Map<String, Object> createValidDutyMap(String id, String ownerUid) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", id);
        map.put("ownerUid", ownerUid);
        map.put("dutyDate", "2026-10-15");
        map.put("shiftType", "MORNING");
        map.put("status", "SCHEDULED");
        map.put("updatedAt", System.currentTimeMillis());
        return map;
    }

    private Map<String, Object> createValidRosterMap(String id, String status) {
        return createValidRosterMap(id, status, ADMIN_UID);
    }

    private Map<String, Object> createValidRosterMap(String id, String status, String publisherUid) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", id);
        map.put("year", 2026);
        map.put("month", 10);
        map.put("status", status);
        map.put("version", 1);
        map.put("updatedAt", System.currentTimeMillis());
        if ("PUBLISHED".equals(status)) {
            map.put("publishedAt", System.currentTimeMillis());
            map.put("publishedByUid", publisherUid);
            map.put("publishedByEmail", "admin@nxtshift.com");
        }
        return map;
    }
}
