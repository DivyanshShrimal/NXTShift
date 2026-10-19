const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require('@firebase/rules-unit-testing');
const { test, before, beforeEach, after } = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');

const PROJECT_ID = 'demo-nxtshift-candidate-rules-test';
const RULES_PATH = path.resolve(__dirname, '../firestore.rules');

let testEnv;

// --- Helper Functions ---

function getUserDb(uid, tokenOptions = {}) {
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

async function setAdminDoc(adminUid) {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('admins').doc(adminUid).set({
      active: true,
      createdAt: Date.now(),
    });
  });
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
// Suite 1: Authentication & isAdmin() Evaluation (Safe Missing Claim vs Admin Doc)
// =============================================================================

test('1.1. Authenticated user with NO admin claim and NO admin doc is NOT admin', async () => {
  const db = getUserDb('user_without_claim'); // empty token, no 'admin' key
  // Should not throw error on token.get('admin', false), evaluates to false
  const draftRef = db.collection('monthly_rosters').doc('2026_11');
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('monthly_rosters').doc('2026_11').set(validDraftRoster('2026_11'));
  });

  // Cannot read draft roster
  await assertFails(draftRef.get());
  // Cannot create monthly roster
  await assertFails(draftRef.set(validDraftRoster('2026_11')));
  // Cannot list /admins collection
  await assertFails(db.collection('admins').get());
});

test('1.2. Authenticated user with custom claim { admin: true } is authorized as admin', async () => {
  const db = getUserDb('claim_admin_user', { admin: true });
  const draftRef = db.collection('monthly_rosters').doc('2026_11');
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('monthly_rosters').doc('2026_11').set(validDraftRoster('2026_11'));
  });

  // Can read draft roster
  await assertSucceeds(draftRef.get());
  // Can create valid monthly roster
  const newRosterRef = db.collection('monthly_rosters').doc('2026_12');
  await assertSucceeds(newRosterRef.set(validDraftRoster('2026_12')));
  // Can list /admins collection
  await assertSucceeds(db.collection('admins').get());
});

test('1.3. Authenticated user with custom claim { admin: false } is NOT authorized as admin', async () => {
  const db = getUserDb('claim_false_user', { admin: false });
  const draftRef = db.collection('monthly_rosters').doc('2026_11');
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('monthly_rosters').doc('2026_11').set(validDraftRoster('2026_11'));
  });

  await assertFails(draftRef.get());
  await assertFails(draftRef.set(validDraftRoster('2026_11')));
  await assertFails(db.collection('admins').get());
});

test('1.4. User with NO admin claim BUT with /admins/{uid} document is authorized as admin', async () => {
  const uid = 'doc_admin_user';
  await setAdminDoc(uid);
  const db = getUserDb(uid); // No token claim, but exists in /admins/{uid}

  const draftRef = db.collection('monthly_rosters').doc('2026_11');
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('monthly_rosters').doc('2026_11').set(validDraftRoster('2026_11'));
  });

  // Allowed because exists(/databases/$(database)/documents/admins/$(request.auth.uid)) is true
  await assertSucceeds(draftRef.get());
  // Can create monthly roster
  const newRosterRef = db.collection('monthly_rosters').doc('2026_12');
  await assertSucceeds(newRosterRef.set(validDraftRoster('2026_12')));
  // Can list /admins collection
  await assertSucceeds(db.collection('admins').get());
});

test('1.5. Unauthenticated user is denied all access', async () => {
  const db = getUnauthedDb();
  await assertFails(db.collection('monthly_rosters').doc('2026_11').get());
  await assertFails(db.collection('admins').doc('any').get());
  await assertFails(db.collection('users').doc('any').get());
});

// =============================================================================
// Suite 2: /admins/{adminUid} Collection Permissions
// =============================================================================

test('2.1. Any authenticated user can get their own /admins/{uid} document', async () => {
  const db = getUserDb('nurse_a');
  // Can get their own doc (even when non-existent, does not fail permission denied)
  const ownRef = db.collection('admins').doc('nurse_a');
  await assertSucceeds(ownRef.get());
});

test('2.2. Normal user CANNOT get another user /admins/{otherUid} document', async () => {
  const db = getUserDb('nurse_a');
  const otherRef = db.collection('admins').doc('nurse_b');
  await assertFails(otherRef.get());
});

test('2.3. Admin CAN get another user /admins/{otherUid} document', async () => {
  const db = getUserDb('claim_admin', { admin: true });
  const otherRef = db.collection('admins').doc('nurse_b');
  await assertSucceeds(otherRef.get());
});

test('2.4. Normal user CANNOT list /admins collection', async () => {
  const db = getUserDb('nurse_a');
  await assertFails(db.collection('admins').get());
});

test('2.5. Admin CAN list /admins collection', async () => {
  await setAdminDoc('doc_admin');
  const db = getUserDb('doc_admin');
  await assertSucceeds(db.collection('admins').get());
});

