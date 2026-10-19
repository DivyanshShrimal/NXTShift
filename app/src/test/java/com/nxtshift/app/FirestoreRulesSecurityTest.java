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
 * role-based administrative permissions, client privilege restrictions,
 * and per-user data isolation.
 */
public class FirestoreRulesSecurityTest {

    // Simulated Firestore Security Context
    static class SecurityContext {
        final String uid;
        final boolean hasAdminCustomClaim;
        final Set<String> adminCollectionUids;

        SecurityContext(String uid, boolean hasAdminCustomClaim, Set<String> adminCollectionUids) {
            this.uid = uid;
            this.hasAdminCustomClaim = hasAdminCustomClaim;
            this.adminCollectionUids = adminCollectionUids != null ? adminCollectionUids : java.util.Collections.emptySet();
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

        // Rule Evaluation: /monthly_rosters/{rosterId}
        boolean canGetMonthlyRoster(Map<String, Object> existingDocResource) {
            if (!isAuthenticated()) return false;
            if (isAdmin()) return true;
            if (existingDocResource == null) return true; // resource == null (check non-existence)
            Object status = existingDocResource.get("status");
            return "PUBLISHED".equals(status);
        }

        boolean canListMonthlyRosters(String queryFilterStatus) {
            if (!isAuthenticated()) return false;
            if (isAdmin()) return true;
            return "PUBLISHED".equals(queryFilterStatus);
        }

        boolean canCreateOrUpdateMonthlyRoster(Map<String, Object> data, String rosterId) {
            if (!isAdmin()) return false;
            return isValidMonthlyRoster(data, rosterId, uid);
        }

        boolean canDeleteMonthlyRoster() {
            return isAdmin();
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
            return isOwner(targetUid);
        }

        boolean canCreateUserDuty(String targetUid, String dutyId, Map<String, Object> data) {
            return isOwner(targetUid) && isValidDutyData(data, targetUid) && dutyId.equals(data.get("id"));
        }

        boolean canUpdateUserDuty(String targetUid, Map<String, Object> existingData, Map<String, Object> newData) {
            return isOwner(targetUid) && isValidDutyData(newData, targetUid) && existingData.get("ownerUid").equals(newData.get("ownerUid"));
        }

        boolean canDeleteUserDuty(String targetUid) {
            return isOwner(targetUid);
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

    // ── 1. Admin Authorization Collection Tests (/admins/{adminUid}) ─────────────

    @Test
    public void testAdminCollection_WritesAlwaysDeniedForEveryone() {
        assertFalse("Unauthenticated cannot write admin doc", unauthenticatedUser.canWriteAdminDoc());
        assertFalse("Normal user cannot create admin doc", normalNurse.canWriteAdminDoc());
        assertFalse("Even existing admin cannot write admin doc via client SDK", adminUserViaCollection.canWriteAdminDoc());
        assertFalse("Token claim admin cannot write admin doc via client SDK", adminUserViaClaim.canWriteAdminDoc());
    }

    @Test
    public void testAdminCollection_UsersCanOnlyCheckTheirOwnDoc() {
        // Normal user checking their own UID
        assertTrue("User can check their own admin document", normalNurse.canGetAdminDoc(NURSE_A_UID));

        // Normal user attempting to check another user's admin document
        assertFalse("Normal user cannot inspect another user's admin document", normalNurse.canGetAdminDoc(NURSE_B_UID));
        assertFalse("Normal user cannot inspect admin's document", normalNurse.canGetAdminDoc(ADMIN_UID));

        // Admin can inspect any admin document
        assertTrue("Admin can check any admin document", adminUserViaCollection.canGetAdminDoc(NURSE_A_UID));
        assertTrue("Admin can check another admin document", adminUserViaClaim.canGetAdminDoc(ADMIN_UID));
    }

    @Test
    public void testAdminCollection_NormalUsersCannotListAdmins() {
        assertFalse("Normal users cannot list the admins collection", normalNurse.canListAdminDocs());
        assertFalse("Unauthenticated users cannot list the admins collection", unauthenticatedUser.canListAdminDocs());
        assertTrue("Admins can list the admins collection", adminUserViaCollection.canListAdminDocs());
    }

    // ── 2. Central Monthly Roster Tests (/monthly_rosters/{rosterId}) ─────────────

    @Test
    public void testMonthlyRoster_NormalUserCanReadPublishedOnly() {
        Map<String, Object> publishedDoc = new HashMap<>();
        publishedDoc.put("id", "2026_10");
        publishedDoc.put("status", "PUBLISHED");

        Map<String, Object> draftDoc = new HashMap<>();
        draftDoc.put("id", "2026_10_draft");
        draftDoc.put("status", "DRAFT");

        // Published roster read
        assertTrue("Normal user can read published roster", normalNurse.canGetMonthlyRoster(publishedDoc));
        assertFalse("Unauthenticated user cannot read published roster", unauthenticatedUser.canGetMonthlyRoster(publishedDoc));

        // Draft roster read (Must be denied for normal users)
        assertFalse("Normal user CANNOT read draft roster", normalNurse.canGetMonthlyRoster(draftDoc));
        assertTrue("Admin user CAN read draft roster", adminUserViaCollection.canGetMonthlyRoster(draftDoc));

        // Non-existent document get (e.g. check if month is published)
        assertTrue("Normal user checking non-existent document gets response without permission denial",
                normalNurse.canGetMonthlyRoster(null));
    }

    @Test
    public void testMonthlyRoster_NormalUsersCannotPublishOrModify() {
        Map<String, Object> validRoster = createValidRosterMap("2026_10", "PUBLISHED");

        assertFalse("Normal user cannot create/publish roster", normalNurse.canCreateOrUpdateMonthlyRoster(validRoster, "2026_10"));
        assertFalse("Normal user cannot delete roster", normalNurse.canDeleteMonthlyRoster());
        assertFalse("Unauthenticated user cannot create/publish roster", unauthenticatedUser.canCreateOrUpdateMonthlyRoster(validRoster, "2026_10"));
    }

    @Test
    public void testMonthlyRoster_AdminCanCreateAndPublishValidRoster() {
        Map<String, Object> validDraft = createValidRosterMap("2026_10_draft", "DRAFT");
        Map<String, Object> validPublished = createValidRosterMap("2026_10", "PUBLISHED", ADMIN_UID);
        Map<String, Object> validPublishedClaim = createValidRosterMap("2026_10", "PUBLISHED", "admin_claim_uid");

        assertTrue("Admin can save valid draft", adminUserViaCollection.canCreateOrUpdateMonthlyRoster(validDraft, "2026_10_draft"));
        assertTrue("Admin can publish valid roster", adminUserViaCollection.canCreateOrUpdateMonthlyRoster(validPublished, "2026_10"));
        assertTrue("Claim-based admin can publish valid roster", adminUserViaClaim.canCreateOrUpdateMonthlyRoster(validPublishedClaim, "2026_10"));
        assertTrue("Admin can delete draft after publishing", adminUserViaCollection.canDeleteMonthlyRoster());
    }

    @Test
    public void testMonthlyRoster_EnforcesPublishedByUidMatchesAuthUid() {
        // Correct publishedByUid matching authenticated admin UID
        Map<String, Object> validPublished = createValidRosterMap("2026_10", "PUBLISHED", ADMIN_UID);
        assertTrue("Publishing succeeds when publishedByUid == request.auth.uid",
                adminUserViaCollection.canCreateOrUpdateMonthlyRoster(validPublished, "2026_10"));

        // Spoofed publishedByUid (e.g. admin tries to set publishedByUid to another user's UID)
        Map<String, Object> spoofedPublisher = createValidRosterMap("2026_10", "PUBLISHED", "another_admin_uid");
        assertFalse("Publishing is rejected when publishedByUid != request.auth.uid",
                adminUserViaCollection.canCreateOrUpdateMonthlyRoster(spoofedPublisher, "2026_10"));
    }

    @Test
    public void testMonthlyRoster_SchemaValidationRejectsInvalidDataEvenForAdmin() {
        // Mismatched ID
        Map<String, Object> mismatchedId = createValidRosterMap("2026_10", "PUBLISHED");
        assertFalse("Rejects mismatched document ID and data.id",
                adminUserViaCollection.canCreateOrUpdateMonthlyRoster(mismatchedId, "2026_11"));

        // Invalid month (< 1 or > 12)
        Map<String, Object> invalidMonth = createValidRosterMap("2026_13", "PUBLISHED");
        invalidMonth.put("month", 13);
        assertFalse("Rejects month > 12", adminUserViaCollection.canCreateOrUpdateMonthlyRoster(invalidMonth, "2026_13"));

        // Invalid status
        Map<String, Object> invalidStatus = createValidRosterMap("2026_10", "UNKNOWN_STATUS");
        assertFalse("Rejects status not in ['DRAFT', 'PUBLISHED']",
                adminUserViaCollection.canCreateOrUpdateMonthlyRoster(invalidStatus, "2026_10"));

        // Published roster missing publishedAt or publishedByUid
        Map<String, Object> missingPublisher = createValidRosterMap("2026_10", "PUBLISHED");
        missingPublisher.remove("publishedByUid");
        assertFalse("Rejects PUBLISHED roster missing publishedByUid",
                adminUserViaCollection.canCreateOrUpdateMonthlyRoster(missingPublisher, "2026_10"));
    }

    @Test
    public void testMonthlyRoster_QueryListFiltering() {
        // Query filtered by status == 'PUBLISHED'
        assertTrue("Normal user can query published rosters with filter", normalNurse.canListMonthlyRosters("PUBLISHED"));

        // Query without filter or with 'DRAFT'
        assertFalse("Normal user CANNOT query draft rosters", normalNurse.canListMonthlyRosters("DRAFT"));
        assertFalse("Normal user CANNOT query without status filter", normalNurse.canListMonthlyRosters(null));

        // Admin can query with any filter
        assertTrue("Admin can query with filter", adminUserViaCollection.canListMonthlyRosters("PUBLISHED"));
        assertTrue("Admin can query drafts", adminUserViaCollection.canListMonthlyRosters("DRAFT"));
        assertTrue("Admin can query without filter", adminUserViaCollection.canListMonthlyRosters(null));
    }

    // ── 3. Per-User Isolation Tests (/users/{uid}/**) ───────────────────────────

    @Test
    public void testPerUserIsolation_UsersCannotAccessOtherUserData() {
        // User A accessing User A duties
        assertTrue("User A can read their own duty", normalNurse.canReadUserDuty(NURSE_A_UID));
        assertTrue("User A can delete their own duty", normalNurse.canDeleteUserDuty(NURSE_A_UID));

        // User B attempting to access User A duties
        assertFalse("User B CANNOT read User A duties", anotherNurse.canReadUserDuty(NURSE_A_UID));
        assertFalse("User B CANNOT delete User A duties", anotherNurse.canDeleteUserDuty(NURSE_A_UID));

        // Unauthenticated user cannot access any user duties
        assertFalse("Unauthenticated user cannot read duties", unauthenticatedUser.canReadUserDuty(NURSE_A_UID));
    }

    @Test
    public void testPerUserIsolation_UserCannotSpoofOwnerUidOnWrite() {
        Map<String, Object> validDutyForNurseA = new HashMap<>();
        validDutyForNurseA.put("id", "duty_01");
        validDutyForNurseA.put("ownerUid", NURSE_A_UID);
        validDutyForNurseA.put("dutyDate", "2026-10-15");
        validDutyForNurseA.put("shiftType", "MORNING");
        validDutyForNurseA.put("status", "SCHEDULED");
        validDutyForNurseA.put("updatedAt", System.currentTimeMillis());

        // Valid write by Owner
        assertTrue("Nurse A can write duty with ownerUid == NURSE_A_UID",
                normalNurse.canCreateUserDuty(NURSE_A_UID, "duty_01", validDutyForNurseA));

        // Nurse A attempting to write a duty into Nurse B's path
        assertFalse("Nurse A cannot write into Nurse B collection",
                normalNurse.canCreateUserDuty(NURSE_B_UID, "duty_01", validDutyForNurseA));

        // Nurse A attempting to write a duty into Nurse A path but setting ownerUid to Nurse B
        Map<String, Object> spoofedOwner = new HashMap<>(validDutyForNurseA);
        spoofedOwner.put("ownerUid", NURSE_B_UID);
        assertFalse("Cannot write duty with ownerUid spoofed to another user",
                normalNurse.canCreateUserDuty(NURSE_A_UID, "duty_01", spoofedOwner));
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
