package com.nxtshift.app.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import com.nxtshift.app.data.model.AccessState;
import com.nxtshift.app.data.model.GlobalAccessConfig;
import com.nxtshift.app.data.model.UserAccessRecord;

import java.util.Map;

/**
 * Centralized client-side access control gate.
 * Monitors global application availability (/app_control/global) and individual user
 * revocation status (/app_access/{uid}) in Cloud Firestore.
 *
 * Cloud operations must check this repository before mutating or querying remote data.
 * Offline calendar and cached duties remain readable regardless of remote status.
 */
public class AccessControlRepository {
    private static final String TAG = "AccessControlRepo";

    public static final String COLLECTION_APP_CONTROL = "app_control";
    public static final String DOC_GLOBAL = "global";
    public static final String COLLECTION_APP_ACCESS = "app_access";

    private static volatile AccessControlRepository INSTANCE;

    private final FirebaseFirestore db;
    private final FirebaseAuth auth;
    private final Handler mainHandler;

    private final MutableLiveData<AccessState> accessStateLiveData = new MutableLiveData<>(AccessState.OFFLINE_UNVERIFIED);
    private final MutableLiveData<GlobalAccessConfig> globalConfigLiveData = new MutableLiveData<>();
    private final MutableLiveData<UserAccessRecord> userAccessLiveData = new MutableLiveData<>();

    private ListenerRegistration globalListenerRegistration;
    private ListenerRegistration userListenerRegistration;

    private String monitoredUid;
    private GlobalAccessConfig currentGlobalConfig;
    private UserAccessRecord currentUserAccess;
    private boolean isOffline = false;
    private boolean isGlobalServerConfirmed = false;
    private boolean isUserServerConfirmed = false;

    public interface AccessCheckCallback {
        void onAllowed();
        void onBlocked(AccessState state, String message);
    }

    public interface ActionCallback<T> {
        void onSuccess(T result);
        void onError(Exception e);
    }