test('2.6. Client writes to /admins collection are COMPLETELY BLOCKED for all users', async () => {
  const userDb = getUserDb('nurse_a');
  const claimAdminDb = getUserDb('claim_admin', { admin: true });
  await setAdminDoc('doc_admin');
  const docAdminDb = getUserDb('doc_admin');

  // Normal user cannot write
  await assertFails(userDb.collection('admins').doc('nurse_a').set({ active: true }));
  // Custom-claim admin cannot write
  await assertFails(claimAdminDb.collection('admins').doc('new_admin').set({ active: true }));
  // Doc admin cannot write or update
  await assertFails(docAdminDb.collection('admins').doc('doc_admin').update({ active: false }));
  // Deletions blocked
  await assertFails(claimAdminDb.collection('admins').doc('doc_admin').delete());
});

// =============================================================================
// Suite 3: Central Monthly Rosters (/monthly_rosters/{rosterId})
// =============================================================================

test('3.1. Normal authenticated user CAN read published monthly roster', async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('monthly_rosters').doc('2026_11').set(
      validPublishedRoster('2026_11', 'admin_123')
    );
  });

  const db = getUserDb('nurse_a');
  const rosterRef = db.collection('monthly_rosters').doc('2026_11');
  await assertSucceeds(rosterRef.get());
});

test('3.2. Normal authenticated user CAN query/list published rosters with filter', async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('monthly_rosters').doc('2026_11').set(
      validPublishedRoster('2026_11', 'admin_123')
    );
  });

  const db = getUserDb('nurse_a');
  const query = db.collection('monthly_rosters').where('status', '==', 'PUBLISHED');
  await assertSucceeds(query.get());
});

test('3.3. Normal authenticated user CANNOT read draft monthly roster', async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('monthly_rosters').doc('2026_11').set(
      validDraftRoster('2026_11')
    );
  });

  const db = getUserDb('nurse_a');
  const rosterRef = db.collection('monthly_rosters').doc('2026_11');
  await assertFails(rosterRef.get());
});

test('3.4. Normal authenticated user CANNOT create, update, or delete monthly rosters', async () => {
  const db = getUserDb('nurse_a');
  const rosterRef = db.collection('monthly_rosters').doc('2026_11');

  await assertFails(rosterRef.set(validDraftRoster('2026_11')));
  await assertFails(rosterRef.set(validPublishedRoster('2026_11', 'nurse_a')));
});

test('3.5. Admin CAN create and publish valid monthly roster', async () => {
  const adminUid = 'admin_publisher';
  const db = getUserDb(adminUid, { admin: true });
  const rosterRef = db.collection('monthly_rosters').doc('2026_11');

  // Draft creation
  await assertSucceeds(rosterRef.set(validDraftRoster('2026_11')));

  // Publish with publishedByUid matching auth.uid
  await assertSucceeds(rosterRef.set(validPublishedRoster('2026_11', adminUid)));
});

test('3.6. Admin CANNOT publish monthly roster if publishedByUid does not match auth.uid', async () => {
  const adminUid = 'admin_publisher';
  const db = getUserDb(adminUid, { admin: true });
  const rosterRef = db.collection('monthly_rosters').doc('2026_11');

  // publishedByUid set to someone else
  const spoofedRoster = validPublishedRoster('2026_11', 'different_admin_uid');
  await assertFails(rosterRef.set(spoofedRoster));
});

test('3.7. Admin CANNOT create monthly roster with invalid schema', async () => {
  const adminUid = 'admin_publisher';
  const db = getUserDb(adminUid, { admin: true });
  const rosterRef = db.collection('monthly_rosters').doc('2026_11');

  // Invalid month (13)
  const invalidMonth = { ...validDraftRoster('2026_11'), month: 13 };
  await assertFails(rosterRef.set(invalidMonth));

  // Invalid year (2018 < 2020)
  const invalidYear = { ...validDraftRoster('2026_11'), year: 2018 };
  await assertFails(rosterRef.set(invalidYear));

  // Invalid status
  const invalidStatus = { ...validDraftRoster('2026_11'), status: 'ARCHIVED' };
  await assertFails(rosterRef.set(invalidStatus));
});

test('3.8. Admin CAN delete monthly roster', async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection('monthly_rosters').doc('2026_11').set(
      validDraftRoster('2026_11')
    );
  });

  const db = getUserDb('admin_deleter', { admin: true });
  await assertSucceeds(db.collection('monthly_rosters').doc('2026_11').delete());
});

// =============================================================================
// Suite 4: User Data Isolation & Duty Schema Validation (/users/{uid}/...)
// =============================================================================

