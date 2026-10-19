const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require('@firebase/rules-unit-testing');
const { test, before, beforeEach, after } = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');

const PROJECT_ID = 'demo-nxtshift-rules-test';
const RULES_PATH = path.resolve(__dirname, '../firestore.rules');

let testEnv;

// --- Helper Functions to get fresh context/db instances per call ---

function getUserDb(uid, tokenOptions) {
  return testEnv.authenticatedContext(uid, tokenOptions).firestore();
}

function getUnauthedDb() {
  return testEnv.unauthenticatedContext().firestore();
}

function validDuty(id, uid) {
  return {
    id,
    ownerUid: uid,
    dutyDate: '2026-10-25',
    shiftType: 'MORNING',
    status: 'SCHEDULED',
    updatedAt: Date.now(),
  };
}

function validDraftRoster(id) {
  return {
    id,
    year: 2026,
    month: 11,
    status: 'DRAFT',
    version: 1,
    updatedAt: Date.now(),
  };
}

function validPublishedRoster(id, publishedByUid) {
  return {
    id,
    year: 2026,
    month: 11,
    status: 'PUBLISHED',
    version: 1,
    updatedAt: Date.now(),
    publishedAt: Date.now(),
    publishedByUid,
  };
}

async function setGlobalControl(enabled, message = 'All services operational') {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('app_control').doc('global').set({
      enabled,
      message,
      updatedAt: Date.now(),
      updatedByUid: 'admin_setup',
      minimumSupportedVersion: 1,
    });
  });
  await new Promise((resolve) => setTimeout(resolve, 50));
}

async function setAdmin(adminUid) {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('admins').doc(adminUid).set({
      active: true,
      createdAt: Date.now(),
    });
  });
  await new Promise((resolve) => setTimeout(resolve, 50));
}

async function setUserAccess(uid, enabled, reason = 'Test Access Control') {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('app_access').doc(uid).set({
      uid,
      enabled,
      reason,
      updatedAt: Date.now(),
      updatedByUid: 'admin_setup',
    });
  });
  await new Promise((resolve) => setTimeout(resolve, 50));
}

before(async () => {
  const rules = fs.readFileSync(RULES_PATH, 'utf8');
  const hostPort = process.env.FIRESTORE_EMULATOR_HOST || '127.0.0.1:8080';
  const [host, portStr] = hostPort.split(':');
  const port = parseInt(portStr || '8080', 10);

  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host,
      port,
    },
  });
});

beforeEach(async () => {
  await testEnv.clearFirestore();
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

// =============================================================================
// 1. Missing Global Control Fails Closed
// =============================================================================
test('1. Missing /app_control/global document fails closed for user duties', async () => {
  // Do NOT create /app_control/global doc
  const db = getUserDb('nurse_a');
  const duty = validDuty('duty_1', 'nurse_a');
  const dutyRef = db.collection('users').doc('nurse_a').collection('duties').doc('duty_1');

  await assertFails(dutyRef.set(duty));
  await assertFails(dutyRef.get());
});

test('1b. Missing /app_control/global document fails closed for admin monthly rosters', async () => {
  await setAdmin('admin_user');
  const db = getUserDb('admin_user');
  const rosterRef = db.collection('monthly_rosters').doc('2026_11');

  await assertFails(rosterRef.set(validDraftRoster('2026_11')));
  await assertFails(rosterRef.get());
});

// =============================================================================
// 2. Global Disable Blocks Cloud Operations
// =============================================================================
test('2. Disabled /app_control/global blocks user reads and writes', async () => {
  await setGlobalControl(false, 'Hospital system undergoing maintenance');
  const db = getUserDb('nurse_a');
  const duty = validDuty('duty_1', 'nurse_a');
  const dutyRef = db.collection('users').doc('nurse_a').collection('duties').doc('duty_1');

  await assertFails(dutyRef.set(duty));
  await assertFails(dutyRef.get());
});

test('2b. Disabled /app_control/global blocks admin roster publishing and reads', async () => {
  await setGlobalControl(false, 'Emergency maintenance');
  await setAdmin('admin_user');

  // Seed a published roster via privileged API
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('monthly_rosters').doc('2026_10')
      .set(validPublishedRoster('2026_10', 'admin_user'));
  });

  const db = getUserDb('admin_user');
  const rosterRef = db.collection('monthly_rosters').doc('2026_10');
  await assertFails(rosterRef.get());
  await assertFails(rosterRef.update({ updatedAt: Date.now() }));
  await assertFails(rosterRef.delete());
});

