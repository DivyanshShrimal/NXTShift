package com.nxtshift.app.data.remote;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import com.nxtshift.app.data.model.AccessState;
import com.nxtshift.app.data.model.MonthlyRoster;
import com.nxtshift.app.data.repository.AccessControlRepository;

import java.util.Map;

/**
 * Remote service managing the central hospital roster publishing system.
 * Enforces admin authorization verification and versioned roster publication in Firestore.
 */
public class CentralRosterService {
    private static final String TAG = "CentralRosterService";

    public static final String COLLECTION_ADMINS = "admins";
    public static final String COLLECTION_MONTHLY_ROSTERS = "monthly_rosters";

    private final FirebaseFirestore db;
    private final FirebaseAuth auth;
    private final Handler mainHandler;
    private AccessControlRepository accessControlRepository;

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(Exception e);
    }

    public CentralRosterService() {
        this(FirebaseFirestore.getInstance(), FirebaseAuth.getInstance(), null);
    }

    public CentralRosterService(AccessControlRepository accessControlRepository) {
        this(FirebaseFirestore.getInstance(), FirebaseAuth.getInstance(), accessControlRepository);
    }

    // Testing / Dependency Injection constructor
    public CentralRosterService(FirebaseFirestore db, FirebaseAuth auth) {
        this(db, auth, null);
    }

    public CentralRosterService(FirebaseFirestore db, FirebaseAuth auth, AccessControlRepository accessControlRepository) {
        this.db = db;
        this.auth = auth;
        this.accessControlRepository = accessControlRepository;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setAccessControlRepository(AccessControlRepository accessControlRepository) {
        this.accessControlRepository = accessControlRepository;
    }

    public AccessControlRepository getAccessControlRepository() {
        return accessControlRepository;
    }

    public String getCurrentUserUid() {
        FirebaseUser user = auth.getCurrentUser();
        return user != null ? user.getUid() : null;
    }

    public String getCurrentUserEmail() {
        FirebaseUser user = auth.getCurrentUser();
        return user != null ? user.getEmail() : null;
    }

    /**
     * Verifies if the specified UID has administrator privileges.
     * Checks server-side /admins/{uid} document.
     */
    public void checkIsAdmin(String uid, @NonNull final Callback<Boolean> callback) {
        if (uid == null || uid.trim().isEmpty()) {
            postSuccess(callback, false);
            return;
        }

        db.collection(COLLECTION_ADMINS).document(uid)
                .get()
                .addOnSuccessListener(documentSnapshot -> {
                    boolean isAdmin = documentSnapshot != null && documentSnapshot.exists();
                    postSuccess(callback, isAdmin);
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Admin verification failed or access denied: " + e.getMessage());
                    postSuccess(callback, false);
                });
    }

    /**
     * Fetches the published roster for a specific month and year.
     * Returns null if no published roster exists.
     */
    public void fetchPublishedRoster(int year, int month, @NonNull final Callback<MonthlyRoster> callback) {
        String rosterId = MonthlyRoster.formatRosterId(year, month);
        db.collection(COLLECTION_MONTHLY_ROSTERS).document(rosterId)
                .get()
                .addOnSuccessListener(doc -> {
                    if (doc != null && doc.exists()) {
                        Map<String, Object> data = doc.getData();
                        if (data != null) {
                            MonthlyRoster roster = MonthlyRoster.fromFirestoreMap(data);
                            if (MonthlyRoster.STATUS_PUBLISHED.equals(roster.getStatus())) {
                                postSuccess(callback, roster);
                                return;
                            }
                        }
                    }
                    postSuccess(callback, null);
                })
                .addOnFailureListener(e -> postError(callback, e));
    }

    /**
     * Fetches the latest published roster across all months.
     */
    public void fetchLatestPublishedRoster(@NonNull final Callback<MonthlyRoster> callback) {
        db.collection(COLLECTION_MONTHLY_ROSTERS)
                .whereEqualTo("status", MonthlyRoster.STATUS_PUBLISHED)
                .orderBy("year", Query.Direction.DESCENDING)
                .orderBy("month", Query.Direction.DESCENDING)
                .limit(1)
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    if (querySnapshot != null && !querySnapshot.isEmpty()) {
                        DocumentSnapshot doc = querySnapshot.getDocuments().get(0);
                        MonthlyRoster roster = MonthlyRoster.fromFirestoreMap(doc.getData());
                        postSuccess(callback, roster);
                    } else {
                        postSuccess(callback, null);
                    }
                })
                .addOnFailureListener(e -> postError(callback, e));
    }

    /**
     * Admin action: Fetches saved draft for the given month and year.
     */
    public void fetchDraftRoster(int year, int month, @NonNull final Callback<MonthlyRoster> callback) {
        String draftId = MonthlyRoster.formatRosterId(year, month) + "_draft";
        db.collection(COLLECTION_MONTHLY_ROSTERS).document(draftId)
                .get()
                .addOnSuccessListener(doc -> {
                    if (doc != null && doc.exists()) {
                        Map<String, Object> data = doc.getData();
                        if (data != null) {
                            MonthlyRoster roster = MonthlyRoster.fromFirestoreMap(data);
                            postSuccess(callback, roster);
                            return;
                        }
                    }
                    postSuccess(callback, null);
                })
                .addOnFailureListener(e -> postError(callback, e));
    }

    /**
     * Verifies both global availability and individual user authorization before executing administrative operations.
     * Fails closed on any network error, missing document, malformed data, or revocation.
     */
    private void verifyAccessBeforeOperation(@NonNull final Callback<?> callback, @NonNull final Runnable onAllowed) {
        String uid = getCurrentUserUid();
        if (uid == null || uid.trim().isEmpty()) {
            postError(callback, new SecurityException("Operation blocked: No authenticated user session found."));
            return;
        }

        if (accessControlRepository != null) {
            accessControlRepository.checkCloudAccess(new AccessControlRepository.AccessCheckCallback() {
                @Override
                public void onAllowed() {
                    onAllowed.run();
                }

                @Override
                public void onBlocked(AccessState state, String message) {
                    postError(callback, new SecurityException("Operation blocked by access control: " + state + " - " + message));
                }
            });
            return;
        }

        // Direct fail-closed check against Firestore when accessControlRepository is not injected
        db.collection("app_control").document("global").get()
                .addOnSuccessListener(globalDoc -> {
                    if (globalDoc == null || !globalDoc.exists()) {
                        postError(callback, new IllegalStateException("Operation blocked: Global control document (/app_control/global) does not exist."));
                        return;
                    }
                    Boolean globalEnabled = globalDoc.getBoolean("enabled");
                    if (!Boolean.TRUE.equals(globalEnabled)) {
                        String msg = globalDoc.getString("message");
                        String errorMsg = (msg != null && !msg.isEmpty()) ? msg : "Application is in global maintenance mode.";
                        postError(callback, new IllegalStateException("Operation blocked: " + errorMsg));
                        return;
                    }

                    // Global is enabled; now verify administrator is not individually revoked (/app_access/{uid})
                    db.collection("app_access").document(uid).get()
                            .addOnSuccessListener(userDoc -> {
                                if (userDoc != null && userDoc.exists()) {
                                    Boolean userEnabled = userDoc.getBoolean("enabled");
                                    if (Boolean.FALSE.equals(userEnabled)) {
                                        String reason = userDoc.getString("reason");
                                        String errorReason = (reason != null && !reason.isEmpty()) ? reason : "Administrative access has been revoked.";
                                        postError(callback, new SecurityException("Operation blocked: " + errorReason));
                                        return;
                                    }
                                }
                                onAllowed.run();
                            })
                            .addOnFailureListener(e -> postError(callback, new SecurityException("Operation blocked: Unable to verify user access authorization: " + e.getMessage(), e)));
                })
                .addOnFailureListener(e -> postError(callback, new SecurityException("Operation blocked: Unable to verify global application status: " + e.getMessage(), e)));
    }

    /**
     * Admin action: Saves roster draft without publishing.
     * Enforces fail-closed access gating on global and user access.
     */
    public void saveDraftRoster(@NonNull MonthlyRoster roster, @NonNull final Callback<Void> callback) {
        verifyAccessBeforeOperation(callback, () -> executeSaveDraftRoster(roster, callback));
    }

    private void executeSaveDraftRoster(@NonNull MonthlyRoster roster, @NonNull final Callback<Void> callback) {
        String draftId = MonthlyRoster.formatRosterId(roster.getYear(), roster.getMonth()) + "_draft";
        roster.setId(draftId);
        roster.setStatus(MonthlyRoster.STATUS_DRAFT);
        roster.setUpdatedAt(System.currentTimeMillis());

        db.collection(COLLECTION_MONTHLY_ROSTERS).document(draftId)
                .set(roster.toFirestoreMap())
                .addOnSuccessListener(aVoid -> postSuccess(callback, null))
                .addOnFailureListener(e -> postError(callback, e));
    }

    /**
     * Admin action: Publishes a monthly roster with incremental versioning.
     * Enforces fail-closed access gating on global and user access.
     */
    public void publishRoster(@NonNull MonthlyRoster roster,
                              String publishedByUid,
                              String publishedByEmail,
                              @NonNull final Callback<Integer> callback) {
        verifyAccessBeforeOperation(callback, () -> executePublishRoster(roster, publishedByUid, publishedByEmail, callback));
    }

    private void executePublishRoster(@NonNull MonthlyRoster roster,
                                     String publishedByUid,
                                     String publishedByEmail,
                                     @NonNull final Callback<Integer> callback) {
        final String pubId = MonthlyRoster.formatRosterId(roster.getYear(), roster.getMonth());
        final DocumentReference docRef = db.collection(COLLECTION_MONTHLY_ROSTERS).document(pubId);

        // 1. Fetch current published document to determine version number
        docRef.get().addOnSuccessListener(currentDoc -> {
            int newVersion = 1;
            if (currentDoc != null && currentDoc.exists()) {
                Object vObj = currentDoc.get("version");
                if (vObj instanceof Number) {
                    newVersion = ((Number) vObj).intValue() + 1;
                }
            }

            final int versionToSave = newVersion;
            roster.setId(pubId);
            roster.setStatus(MonthlyRoster.STATUS_PUBLISHED);
            roster.setVersion(versionToSave);
            long now = System.currentTimeMillis();
            roster.setPublishedAt(now);
            roster.setUpdatedAt(now);
            roster.setPublishedByUid(publishedByUid != null ? publishedByUid : getCurrentUserUid());
            roster.setPublishedByEmail(publishedByEmail != null ? publishedByEmail : getCurrentUserEmail());

            // 2. Commit publication to Firestore
            docRef.set(roster.toFirestoreMap())
                    .addOnSuccessListener(aVoid -> {
                        // 3. Clean up draft after successful publication (best effort)
                        String draftId = pubId + "_draft";
                        db.collection(COLLECTION_MONTHLY_ROSTERS).document(draftId).delete();

                        postSuccess(callback, versionToSave);
                    })
                    .addOnFailureListener(e -> postError(callback, e));

        }).addOnFailureListener(e -> postError(callback, e));
    }

    private <T> void postSuccess(Callback<T> callback, T result) {
        if (callback != null) {
            if (mainHandler == null || Looper.getMainLooper() == null) {
                callback.onSuccess(result);
            } else {
                mainHandler.post(() -> callback.onSuccess(result));
            }
        }
    }

    private <T> void postError(Callback<T> callback, Exception e) {
        if (callback != null) {
            if (mainHandler == null || Looper.getMainLooper() == null) {
                callback.onError(e);
            } else {
                mainHandler.post(() -> callback.onError(e));
            }
        }
    }
}
