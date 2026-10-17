package com.nxtshift.app.data.remote;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreSettings;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.WriteBatch;
import com.nxtshift.app.data.local.StaffMemberEntity;
import com.nxtshift.app.data.local.UserProfileEntity;
import com.nxtshift.app.domain.Duty;
import com.nxtshift.app.domain.DutyStatus;
import com.nxtshift.app.domain.ShiftType;
import com.nxtshift.app.domain.SyncStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Encapsulates all Firestore remote interactions under the user's isolated path: users/{uid}/*
 */
public class FirestoreService {
    private static final String TAG = "FirestoreService";

    private static final String COLLECTION_USERS = "users";
    private static final String COLLECTION_DUTIES = "duties";
    private static final String COLLECTION_STAFF = "staff";
    private static final String COLLECTION_PROFILE = "profile";
    private static final String DOC_MAIN_PROFILE = "main";
    private static final String COLLECTION_SETTINGS = "settings";
    private static final String DOC_PREFERENCES = "preferences";

    private final FirebaseFirestore db;
    private final FirebaseAuth auth;

    public interface FirestoreCallback<T> {
        void onSuccess(T result);
        void onError(Exception e);
    }

    public FirestoreService() {
        this.db = FirebaseFirestore.getInstance();
        this.auth = FirebaseAuth.getInstance();
        
        try {
            FirebaseFirestoreSettings settings = new FirebaseFirestoreSettings.Builder()
                    .setPersistenceEnabled(true)
                    .build();
            this.db.setFirestoreSettings(settings);
        } catch (Exception e) {
            Log.d(TAG, "Firestore settings notice: " + e.getMessage());
        }
    }

    public String getCurrentUserUid() {
        FirebaseUser user = auth.getCurrentUser();
        return user != null ? user.getUid() : null;
    }

    private CollectionReference getDutiesCollection(String uid) {
        return db.collection(COLLECTION_USERS).document(uid).collection(COLLECTION_DUTIES);
    }

    private CollectionReference getStaffCollection(String uid) {
        return db.collection(COLLECTION_USERS).document(uid).collection(COLLECTION_STAFF);
    }

    private DocumentReference getProfileDocument(String uid) {
        return db.collection(COLLECTION_USERS).document(uid)
                .collection(COLLECTION_PROFILE).document(DOC_MAIN_PROFILE);
    }

    public void uploadStaffBatch(@NonNull String uid, @NonNull List<StaffMemberEntity> staffList, final FirestoreCallback<Void> callback) {
        if (staffList.isEmpty()) {
            if (callback != null) callback.onSuccess(null);
            return;
        }

        WriteBatch batch = db.batch();
        for (StaffMemberEntity s : staffList) {
            DocumentReference docRef = getStaffCollection(uid).document(s.getId());
            Map<String, Object> map = new HashMap<>();
            map.put("id", s.getId());
            map.put("ownerUid", uid);
            map.put("name", s.getName());
            map.put("normalizedName", s.getNormalizedName());
            map.put("aliases", s.getAliases());
            map.put("department", s.getDepartment());
            map.put("role", s.getRole());
            map.put("dutyCount", s.getDutyCount());
            map.put("createdAt", s.getCreatedAt());
            map.put("updatedAt", s.getUpdatedAt());
            batch.set(docRef, map);
        }

        batch.commit()
                .addOnSuccessListener(aVoid -> {
                    if (callback != null) callback.onSuccess(null);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Staff batch upload failed", e);
                    if (callback != null) callback.onError(e);
                });
    }

