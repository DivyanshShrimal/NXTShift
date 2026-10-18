# NXTShift — Native Android Shift Planner for Nurses

> **Always one shift ahead.**

NXTShift is a production-oriented native Android application built for nurses and hospital staff to manage their individual duty rosters, track shifts across departments, import rosters directly from screenshots using on-device ML Kit OCR, and maintain synchronized schedules across devices via Firebase Authentication and Cloud Firestore.

---

## 🛠 Tech Stack & Architecture

- **Language & Platform:** Java 17, Native Android (API 26 min, API 34 target)
- **Architecture:** Clean Architecture with Repository Pattern, MVVM, and Android Jetpack
- **Local Database:** Room SQLite with strictly isolated per-user queries and indices
- **Cloud Backend:** Firebase Authentication (Email/Password) + Cloud Firestore
- **On-Device OCR:** Google ML Kit Text Recognition (`com.google.android.gms:play-services-mlkit-text-recognition`)
- **Notifications & Scheduling:** Android `AlarmManager.setExactAndAllowWhileIdle()` + `BroadcastReceiver` + `BOOT_COMPLETED` restart recovery
- **UI & Design:** Material Design 3 (Dark Navy / Medical Teal / Modern Slate), ViewBinding, and ConstraintLayout

---

## 🔐 Authentication & Security

### Firebase Email & Password Authentication
- **User Registration:** Email, Password, and Confirm Password with minimum 6-character validation and duplicate account detection.
- **Email Verification:** Sends verification links upon registration; dedicated holding screen with countdown timer and server recheck (`reload()`).
- **User Sign-In:** Secure email and password authentication with clear error mapping.
- **Forgot Password:** In-app reset flow sending password reset emails directly through Firebase.
- **Account Isolation & Local Wipe:** Upon sign-out or account deletion, all cached Room records belonging to the authenticated `ownerUid` are safely cleared to ensure no shift data leaks between different accounts on shared devices.

### Cloud Firestore Security Rules
All duty data and profiles are strictly nested under `/users/{uid}/` and enforced via `firestore.rules`:
```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    function isAuthenticated() {
      return request.auth != null && request.auth.uid != null;
    }
    function isOwner(uid) {
      return isAuthenticated() && request.auth.uid == uid;
    }
    match /users/{uid} {
      allow read, write: if isOwner(uid);
      match /duties/{dutyId} {
        allow read, write: if isOwner(uid);
      }
      match /profile/{profileDoc} {
        allow read, write: if isOwner(uid);
      }
      match /settings/{settingsDoc} {
        allow read, write: if isOwner(uid);
      }
    }
    match /{document=**} {
      allow read, write: if false;
    }
  }
}
```

---

## 📱 Features

1. **Dashboard & Duty Calendar**
   - Monthly calendar grid with color-coded shift indicator dots.
   - Day selection showing complete shift breakdown: Morning, Evening, Night, General, Off, Leave, On-Call.
   - Ward/Department attribution, custom notes, and shift duration calculations.

2. **On-Device Roster Screenshot OCR**
   - Uses Google ML Kit to parse photographed or screenshot duty rosters on-device.
   - Regex-based table parsing with intelligent nurse row detection and day-to-shift token matching (`M`, `E`, `N`, `G`, `OFF`, `L`).
   - Interactive review screen allowing nurses to adjust dates or shift types prior to saving.

3. **Duplicate Detection & Conflict Handling**
   - Configurable import policies:
     - `MERGE_REPLACE`: Updates existing dates with newly imported shifts.
     - `KEEP_EXISTING`: Preserves manually scheduled shifts and only imports newly vacant days.
     - `TIMESTAMP_WINS`: Cloud timestamp-based conflict resolution.

4. **Daily 8 PM Shift Notifications**
   - Schedules an alarm for 8:00 PM (20:00) every evening notifying the nurse of their upcoming tomorrow shift.
   - Auto-recovers on device reboot via `BootReceiver` listening to `android.intent.action.BOOT_COMPLETED`.

---

## 🧪 Unit Tests & Verification

The project includes unit test coverage across all core domain, parsing, conflict, isolation, and authentication logic:

