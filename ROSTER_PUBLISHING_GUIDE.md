# NXTShift — Central Admin Roster Publishing System Guide

This guide documents the centralized monthly roster publishing architecture, spreadsheet column format, Firestore Security Rules, and step-by-step administrator configuration.

---

## 1. Architecture Overview

NXTShift enables hospital administrators to upload, validate, preview, and publish monthly duty rosters centrally. Clinical staff members can query published rosters, select their approved name, review their exact duties, and import them directly into their private personal calendar.

### Security Boundary:
- **Server-Side Enforcement**: Admin access is governed directly by Firestore Security Rules using a protected `/admins/{uid}` collection. Client-side UI toggles are never trusted as the security boundary.
- **Data Isolation**: User calendars remain isolated under `/users/{uid}/duties` and local Room storage. One user's import never touches another user's schedule.
- **Spark Plan Friendly**: Each published month is stored as structured document data (~25 KB), well within the 1 MB Firestore document limit and free tier read quotas (1 document read per month import).

---

## 2. Spreadsheet Format & Columns

The system supports standard `.csv` and Excel `.xlsx` files with zero external runtime dependencies.

### Required Column Headers:
1. **Date Column**:
   - Header: `Date` or `Day`
   - Formats accepted: `DD/MM/YYYY` (e.g. `01/10/2026`), `YYYY-MM-DD` (e.g. `2026-10-01`), `D-MMM` (e.g. `1-Oct`), or day numbers (`1` to `31`).
2. **Shift Columns**:
   - **Morning**: `8 AM - 2 PM`, `8-2`, `08:00 - 14:00`, or `Morning`
   - **Evening**: `2 PM - 8 PM`, `2-8`, `14:00 - 20:00`, or `Evening`
   - **Night**: `8 PM - 8 AM`, `8-8`, `20:00 - 08:00`, or `Night` (automatically flags overnight transitions)
   - **General**: `9 AM - 5 PM`, `10 AM - 6 PM`, `12 PM - 8 PM`, or `General`
3. **Status Columns (Kept Strictly Distinct)**:
   - **Off**: `Off`, `Weekly Off`, `W/O`, `WO`
   - **Leave**: `Leave`, `On Leave`, `CL`, `EL`, `SL`

### Cell Content Rules:
- Multiple staff members in a single shift cell should be separated by commas (`,`), semicolons (`;`), slashes (`/`), or newlines (`\n`).
- Two-word names (such as **Ajay Jangid** and **Ajay Jindoliya**) are strictly preserved and differentiated.
- Known OCR/spelling variations (e.g. `Yarmini` → `Yamini`) are normalized automatically.
- Off and Leave entries are never converted into working duties.
- Colleagues are strictly isolated to the exact date-and-shift cell and are never taken from adjacent columns.

### Sample CSV Template (`roster_template.csv`):
```csv
Date, 8 AM - 2 PM, 2 PM - 8 PM, 8 PM - 8 AM, 9 AM - 5 PM, Off, Leave
01/10/2026, Munesh; Pradeep, Ajay Jangid; Yamini, Govind; Rohit, Vishal, Shiv, Ram
02/10/2026, Kuldeep; Hansraj, Ajay Jindoliya; Vikas, Munesh; Rohit, Naveen, Yamini, Ajay Jangid
03/10/2026, Govind; Rohit, Shiv; Vishal, Kuldeep; Ram, Naveen, Ajay Jangid; Yamini, -
```

---

## 3. Firebase Console Manual Setup (Admin Authorization)

To authorize an administrator identity in Firestore:

1. Open the [Firebase Console](https://console.firebase.google.com/).
2. Navigate to **Firestore Database** -> **Data**.
3. Create a collection named `admins` (if not already created).
4. Add a document whose **Document ID** is the administrator's Firebase Authentication UID:
   - **Collection**: `admins`
   - **Document ID**: `<ADMIN_USER_UID>` (e.g., obtained from Firebase Auth -> Users tab)
   - **Fields**:
     ```json
     {
       "email": "admin@hospital.com",
       "role": "admin",
       "createdAt": 1729000000000
     }
     ```
5. Deploy the updated `firestore.rules`:
   ```bash
   firebase deploy --only firestore:rules
   ```

*Security Note*: Firestore rules prevent client-side write access to `/admins/{uid}` (`allow write: if false;`), ensuring that only backend administrators using the Firebase Console or Admin SDK can assign the admin role.

---

## 4. Admin Workflow (Publishing)

1. Sign in with the authorized admin account.
2. The Dashboard displays the **Admin Roster Portal** card.
3. Open the portal and select the roster Month & Year.
4. Tap **Choose Excel / CSV File** and select your roster spreadsheet.
5. The system performs instant client-side validation:
   - Validates date ranges and shift columns.
   - Warns about ambiguous names, duplicate cell bookings, or unrecognized staff.
   - Generates an interactive day-by-day preview with colleagues and shift timings.
6. Actions:
   - **Save Draft**: Saves the schedule as an unpublished draft (`/monthly_rosters/{year_month}_draft`).
   - **Publish Roster**: Confirms publication, increments the version number (e.g. `v1` → `v2`), and publishes to `/monthly_rosters/{year_month}`.

---

## 5. User Workflow (Calendar Import)

1. Clinical staff members open the app and tap **Import Published Roster** on the Dashboard.
2. The app queries Firestore for the latest published roster for the selected month:
   - If not yet published, displays: *"Roster not updated yet. Please check again later."*
   - If published, displays the version badge, publication timestamp, and approved staff directory.
3. The user selects their name (profile matching highlights their name automatically).
4. The user reviews extracted duties:
   - Exact dates, shift types (Morning, Evening, Night, Off, Leave), start/end times, and coworkers.
   - Off and Leave shifts are maintained with zero working hours and appropriate status tags.
5. Selects conflict handling:
   - *Merge & Update Conflicts (Default)*
   - *Replace entire month range*
   - *Keep existing entries on conflict*
6. Taps **Import Duties to Calendar**:
   - Saves to the user's local Room SQLite database and personal path `/users/{uid}/duties`.
   - Re-importing the same roster version never creates duplicate entries.
   - Shift alarms and persistent status notifications are updated automatically.