    public void fetchAllStaff(@NonNull String uid, final FirestoreCallback<List<StaffMemberEntity>> callback) {
        getStaffCollection(uid)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    List<StaffMemberEntity> list = new ArrayList<>();
                    for (DocumentSnapshot doc : queryDocumentSnapshots) {
                        if (doc.exists()) {
                            String id = doc.getString("id");
                            if (id == null) id = doc.getId();
                            String name = doc.getString("name");
                            if (name == null) name = "";
                            String norm = doc.getString("normalizedName");
                            if (norm == null) norm = name.toLowerCase().replaceAll("[^a-z0-9]", "");
                            String aliases = doc.getString("aliases");
                            String dept = doc.getString("department");
                            String role = doc.getString("role");
                            Long count = doc.getLong("dutyCount");
                            Long created = doc.getLong("createdAt");
                            Long updated = doc.getLong("updatedAt");
                            list.add(new StaffMemberEntity(
                                    id,
                                    uid,
                                    name,
                                    norm,
                                    aliases != null ? aliases : "",
                                    dept != null ? dept : "",
                                    role != null ? role : "Nurse / Staff",
                                    count != null ? count.intValue() : 0,
                                    created != null ? created : System.currentTimeMillis(),
                                    updated != null ? updated : System.currentTimeMillis(),
                                    "SYNCED"
                            ));
                        }
                    }
                    if (callback != null) callback.onSuccess(list);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to fetch staff for user: " + uid, e);
                    if (callback != null) callback.onError(e);
                });
    }

    public void uploadDuty(@NonNull Duty duty, final FirestoreCallback<Void> callback) {
        String uid = duty.getOwnerUid();
        if (uid == null || uid.isEmpty()) {
            if (callback != null) callback.onError(new IllegalStateException("Missing owner UID for duty"));
            return;
        }

        Map<String, Object> data = mapDutyToFirestore(duty);
        getDutiesCollection(uid).document(duty.getId())
                .set(data)
                .addOnSuccessListener(aVoid -> {
                    if (callback != null) callback.onSuccess(null);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to upload duty: " + duty.getId(), e);
                    if (callback != null) callback.onError(e);
                });
    }

    public void uploadDutiesBatch(@NonNull List<Duty> duties, final FirestoreCallback<Void> callback) {
        if (duties.isEmpty()) {
            if (callback != null) callback.onSuccess(null);
            return;
        }

        String uid = duties.get(0).getOwnerUid();
        if (uid == null || uid.isEmpty()) {
            if (callback != null) callback.onError(new IllegalStateException("Missing owner UID"));
            return;
        }

        WriteBatch batch = db.batch();
        for (Duty duty : duties) {
            DocumentReference docRef = getDutiesCollection(uid).document(duty.getId());
            batch.set(docRef, mapDutyToFirestore(duty));
        }

        batch.commit()
                .addOnSuccessListener(aVoid -> {
                    if (callback != null) callback.onSuccess(null);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Batch upload failed", e);
                    if (callback != null) callback.onError(e);
                });
    }

    public void deleteDuty(@NonNull String dutyId, @NonNull String uid, final FirestoreCallback<Void> callback) {
        getDutiesCollection(uid).document(dutyId)
                .delete()
                .addOnSuccessListener(aVoid -> {
                    if (callback != null) callback.onSuccess(null);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to delete duty: " + dutyId, e);
                    if (callback != null) callback.onError(e);
                });
    }

    public void fetchAllDuties(@NonNull String uid, final FirestoreCallback<List<Duty>> callback) {
        getDutiesCollection(uid)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    List<Duty> duties = new ArrayList<>();
                    for (DocumentSnapshot doc : queryDocumentSnapshots) {
                        Duty duty = mapFirestoreToDuty(doc, uid);
                        if (duty != null) {
                            duties.add(duty);
                        }
                    }
                    if (callback != null) callback.onSuccess(duties);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to fetch duties for user: " + uid, e);
                    if (callback != null) callback.onError(e);
                });
    }

    public ListenerRegistration listenToDuties(@NonNull String uid, final FirestoreCallback<List<Duty>> callback) {
        return getDutiesCollection(uid)
                .addSnapshotListener((queryDocumentSnapshots, error) -> {
                    if (error != null) {
                        Log.e(TAG, "Listen failed for duties", error);
                        if (callback != null) callback.onError(error);
                        return;
                    }

                    if (queryDocumentSnapshots != null) {
                        List<Duty> duties = new ArrayList<>();
                        for (DocumentSnapshot doc : queryDocumentSnapshots) {
                            Duty duty = mapFirestoreToDuty(doc, uid);
                            if (duty != null) {
                                duties.add(duty);
                            }
                        }
                        if (callback != null) callback.onSuccess(duties);
                    }
                });
    }

    public void saveUserProfile(@NonNull UserProfileEntity profile, final FirestoreCallback<Void> callback) {
        Map<String, Object> map = new HashMap<>();
        map.put("uid", profile.getUid());
        map.put("phoneNumber", profile.getPhoneNumber());
        map.put("displayName", profile.getDisplayName());
        map.put("hospitalName", profile.getHospitalName());
        map.put("defaultWard", profile.getDefaultWard());
        map.put("updatedAt", System.currentTimeMillis());

        getProfileDocument(profile.getUid())
                .set(map)
                .addOnSuccessListener(aVoid -> {
                    if (callback != null) callback.onSuccess(null);
                })
                .addOnFailureListener(e -> {
                    if (callback != null) callback.onError(e);
                });
    }

    public void fetchUserProfile(@NonNull String uid, final FirestoreCallback<UserProfileEntity> callback) {
        getProfileDocument(uid)
                .get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists()) {
                        UserProfileEntity p = new UserProfileEntity(
                                uid,
                                doc.getString("email"),
                                doc.getString("phoneNumber"),
                                doc.getString("displayName"),
                                doc.getString("hospitalName"),
                                doc.getString("defaultWard"),
                                doc.contains("createdAt") && doc.getLong("createdAt") != null ? doc.getLong("createdAt") : System.currentTimeMillis(),
                                doc.contains("updatedAt") && doc.getLong("updatedAt") != null ? doc.getLong("updatedAt") : System.currentTimeMillis()
                        );
                        if (callback != null) callback.onSuccess(p);
                    } else {
                        if (callback != null) callback.onSuccess(null);
                    }
                })
                .addOnFailureListener(e -> {
                    if (callback != null) callback.onError(e);
                });
    }

    /**
     * Purges all remote data belonging to the user when account deletion is requested.
     */
    public void deleteEntireUserAccountData(@NonNull final String uid, final FirestoreCallback<Void> callback) {
        // 1. Delete all duty documents
        getDutiesCollection(uid).get().addOnCompleteListener(task -> {
            if (!task.isSuccessful() || task.getResult() == null) {
                if (callback != null) callback.onError(task.getException());
                return;
            }

            WriteBatch batch = db.batch();
            for (DocumentSnapshot doc : task.getResult()) {
                batch.delete(doc.getReference());
            }

            // 2. Delete staff documents
            getStaffCollection(uid).get().addOnCompleteListener(staffTask -> {
                if (staffTask.isSuccessful() && staffTask.getResult() != null) {
                    for (DocumentSnapshot doc : staffTask.getResult()) {
                        batch.delete(doc.getReference());
                    }
                }

                // 3. Delete profile doc & user root doc
                batch.delete(getProfileDocument(uid));
                batch.delete(db.collection(COLLECTION_USERS).document(uid)
                        .collection(COLLECTION_SETTINGS).document(DOC_PREFERENCES));
                batch.delete(db.collection(COLLECTION_USERS).document(uid));

                batch.commit()
                        .addOnSuccessListener(aVoid -> {
                            Log.i(TAG, "All Firestore records purged for UID: " + uid);
                            if (callback != null) callback.onSuccess(null);
                        })
                        .addOnFailureListener(e -> {
                            Log.e(TAG, "Failed purging Firestore account data", e);
                            if (callback != null) callback.onError(e);
                        });
            });
        });
    }

    private Map<String, Object> mapDutyToFirestore(Duty duty) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", duty.getId());
        data.put("ownerUid", duty.getOwnerUid());
        data.put("dutyDate", duty.getDutyDate());
        data.put("shiftType", duty.getShiftType().name());
        data.put("shiftName", duty.getShiftName());
        data.put("startTime", duty.getStartTime());
        data.put("endTime", duty.getEndTime());
        data.put("isOvernight", duty.isOvernight());
        data.put("colleagues", duty.getColleagues());
        data.put("colleagueRoles", duty.getColleagueRoles());
        data.put("wardOrDepartment", duty.getWardOrDepartment());
        data.put("notes", duty.getNotes());
        data.put("status", duty.getStatus().name());
        data.put("createdAt", duty.getCreatedAt());
        data.put("updatedAt", duty.getUpdatedAt());
        return data;
    }

    private Duty mapFirestoreToDuty(DocumentSnapshot doc, String uid) {
        if (!doc.exists()) return null;
        try {
            String id = doc.getString("id");
            if (id == null) id = doc.getId();

            String ownerUid = doc.getString("ownerUid");
            if (ownerUid == null) ownerUid = uid;

            String dutyDate = doc.getString("dutyDate");
            String shiftTypeName = doc.getString("shiftType");
            ShiftType shiftType = ShiftType.MORNING;
            if (shiftTypeName != null) {
                try {
                    shiftType = ShiftType.valueOf(shiftTypeName);
                } catch (Exception ignored) {}
            }

            String shiftName = doc.getString("shiftName");
            String startTime = doc.getString("startTime");
            String endTime = doc.getString("endTime");
            Boolean isOvernight = doc.getBoolean("isOvernight");
            String colleagues = doc.getString("colleagues");
            String colleagueRoles = doc.getString("colleagueRoles");
            String wardOrDepartment = doc.getString("wardOrDepartment");
            String notes = doc.getString("notes");

            String statusName = doc.getString("status");
            DutyStatus status = DutyStatus.SCHEDULED;
            if (statusName != null) {
                try {
                    status = DutyStatus.valueOf(statusName);
                } catch (Exception ignored) {}
            }

            Long createdAt = doc.getLong("createdAt");
            Long updatedAt = doc.getLong("updatedAt");

            return new Duty(
                    id,
                    ownerUid,
                    dutyDate,
                    shiftType,
                    shiftName,
                    startTime,
                    endTime,
                    isOvernight != null && isOvernight,
                    colleagues,
                    colleagueRoles,
                    wardOrDepartment,
                    notes,
                    status,
                    createdAt != null ? createdAt : System.currentTimeMillis(),
                    updatedAt != null ? updatedAt : System.currentTimeMillis(),
                    SyncStatus.SYNCED
            );
        } catch (Exception e) {
            Log.e(TAG, "Error deserializing duty: " + doc.getId(), e);
            return null;
        }
    }
}