    public static AccessControlRepository getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AccessControlRepository.class) {
                if (INSTANCE == null) {
                    INSTANCE = new AccessControlRepository(
                            FirebaseFirestore.getInstance(),
                            FirebaseAuth.getInstance()
                    );
                }
            }
        }
        return INSTANCE;
    }

    // Testing / Dependency Injection constructor
    public AccessControlRepository(FirebaseFirestore db, FirebaseAuth auth) {
        this.db = db;
        this.auth = auth;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public LiveData<AccessState> getAccessStateLiveData() {
        return accessStateLiveData;
    }

    public LiveData<GlobalAccessConfig> getGlobalConfigLiveData() {
        return globalConfigLiveData;
    }

    public LiveData<UserAccessRecord> getUserAccessLiveData() {
        return userAccessLiveData;
    }

    private AccessState testAccessState = null;
    private AccessState currentEvaluatedState = AccessState.OFFLINE_UNVERIFIED;

    public synchronized AccessState getCurrentAccessState() {
        if (testAccessState != null) {
            return testAccessState;
        }
        return currentEvaluatedState;
    }

    public synchronized GlobalAccessConfig getCurrentGlobalConfig() {
        return currentGlobalConfig;
    }

    public synchronized UserAccessRecord getCurrentUserAccess() {
        return currentUserAccess;
    }

    public synchronized boolean isServerConfirmed() {
        return isGlobalServerConfirmed && isUserServerConfirmed && !isOffline;
    }

    /**
     * Starts real-time observation of global and user access control documents.
     */
    public synchronized void startMonitoring(String uid) {
        if (uid == null || uid.trim().isEmpty()) {
            stopMonitoring();
            return;
        }

        if (uid.equals(monitoredUid) && globalListenerRegistration != null && userListenerRegistration != null) {
            return; // Already actively listening for this user
        }

        stopMonitoring();
        this.monitoredUid = uid;
        this.isGlobalServerConfirmed = false;
        this.isUserServerConfirmed = false;
        postState(AccessState.OFFLINE_UNVERIFIED);

        // 1. Observe /app_control/global
        DocumentReference globalRef = db.collection(COLLECTION_APP_CONTROL).document(DOC_GLOBAL);
        globalListenerRegistration = globalRef.addSnapshotListener((snapshot, error) -> {
            if (error != null) {
                handleListenerError(error);
                return;
            }
            handleGlobalSnapshot(snapshot);
        });

        // 2. Observe /app_access/{uid}
        DocumentReference userRef = db.collection(COLLECTION_APP_ACCESS).document(uid);
        userListenerRegistration = userRef.addSnapshotListener((snapshot, error) -> {
            if (error != null) {
                handleListenerError(error);
                return;
            }
            handleUserSnapshot(uid, snapshot);
        });
    }

    /**
     * Stops all active Firestore access listeners.
     */
    public synchronized void stopMonitoring() {
        if (globalListenerRegistration != null) {
            globalListenerRegistration.remove();
            globalListenerRegistration = null;
        }
        if (userListenerRegistration != null) {
            userListenerRegistration.remove();
            userListenerRegistration = null;
        }
        monitoredUid = null;
        isGlobalServerConfirmed = false;
        isUserServerConfirmed = false;
        isOffline = false;
    }

    public synchronized void handleGlobalSnapshot(DocumentSnapshot snapshot) {
        boolean fromCache = (snapshot != null && snapshot.getMetadata() != null && snapshot.getMetadata().isFromCache());
        isGlobalServerConfirmed = !fromCache;
        if (!fromCache) {
            isOffline = false;
        }

        if (snapshot == null || !snapshot.exists()) {
            // Missing control document -> fail-closed: global disabled by default until initialized
            currentGlobalConfig = new GlobalAccessConfig(false, "Application control configuration not found.", System.currentTimeMillis(), "", 1);
        } else {
            Map<String, Object> data = snapshot.getData();
            currentGlobalConfig = GlobalAccessConfig.fromMap(data);
        }
        mainHandler.post(() -> globalConfigLiveData.setValue(currentGlobalConfig));
        evaluateState();
    }

    public synchronized void handleUserSnapshot(String uid, DocumentSnapshot snapshot) {
        boolean fromCache = (snapshot != null && snapshot.getMetadata() != null && snapshot.getMetadata().isFromCache());
        isUserServerConfirmed = !fromCache;
        if (!fromCache) {
            isOffline = false;
        }

        if (snapshot == null || !snapshot.exists()) {
            // No individual revocation record -> user is permitted individually
            currentUserAccess = new UserAccessRecord(uid, true, "", System.currentTimeMillis(), "");
        } else {
            Map<String, Object> data = snapshot.getData();
            currentUserAccess = UserAccessRecord.fromMap(uid, data);
        }
        mainHandler.post(() -> userAccessLiveData.setValue(currentUserAccess));
        evaluateState();
    }

    public synchronized void handleListenerError(FirebaseFirestoreException error) {
        Log.w(TAG, "Access control listener notice: " + error.getMessage());
        isGlobalServerConfirmed = false;
        isUserServerConfirmed = false;
        if (error.getCode() == FirebaseFirestoreException.Code.UNAVAILABLE ||
                error.getCause() instanceof FirebaseNetworkException) {
            isOffline = true;
            evaluateState();
        } else if (error.getCode() == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
            // Permission denied -> fail-closed
            postState(AccessState.ERROR);
        } else {
            evaluateState();
        }
    }

    /**
     * Evaluates current access state following fail-closed security principles.
     */
    public synchronized void evaluateState() {
        // 1. Any confirmed OR cached revocation / global maintenance mode immediately blocks cloud access (fail-closed)
        if (currentGlobalConfig != null && !currentGlobalConfig.isEnabled()) {
            postState(AccessState.GLOBAL_DISABLED);
            return;
        }

        if (currentUserAccess != null && !currentUserAccess.isEnabled()) {
            postState(AccessState.USER_REVOKED);
            return;
        }

        // 2. If offline, or if either global or user state has not been confirmed directly by the live server,
        // cloud operations must remain unverified and blocked. Cached 'ALLOWED' data is never trusted.
        if (isOffline || !isGlobalServerConfirmed || !isUserServerConfirmed) {
            postState(AccessState.OFFLINE_UNVERIFIED);
            return;
        }

        if (currentGlobalConfig == null || currentUserAccess == null) {
            postState(AccessState.OFFLINE_UNVERIFIED);
            return;
        }

        // 3. Only when both global availability and user authorization are confirmed enabled by the live server
        postState(AccessState.ALLOWED);
    }

    private void postState(AccessState state) {
        this.currentEvaluatedState = state;
        if (mainHandler == null || Looper.getMainLooper() == null) {
            try {
                accessStateLiveData.setValue(state);
            } catch (Exception ignored) {}
        } else {
            mainHandler.post(() -> accessStateLiveData.setValue(state));
        }
    }

    /**
     * Single check method before executing protected cloud reads or writes.
     * Fails closed if access is disabled, revoked, or unverified.
     */
    public void checkCloudAccess(@NonNull AccessCheckCallback callback) {
        AccessState state = getCurrentAccessState();
        if (state == AccessState.ALLOWED) {
            callback.onAllowed();
            return;
        }

        GlobalAccessConfig globalConfig = getCurrentGlobalConfig();
        UserAccessRecord userAccess = getCurrentUserAccess();

        String message;
        switch (state) {
            case GLOBAL_DISABLED:
                message = globalConfig != null && globalConfig.getMessage() != null && !globalConfig.getMessage().isEmpty()
                        ? globalConfig.getMessage()
                        : "NXTShift is currently offline for maintenance.";
                break;
            case USER_REVOKED:
                message = userAccess != null && userAccess.getReason() != null && !userAccess.getReason().isEmpty()
                        ? userAccess.getReason()
                        : "Access to online features has been revoked by an administrator.";
                break;
            case OFFLINE_UNVERIFIED:
                message = "Device is offline. Remote cloud operations require an active internet connection to verify access permissions.";
                break;
            case ERROR:
            default:
                message = "Access verification error. Protected cloud operations are temporarily suspended.";
                break;
        }

        callback.onBlocked(state, message);
    }

    // Testing helper setters
    public synchronized void setAccessStateForTesting(AccessState state) {
        this.testAccessState = state;
    }

    public synchronized void setGlobalConfigForTesting(GlobalAccessConfig config) {
        this.currentGlobalConfig = config;
    }

    public synchronized void setUserAccessForTesting(UserAccessRecord record) {
        this.currentUserAccess = record;
    }

    public synchronized void setServerConfirmedForTesting(boolean globalConfirmed, boolean userConfirmed) {
        this.isGlobalServerConfirmed = globalConfirmed;
        this.isUserServerConfirmed = userConfirmed;
    }

    public synchronized void setOfflineForTesting(boolean offline) {
        this.isOffline = offline;
    }

    // --- Administrator Remote Control APIs ---

    /**
     * Fetches current global config directly. Admin or authenticated user check.
     */
    public void fetchGlobalAccessConfig(@NonNull ActionCallback<GlobalAccessConfig> callback) {
        db.collection(COLLECTION_APP_CONTROL).document(DOC_GLOBAL)
                .get()
                .addOnSuccessListener(snapshot -> {
                    if (snapshot != null && snapshot.exists()) {
                        GlobalAccessConfig config = GlobalAccessConfig.fromMap(snapshot.getData());
                        mainHandler.post(() -> callback.onSuccess(config));
                    } else {
                        // Return default disabled or prompt initialization
                        mainHandler.post(() -> callback.onSuccess(new GlobalAccessConfig(false, "Not configured", 0, "", 1)));
                    }
                })
                .addOnFailureListener(e -> mainHandler.post(() -> callback.onError(e)));
    }

    /**
     * Updates global app access status. Requires administrator authorization.
     */
    public void setGlobalAccess(boolean enabled, String message, String adminUid, @NonNull ActionCallback<Void> callback) {
        GlobalAccessConfig config = new GlobalAccessConfig(
                enabled,
                message != null ? message.trim() : "",
                System.currentTimeMillis(),
                adminUid,
                1
        );

        db.collection(COLLECTION_APP_CONTROL).document(DOC_GLOBAL)
                .set(config.toMap())
                .addOnSuccessListener(aVoid -> {
                    Log.i(TAG, "Global access updated: enabled=" + enabled + " by admin=" + adminUid);
                    mainHandler.post(() -> callback.onSuccess(null));
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Failed to update global access: " + e.getMessage());
                    mainHandler.post(() -> callback.onError(e));
                });
    }

    /**
     * Fetches an individual user's access record. Requires administrator authorization.
     */
    public void fetchUserAccess(String targetUid, @NonNull ActionCallback<UserAccessRecord> callback) {
        if (targetUid == null || targetUid.trim().isEmpty()) {
            mainHandler.post(() -> callback.onError(new IllegalArgumentException("Target UID cannot be empty")));
            return;
        }

        db.collection(COLLECTION_APP_ACCESS).document(targetUid.trim())
                .get()
                .addOnSuccessListener(snapshot -> {
                    if (snapshot != null && snapshot.exists()) {
                        UserAccessRecord record = UserAccessRecord.fromMap(targetUid, snapshot.getData());
                        mainHandler.post(() -> callback.onSuccess(record));
                    } else {
                        // User has no revocation record -> defaults to enabled
                        mainHandler.post(() -> callback.onSuccess(new UserAccessRecord(targetUid, true, "No restrictions recorded", 0, "")));
                    }
                })
                .addOnFailureListener(e -> mainHandler.post(() -> callback.onError(e)));
    }

    /**
     * Sets an individual user's access status (Revoke / Restore). Requires administrator authorization.
     */
    public void setUserAccess(String targetUid, boolean enabled, String reason, String adminUid, @NonNull ActionCallback<Void> callback) {
        if (targetUid == null || targetUid.trim().isEmpty()) {
            mainHandler.post(() -> callback.onError(new IllegalArgumentException("Target UID cannot be empty")));
            return;
        }

        UserAccessRecord record = new UserAccessRecord(
                targetUid.trim(),
                enabled,
                reason != null ? reason.trim() : "",
                System.currentTimeMillis(),
                adminUid
        );

        db.collection(COLLECTION_APP_ACCESS).document(targetUid.trim())
                .set(record.toMap())
                .addOnSuccessListener(aVoid -> {
                    Log.i(TAG, "User access updated for " + targetUid + ": enabled=" + enabled + " by admin=" + adminUid);
                    mainHandler.post(() -> callback.onSuccess(null));
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Failed to update user access for " + targetUid + ": " + e.getMessage());
                    mainHandler.post(() -> callback.onError(e));
                });
    }
}