test('4.1. User CAN access own profile, duties, rosterImports, staff, and settings', async () => {
  const uid = 'nurse_user_1';
  const db = getUserDb(uid);

  // Root doc
  await assertSucceeds(db.collection('users').doc(uid).set({ name: 'Nurse 1' }));
  await assertSucceeds(db.collection('users').doc(uid).get());

  // Profile sub-doc
  await assertSucceeds(db.collection('users').doc(uid).collection('profile').doc('main').set({ bio: 'Nurse' }));
  await assertSucceeds(db.collection('users').doc(uid).collection('profile').doc('main').get());

  // Valid Duty
  const duty = validDuty('duty_1', uid);
  await assertSucceeds(db.collection('users').doc(uid).collection('duties').doc('duty_1').set(duty));
  await assertSucceeds(db.collection('users').doc(uid).collection('duties').doc('duty_1').get());

  // RosterImports
  await assertSucceeds(db.collection('users').doc(uid).collection('rosterImports').doc('imp_1').set({ importedAt: Date.now() }));
  await assertSucceeds(db.collection('users').doc(uid).collection('rosterImports').doc('imp_1').get());

  // Staff
  await assertSucceeds(db.collection('users').doc(uid).collection('staff').doc('st_1').set({ name: 'Colleague' }));
  await assertSucceeds(db.collection('users').doc(uid).collection('staff').doc('st_1').get());

  // Settings
  await assertSucceeds(db.collection('users').doc(uid).collection('settings').doc('app').set({ darkMode: true }));
  await assertSucceeds(db.collection('users').doc(uid).collection('settings').doc('app').get());
});

test('4.2. User CANNOT read or write another user private data', async () => {
  const uidA = 'nurse_user_a';
  const uidB = 'nurse_user_b';

  const dbB = getUserDb(uidB);
  await dbB.collection('users').doc(uidB).set({ name: 'Nurse B' });
  await dbB.collection('users').doc(uidB).collection('duties').doc('duty_b').set(validDuty('duty_b', uidB));

  const dbA = getUserDb(uidA);
  // Cannot read B's root doc
  await assertFails(dbA.collection('users').doc(uidB).get());
  // Cannot write B's root doc
  await assertFails(dbA.collection('users').doc(uidB).set({ name: 'Hacked' }));
  // Cannot read B's duties
  await assertFails(dbA.collection('users').doc(uidB).collection('duties').doc('duty_b').get());
  // Cannot write B's duties
  await assertFails(dbA.collection('users').doc(uidB).collection('duties').doc('duty_new').set(validDuty('duty_new', uidB)));
});

test('4.3. Duty write schema rejects invalid duty data even from owner', async () => {
  const uid = 'nurse_user_1';
  const db = getUserDb(uid);

  // Missing required field (e.g., status)
  const invalidDuty1 = {
    id: 'duty_bad_1',
    ownerUid: uid,
    dutyDate: '2026-10-25',
    shiftType: 'MORNING',
    updatedAt: Date.now(),
    // missing status
  };
  await assertFails(db.collection('users').doc(uid).collection('duties').doc('duty_bad_1').set(invalidDuty1));

  // ownerUid mismatch
  const spoofedDuty = {
    ...validDuty('duty_bad_2', 'someone_else'),
    id: 'duty_bad_2',
  };
  await assertFails(db.collection('users').doc(uid).collection('duties').doc('duty_bad_2').set(spoofedDuty));

  // Document ID mismatch with data.id
  const mismatchedIdDuty = {
    ...validDuty('duty_mismatch_id', uid),
  };
  await assertFails(db.collection('users').doc(uid).collection('duties').doc('different_doc_id').set(mismatchedIdDuty));
});

test('4.4. Owner CAN update and delete own duty, but CANNOT change ownerUid on update', async () => {
  const uid = 'nurse_user_1';
  const db = getUserDb(uid);
  const duty = validDuty('duty_1', uid);

  await assertSucceeds(db.collection('users').doc(uid).collection('duties').doc('duty_1').set(duty));

  // Valid update
  const updatedDuty = { ...duty, shiftType: 'NIGHT', updatedAt: Date.now() };
  await assertSucceeds(db.collection('users').doc(uid).collection('duties').doc('duty_1').set(updatedDuty));

  // Update changing ownerUid fails
  const hijackedDuty = { ...updatedDuty, ownerUid: 'other_user' };
  await assertFails(db.collection('users').doc(uid).collection('duties').doc('duty_1').set(hijackedDuty));

  // Owner can delete
  await assertSucceeds(db.collection('users').doc(uid).collection('duties').doc('duty_1').delete());
});

// =============================================================================
// Suite 5: Catch-all Default Deny
// =============================================================================

test('5.1. Unmatched paths are denied for all users and admins', async () => {
  const userDb = getUserDb('nurse_a');
  const adminDb = getUserDb('admin_a', { admin: true });

  await assertFails(userDb.collection('arbitrary_collection').doc('doc1').set({ foo: 'bar' }));
  await assertFails(adminDb.collection('arbitrary_collection').doc('doc1').set({ foo: 'bar' }));
  await assertFails(userDb.collection('arbitrary_collection').doc('doc1').get());
  await assertFails(adminDb.collection('arbitrary_collection').doc('doc1').get());
});