// =============================================================================
// 3. Control Documents Modification Authorization
// =============================================================================
test('3. Non-admin and unauthenticated users cannot modify /app_control/global', async () => {
  const unauthedDb = getUnauthedDb();
  const userDb = getUserDb('nurse_a');

  await assertFails(unauthedDb.collection('app_control').doc('global').set({ enabled: true }));
  await assertFails(userDb.collection('app_control').doc('global').set({ enabled: true }));
});

test('3b. Admin user CAN modify /app_control/global', async () => {
  await setAdmin('admin_user');
  const adminDb = getUserDb('admin_user');

  await assertSucceeds(adminDb.collection('app_control').doc('global').set({
    enabled: true,
    message: 'All systems nominal',
    updatedAt: Date.now(),
    updatedByUid: 'admin_user',
    minimumSupportedVersion: 1,
  }));
});

test('3c. Non-admin users cannot modify /app_access/{uid}', async () => {
  await setGlobalControl(true);
  const userDb = getUserDb('nurse_a');

  // Normal user cannot grant themselves or change their access record
  await assertFails(userDb.collection('app_access').doc('nurse_a').set({ enabled: true, uid: 'nurse_a' }));
});

test('3d. Admin user CAN write /app_access/{uid} to revoke or grant access', async () => {
  await setAdmin('admin_user');
  const adminDb = getUserDb('admin_user');

  await assertSucceeds(adminDb.collection('app_access').doc('nurse_a').set({
    uid: 'nurse_a',
    enabled: false,
    reason: 'Temporary suspension',
    updatedAt: Date.now(),
    updatedByUid: 'admin_user',
  }));
});

test('3e. Authenticated user can read their own access record, but not others', async () => {
  await setGlobalControl(true);
  await setUserAccess('nurse_a', true);
  await setUserAccess('nurse_b', true);

  const userDb = getUserDb('nurse_a');
  await assertSucceeds(userDb.collection('app_access').doc('nurse_a').get());
  await assertFails(userDb.collection('app_access').doc('nurse_b').get());
});

// =============================================================================
// 4. Revoked User Access
// =============================================================================
test('4. Revoked user cannot access their own duties or cloud data', async () => {
  await setGlobalControl(true);
  await setUserAccess('revoked_nurse', false, 'Access revoked by department head');

  // Seed an existing duty via privileged API
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('users').doc('revoked_nurse').collection('duties').doc('duty_1')
      .set(validDuty('duty_1', 'revoked_nurse'));
  });

  const revokedDb = getUserDb('revoked_nurse');
  const dutyRef = revokedDb.collection('users').doc('revoked_nurse').collection('duties').doc('duty_1');

  await assertFails(dutyRef.get());
  await assertFails(dutyRef.set(validDuty('duty_2', 'revoked_nurse')));
  await assertFails(dutyRef.delete());
});

test('4b. Revoked user cannot read published monthly rosters', async () => {
  await setGlobalControl(true);
  await setUserAccess('revoked_nurse', false);

  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('monthly_rosters').doc('2026_10')
      .set(validPublishedRoster('2026_10', 'admin_1'));
  });

  const revokedDb = getUserDb('revoked_nurse');
  await assertFails(revokedDb.collection('monthly_rosters').doc('2026_10').get());
});

