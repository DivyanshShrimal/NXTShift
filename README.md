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