- `EmailAuthValidationTest`: RFC-compliant email formatting, password length rules, and confirmation checks.
- `ConflictPolicyTest`: Merge, keep existing, and timestamp conflict resolution tests.
- `DataIsolationTest`: Verifies local Room entities strictly isolate duty records by `ownerUid`.
- `DateTimeUtilsTest`: Date parsing, calendar formatting, and next-day date math.
- `DutyDomainTest`: Shift type definitions, time calculations, and entity domain mapping.
- `NotificationContentTest`: 8 PM notification title and message formatting.
- `RosterParserTest`: OCR roster grid parsing, token recognition, and nurse row matching.
- `FirestoreRulesSecurityTest`: Server-side predicate evaluation for admin rights, global kill switch, user revocation, and user isolation.
- `AccessControlRepositoryTest`: Client-side access state transitions, global/user access gating, fail-closed handling, and offline calendar availability.

---

## 🛡️ Remote Access Control & App Governance

NXTShift incorporates a centralized, fail-closed Remote Access Control system enabling administrators to manage global app availability (maintenance mode) and individually revoke or restore tester/user access.

### Architecture & Control Schema

1. **Global Application Control (`/app_control/global`)**
   - `enabled` (boolean): `true` allows online operations; `false` activates maintenance mode.
   - `message` (string): Optional user-facing notification displayed on the maintenance screen.
   - `updatedAt` (number): Timestamp of the last change.
   - `updatedByUid` (string): UID of the administrator who applied the change.
   - `minimumSupportedVersion` (number): Optional client version enforcement.

2. **Individual User Access (`/app_access/{uid}`)**
   - `enabled` (boolean): `false` immediately revokes cloud sync for that specific UID.
   - `reason` (string): Non-sensitive explanation for the revocation.
   - `updatedAt` (number): Timestamp of revocation/restoration.
   - `updatedByUid` (string): UID of the administrator who performed the action.
   *(Note: Users without an `/app_access` document are permitted by default unless revoked).*

### Admin Authorization Requirements

- Administrative actions (toggling global access or modifying user access records) are strictly restricted to authenticated users identified in the server-side `/admins/{adminUid}` collection.
- Regular users cannot modify `/app_control/global`, `/app_access/{uid}`, or escalate their own privileges.
- In `firestore.rules`, all mutations and reads to protected collections (`/users/{uid}/*` and `/monthly_rosters/*`) enforce:
  `isAccessPermitted(uid) == isGlobalEnabled() && isUserAccessAllowed(uid)`.

### Offline-First Policy & Emergency Calendar Access

- **Online & Authorized:** Normal real-time synchronization with Cloud Firestore.
- **Offline / Disconnected:**
  - Local Room SQLite database remains 100% functional.
  - Medical staff can always inspect their saved duties and browse the monthly calendar via **"View Offline Calendar"**.
  - New cloud writes and synchronizations require active verification and fail closed if unverified.
- **Access Revoked or Global Maintenance:**
  - Online cloud synchronization and roster publishing are halted immediately.
  - The app displays a dedicated **Access Restricted** holding screen.
  - Staff can tap **"View Offline Calendar"** to view previously cached local shifts at any time.

### Known Limitations

- **Offline Latency:** A device without an internet connection cannot receive real-time revocation signals until it reconnects. Server-side Firestore rules prevent any unauthorized data reads or writes if the client attempts to communicate with the cloud.
- **Compromised/Rooted Devices:** While rooted clients could manipulate client-side UI, server-side Firestore security rules independently reject unauthorized queries and mutations.

### Administration & Console Setup

1. **Initial Setup in Firebase Console:**
   - Create document `/app_control/global` with fields:
     - `enabled: true`
     - `message: "All services operational"`
     - `updatedAt: <current timestamp>`
   - To designate an admin, create document `/admins/<ADMIN_UID>` with field `{ active: true }`.
2. **In-App Administration:**
   - Sign in with an authorized administrator account.
   - Open **Admin Roster Portal** from the Dashboard.
   - Under **Remote Access Control**, view live global status, toggle maintenance mode, or look up and revoke/restore specific user UIDs.

---

### Build & Test Commands

Run all unit tests:
```bash
./gradlew testDebugUnitTest
```

Build debug APK:
```bash
./gradlew assembleDebug
```
Output APK location: `app/build/outputs/apk/debug/app-debug.apk`