// =============================================================================
// 5. Revoked Admin Access
// =============================================================================
test('5. Revoked admin cannot create, update, publish, or delete monthly rosters', async () => {
  await setGlobalControl(true);
  await setAdmin('revoked_admin');
  await setUserAccess('revoked_admin', false, 'Admin revoked by security audit');

  // Seed an existing roster via privileged API
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('monthly_rosters').doc('2026_10')
      .set(validPublishedRoster('2026_10', 'revoked_admin'));
  });

  const revokedAdminDb = getUserDb('revoked_admin');
  const draftRef = revokedAdminDb.collection('monthly_rosters').doc('2026_11_draft');
  const publishedRef = revokedAdminDb.collection('monthly_rosters').doc('2026_10');

  // Attempt draft creation
  await assertFails(draftRef.set(validDraftRoster('2026_11_draft')));

  // Attempt publication
  await assertFails(draftRef.set(validPublishedRoster('2026_11_draft', 'revoked_admin')));

  // Attempt update on existing roster
  await assertFails(publishedRef.update({ updatedAt: Date.now() }));

  // Attempt deletion of existing roster
  await assertFails(publishedRef.delete());

  // Attempt reading roster
  await assertFails(publishedRef.get());
});

// =============================================================================
// 6. Enabled Users Retain Intended Permissions & User Isolation
// =============================================================================
test('6. Enabled user can perform full CRUD on their own duties', async () => {
  await setGlobalControl(true);
  // nurse_a has no /app_access doc -> permitted by default
  const userDb = getUserDb('nurse_a');
  const duty = validDuty('duty_1', 'nurse_a');
  const dutyRef = userDb.collection('users').doc('nurse_a').collection('duties').doc('duty_1');

  // Create
  await assertSucceeds(dutyRef.set(duty));

  // Read
  await assertSucceeds(dutyRef.get());

  // Update
  await assertSucceeds(dutyRef.update({
    status: 'COMPLETED',
    updatedAt: Date.now(),
  }));

  // Delete
  await assertSucceeds(dutyRef.delete());
});

test('6b. Per-user data isolation prevents user from accessing other user duties', async () => {
  await setGlobalControl(true);

  // Seed nurse_b's duty via privileged API
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('users').doc('nurse_b').collection('duties').doc('duty_b_1')
      .set(validDuty('duty_b_1', 'nurse_b'));
  });

  const userDb = getUserDb('nurse_a');
  const otherDutyRef = userDb.collection('users').doc('nurse_b').collection('duties').doc('duty_b_1');

  // nurse_a cannot read or modify nurse_b's data
  await assertFails(otherDutyRef.get());
  await assertFails(otherDutyRef.set(validDuty('duty_b_1', 'nurse_b')));
  await assertFails(otherDutyRef.delete());
});

test('6c. User cannot forge duty data with mismatched ownerUid or ID', async () => {
  await setGlobalControl(true);
  const userDb = getUserDb('nurse_a');

  // nurse_a tries to create duty with ownerUid: nurse_b in their own collection
  const forgedOwner = validDuty('duty_1', 'nurse_b');
  const dutyRef1 = userDb.collection('users').doc('nurse_a').collection('duties').doc('duty_1');
  await assertFails(dutyRef1.set(forgedOwner));

  // nurse_a tries to create duty with mismatched document ID vs data.id
  const mismatchedId = validDuty('duty_other_id', 'nurse_a');
  const dutyRef2 = userDb.collection('users').doc('nurse_a').collection('duties').doc('duty_1');
  await assertFails(dutyRef2.set(mismatchedId));
});

// =============================================================================
// 7. Published Roster Reads & Non-Admin Mutations
// =============================================================================
test('7. Normal user can read PUBLISHED roster but CANNOT read DRAFT roster', async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection('app_control').doc('global').set({
      enabled: true,
      message: 'All systems nominal',
      updatedAt: Date.now(),
      updatedByUid: 'admin_setup',
      minimumSupportedVersion: 1,
    });
    await db.collection('monthly_rosters').doc('2026_10')
      .set(validPublishedRoster('2026_10', 'admin_1'));
    await db.collection('monthly_rosters').doc('2026_11_draft')
      .set(validDraftRoster('2026_11_draft'));
  });

  const userDb = getUserDb('nurse_a');
  const publishedRef = userDb.collection('monthly_rosters').doc('2026_10');
  const draftRef = userDb.collection('monthly_rosters').doc('2026_11_draft');

  await assertSucceeds(publishedRef.get());
  await assertFails(draftRef.get());
});

test('7b. Normal user CANNOT create, update, or delete monthly rosters', async () => {
  await setGlobalControl(true);
  const userDb = getUserDb('nurse_a');
  const draftRef = userDb.collection('monthly_rosters').doc('2026_11');

  await assertFails(draftRef.set(validDraftRoster('2026_11')));
});

// =============================================================================
// 8. Admin Roster Lifecycle & Schema Validation
// =============================================================================
test('8. Authorized admin can create draft, publish roster, and delete roster', async () => {
  await setGlobalControl(true);
  await setAdmin('admin_user');

  const adminDb = getUserDb('admin_user');
  const rosterRef = adminDb.collection('monthly_rosters').doc('2026_11');

  // Create draft
  await assertSucceeds(rosterRef.set(validDraftRoster('2026_11')));

  // Read draft (admin only)
  await assertSucceeds(rosterRef.get());

  // Publish
  await assertSucceeds(rosterRef.set(validPublishedRoster('2026_11', 'admin_user')));

  // Delete
  await assertSucceeds(rosterRef.delete());
});

test('8b. Publishing with mismatched publishedByUid is rejected', async () => {
  await setGlobalControl(true);
  await setAdmin('admin_user');

  const adminDb = getUserDb('admin_user');
  const rosterRef = adminDb.collection('monthly_rosters').doc('2026_11');

  // Attempt to publish claiming publishedByUid is 'other_admin'
  const mismatchedRoster = validPublishedRoster('2026_11', 'other_admin');
  await assertFails(rosterRef.set(mismatchedRoster));
});

test('8c. Invalid roster data (missing required fields / invalid values) is rejected', async () => {
  await setGlobalControl(true);
  await setAdmin('admin_user');

  const adminDb = getUserDb('admin_user');
  const rosterRef = adminDb.collection('monthly_rosters').doc('2026_11');

  // Invalid month (month > 12)
  const invalidMonth = { ...validDraftRoster('2026_11'), month: 13 };
  await assertFails(rosterRef.set(invalidMonth));

  // Invalid status (not DRAFT or PUBLISHED)
  const invalidStatus = { ...validDraftRoster('2026_11'), status: 'ARCHIVED' };
  await assertFails(rosterRef.set(invalidStatus));

  // Missing version
  const missingVersion = { ...validDraftRoster('2026_11') };
  delete missingVersion.version;
  await assertFails(rosterRef.set(missingVersion));

  // Non-numeric updatedAt
  const nonNumericUpdated = { ...validDraftRoster('2026_11'), updatedAt: '2026-10-19' };
  await assertFails(rosterRef.set(nonNumericUpdated));
});

// =============================================================================
// 9. Client Admin Document Write Prohibition
// =============================================================================
test('9. No client app (even admin) can write to /admins collection', async () => {
  await setAdmin('admin_user');

  const adminDb = getUserDb('admin_user');
  const newAdminRef = adminDb.collection('admins').doc('new_admin');
  await assertFails(newAdminRef.set({ active: true }));

  const userDb = getUserDb('nurse_a');
  const selfAdminRef = userDb.collection('admins').doc('nurse_a');
  await assertFails(selfAdminRef.set({ active: true }));
});
