package com.nxtshift.app.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Transformations;

import com.google.firebase.firestore.ListenerRegistration;
import com.nxtshift.app.data.local.DutyDao;
import com.nxtshift.app.data.local.DutyEntity;
import com.nxtshift.app.data.local.NXTShiftDatabase;
import com.nxtshift.app.data.remote.FirestoreService;
import com.nxtshift.app.domain.Duty;
import com.nxtshift.app.domain.SyncStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Offline-first repository coordinating Room database caching and Firestore cloud synchronization.
 * Strictly guarantees account data isolation by user UID.
 */
public class DutyRepository {
    private static final String TAG = "DutyRepository";
    private static volatile DutyRepository INSTANCE;

    public enum ImportConflictPolicy {
        MERGE_REPLACE_CONFLICTS,
        REPLACE_RANGE,
        KEEP_EXISTING_ON_CONFLICT
    }

    private final DutyDao dutyDao;
    private final FirestoreService firestoreService;
    private final ExecutorService diskExecutor;
    private final Handler mainHandler;
    private ListenerRegistration activeFirestoreListener;

    public interface RepositoryCallback<T> {
        void onComplete(T result);
        void onError(Exception e);
    }

    public static DutyRepository getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (DutyRepository.class) {
                if (INSTANCE == null) {
                    INSTANCE = new DutyRepository(
                            NXTShiftDatabase.getInstance(context).dutyDao(),
                            new FirestoreService()
                    );
                }
            }
        }
        return INSTANCE;
    }

    public DutyRepository(DutyDao dutyDao, FirestoreService firestoreService) {
        this.dutyDao = dutyDao;
        this.firestoreService = firestoreService;
        this.diskExecutor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    // --- Observable Queries Isolated by Owner UID ---

    public LiveData<List<Duty>> getAllDuties(String ownerUid) {
        return Transformations.map(dutyDao.getAllDutiesLiveData(ownerUid), entities -> {
            List<Duty> domainList = new ArrayList<>();
            if (entities != null) {
                for (DutyEntity entity : entities) {
                    domainList.add(entity.toDomain());
                }
            }
            return domainList;
        });
    }

    public LiveData<List<Duty>> getDutiesBetweenDates(String ownerUid, String startDate, String endDate) {
        return Transformations.map(dutyDao.getDutiesBetweenDatesLiveData(ownerUid, startDate, endDate), entities -> {
            List<Duty> domainList = new ArrayList<>();
            if (entities != null) {
                for (DutyEntity entity : entities) {
                    domainList.add(entity.toDomain());
                }
            }
            return domainList;
        });
    }

    public LiveData<Duty> getDutyForDate(String ownerUid, String date) {
        return Transformations.map(dutyDao.getDutyForDateLiveData(ownerUid, date), entity -> {
            if (entity != null) {
                return entity.toDomain();
            }
            return null;
        });
    }

    public LiveData<List<Duty>> getUpcomingDuties(String ownerUid, String fromDate, int limit) {
        return Transformations.map(dutyDao.getUpcomingDutiesLiveData(ownerUid, fromDate, limit), entities -> {
            List<Duty> domainList = new ArrayList<>();
            if (entities != null) {
                for (DutyEntity entity : entities) {
                    domainList.add(entity.toDomain());
                }
            }
            return domainList;
        });
    }

    // Direct synchronous query for background services / Alarm receiver
    public Duty getDutyForDateDirect(String ownerUid, String date) {
        DutyEntity entity = dutyDao.getDutyForDateDirect(ownerUid, date);
        return entity != null ? entity.toDomain() : null;
    }

    public List<Duty> getUpcomingDutiesDirect(String ownerUid, String fromDate, int limit) {
        List<DutyEntity> entities = dutyDao.getUpcomingDutiesDirect(ownerUid, fromDate, limit);
        List<Duty> list = new ArrayList<>();
        if (entities != null) {
            for (DutyEntity e : entities) {
                list.add(e.toDomain());
            }
        }
        return list;
    }

    // --- Save / Edit / Delete Operations ---

    public void saveDuty(final Duty duty, final RepositoryCallback<Duty> callback) {
        duty.setUpdatedAt(System.currentTimeMillis());
        duty.setSyncStatus(SyncStatus.PENDING_UPLOAD);

        diskExecutor.execute(() -> {
            try {
                // 1. Save to Room immediately
                dutyDao.insertOrReplace(DutyEntity.fromDomain(duty, duty.getOwnerUid()));

                if (callback != null) {
                    mainHandler.post(() -> callback.onComplete(duty));
                }

                // 2. Upload to Firestore
                firestoreService.uploadDuty(duty, new FirestoreService.FirestoreCallback<Void>() {
                    @Override
                    public void onSuccess(Void result) {
                        diskExecutor.execute(() -> {
                            duty.setSyncStatus(SyncStatus.SYNCED);
                            dutyDao.update(DutyEntity.fromDomain(duty, duty.getOwnerUid()));
                            Log.d(TAG, "Duty synced to Firestore: " + duty.getId());
                        });
                    }

                    @Override
                    public void onError(Exception e) {
                        Log.w(TAG, "Duty upload pending network: " + e.getMessage());
                        diskExecutor.execute(() -> {
                            duty.setSyncStatus(SyncStatus.PENDING_UPLOAD);
                            dutyDao.update(DutyEntity.fromDomain(duty, duty.getOwnerUid()));
                        });
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Error saving duty locally", e);
                if (callback != null) {
                    mainHandler.post(() -> callback.onError(e));
                }
            }
        });
    }

    public void deleteDuty(final Duty duty, final RepositoryCallback<Void> callback) {
        diskExecutor.execute(() -> {
            try {
                dutyDao.deleteDutyById(duty.getId(), duty.getOwnerUid());

                if (callback != null) {
                    mainHandler.post(() -> callback.onComplete(null));
                }

                firestoreService.deleteDuty(duty.getId(), duty.getOwnerUid(), new FirestoreService.FirestoreCallback<Void>() {
                    @Override
                    public void onSuccess(Void result) {
                        Log.d(TAG, "Deleted duty from Firestore: " + duty.getId());
                    }

                    @Override
                    public void onError(Exception e) {
                        Log.w(TAG, "Could not delete from Firestore immediately: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                if (callback != null) {
                    mainHandler.post(() -> callback.onError(e));
                }
            }
        });
    }

    // --- Roster Import & Conflict Handling ---

    public static class BulkImportResult {
        public final int totalImported;
        public final int createdCount;
        public final int updatedCount;
        public final int skippedCount;

        public BulkImportResult(int totalImported, int createdCount, int updatedCount, int skippedCount) {
            this.totalImported = totalImported;
            this.createdCount = createdCount;
            this.updatedCount = updatedCount;
            this.skippedCount = skippedCount;
        }
    }

    public void importRosterDutiesDetailed(final String ownerUid,
                                           final List<Duty> importedDuties,
                                           final ImportConflictPolicy policy,
                                           final String startDate,
                                           final String endDate,
                                           final RepositoryCallback<BulkImportResult> callback) {
        diskExecutor.execute(() -> {
            try {
                List<DutyEntity> toInsert = new ArrayList<>();
                long now = System.currentTimeMillis();
                int created = 0;
                int updated = 0;
                int skipped = 0;

                if (policy == ImportConflictPolicy.REPLACE_RANGE && startDate != null && endDate != null) {
                    dutyDao.deleteRangeByOwner(ownerUid, startDate, endDate);
                    for (Duty d : importedDuties) {
                        d.setOwnerUid(ownerUid);
                        d.setUpdatedAt(now);
                        d.setSyncStatus(SyncStatus.PENDING_UPLOAD);
                        toInsert.add(DutyEntity.fromDomain(d, ownerUid));
                    }
                    dutyDao.insertOrReplaceAll(toInsert);
                    created = toInsert.size();
                } else if (policy == ImportConflictPolicy.KEEP_EXISTING_ON_CONFLICT) {
                    for (Duty d : importedDuties) {
                        d.setOwnerUid(ownerUid);
                        d.setUpdatedAt(now);
                        DutyEntity existing = dutyDao.getDutyForDateDirect(ownerUid, d.getDutyDate());
                        if (existing == null) {
                            d.setSyncStatus(SyncStatus.PENDING_UPLOAD);
                            DutyEntity entity = DutyEntity.fromDomain(d, ownerUid);
                            toInsert.add(entity);
                            dutyDao.insertOrReplace(entity);
                            created++;
                        } else {
                            skipped++;
                        }
                    }
                } else {
                    // MERGE_REPLACE_CONFLICTS: default
                    for (Duty d : importedDuties) {
                        d.setOwnerUid(ownerUid);
                        d.setUpdatedAt(now);
                        d.setSyncStatus(SyncStatus.PENDING_UPLOAD);
                        DutyEntity existing = dutyDao.getDutyForDateDirect(ownerUid, d.getDutyDate());
                        if (existing != null) {
                            updated++;
                            d.setId(existing.getId());
                        } else {
                            created++;
                        }
                        toInsert.add(DutyEntity.fromDomain(d, ownerUid));
                    }
                    dutyDao.insertOrReplaceAll(toInsert);
                }

                // Diagnostic verification: confirm all saved records can be loaded back from Room
                for (DutyEntity entity : toInsert) {
                    Log.i(TAG, "[RosterDiagnostic] Saved to Room: Date=" + entity.getDutyDate() + ", Shift=" + entity.getShiftType() + ", Colleagues='" + entity.getColleagues() + "'");
                    DutyEntity reloaded = dutyDao.getDutyByIdDirect(ownerUid, entity.getId());
                    if (reloaded != null) {
                        Log.i(TAG, "[RosterDiagnostic] Verified loaded from Room: Date=" + reloaded.getDutyDate() + ", Shift=" + reloaded.getShiftType() + ", Colleagues='" + reloaded.getColleagues() + "'");
                    } else {
                        Log.w(TAG, "[RosterDiagnostic] Room reload check failed for Date=" + entity.getDutyDate() + ", Id=" + entity.getId());
                    }
                }

                final BulkImportResult result = new BulkImportResult(toInsert.size(), created, updated, skipped);
                if (callback != null) {
                    mainHandler.post(() -> callback.onComplete(result));
                }

                if (!toInsert.isEmpty()) {
                    List<Duty> dutiesToUpload = new ArrayList<>();
                    for (DutyEntity entity : toInsert) {
                        dutiesToUpload.add(entity.toDomain());
                    }
                    firestoreService.uploadDutiesBatch(dutiesToUpload, new FirestoreService.FirestoreCallback<Void>() {
                        @Override
                        public void onSuccess(Void res) {
                            diskExecutor.execute(() -> {
                                for (DutyEntity entity : toInsert) {
                                    entity.setSyncStatus(SyncStatus.SYNCED.name());
                                }
                                dutyDao.insertOrReplaceAll(toInsert);
                                Log.d(TAG, "Imported " + result.totalImported + " duties synced to cloud");
                            });
                        }

                        @Override
                        public void onError(Exception e) {
                            Log.w(TAG, "Cloud sync for imported duties queued: " + e.getMessage());
                        }
                    });
                }

            } catch (Exception e) {
                Log.e(TAG, "Failed importing roster duties", e);
                if (callback != null) {
                    mainHandler.post(() -> callback.onError(e));
                }
            }
        });
    }

    public void importRosterDuties(final String ownerUid,
                                   final List<Duty> importedDuties,
                                   final ImportConflictPolicy policy,
                                   final String startDate,
                                   final String endDate,
                                   final RepositoryCallback<Integer> callback) {
        importRosterDutiesDetailed(ownerUid, importedDuties, policy, startDate, endDate, new RepositoryCallback<BulkImportResult>() {
            @Override
            public void onComplete(BulkImportResult result) {
                if (callback != null) callback.onComplete(result.totalImported);
            }

            @Override
            public void onError(Exception e) {
                if (callback != null) callback.onError(e);
            }
        });
    }

    // --- Cloud Synchronization & Listeners ---

    public void syncWithCloud(final String ownerUid, final RepositoryCallback<Boolean> callback) {
        if (ownerUid == null || ownerUid.isEmpty()) {
            if (callback != null) callback.onError(new IllegalArgumentException("No UID provided"));
            return;
        }

        diskExecutor.execute(() -> {
            // 1. Upload pending changes first
            List<DutyEntity> pending = dutyDao.getPendingUploadDuties(ownerUid);
            if (pending != null && !pending.isEmpty()) {
                List<Duty> toUpload = new ArrayList<>();
                for (DutyEntity e : pending) {
                    toUpload.add(e.toDomain());
                }
                firestoreService.uploadDutiesBatch(toUpload, new FirestoreService.FirestoreCallback<Void>() {
                    @Override
                    public void onSuccess(Void result) {
                        diskExecutor.execute(() -> {
                            for (DutyEntity e : pending) {
                                e.setSyncStatus(SyncStatus.SYNCED.name());
                            }
                            dutyDao.insertOrReplaceAll(pending);
                        });
                    }

                    @Override
                    public void onError(Exception e) {
                        Log.w(TAG, "Pending sync deferred: " + e.getMessage());
                    }
                });
            }

            // 2. Fetch remote duties and merge into Room
            firestoreService.fetchAllDuties(ownerUid, new FirestoreService.FirestoreCallback<List<Duty>>() {
                @Override
                public void onSuccess(List<Duty> remoteDuties) {
                    diskExecutor.execute(() -> {
                        List<DutyEntity> entities = new ArrayList<>();
                        for (Duty remote : remoteDuties) {
                            DutyEntity local = dutyDao.getDutyByIdDirect(ownerUid, remote.getId());
                            // Conflict policy: If local has newer updated timestamp and is pending upload, preserve local
                            if (local != null && local.getSyncStatus().equals(SyncStatus.PENDING_UPLOAD.name())
                                    && local.getUpdatedAt() > remote.getUpdatedAt()) {
                                continue;
                            }
                            remote.setSyncStatus(SyncStatus.SYNCED);
                            entities.add(DutyEntity.fromDomain(remote, ownerUid));
                        }
                        if (!entities.isEmpty()) {
                            dutyDao.insertOrReplaceAll(entities);
                        }
                        if (callback != null) {
                            mainHandler.post(() -> callback.onComplete(true));
                        }
                    });
                }

                @Override
                public void onError(Exception e) {
                    Log.w(TAG, "Cloud fetch failed: " + e.getMessage());
                    if (callback != null) {
                        mainHandler.post(() -> callback.onError(e));
                    }
                }
            });
        });
    }

    public void startListeningToCloudUpdates(final String ownerUid) {
        stopListeningToCloudUpdates();
        if (ownerUid == null || ownerUid.isEmpty()) return;

        activeFirestoreListener = firestoreService.listenToDuties(ownerUid, new FirestoreService.FirestoreCallback<List<Duty>>() {
            @Override
            public void onSuccess(List<Duty> remoteDuties) {
                diskExecutor.execute(() -> {
                    List<DutyEntity> entities = new ArrayList<>();
                    for (Duty remote : remoteDuties) {
                        remote.setSyncStatus(SyncStatus.SYNCED);
                        entities.add(DutyEntity.fromDomain(remote, ownerUid));
                    }
                    if (!entities.isEmpty()) {
                        dutyDao.insertOrReplaceAll(entities);
                    }
                });
            }

            @Override
            public void onError(Exception e) {
                Log.w(TAG, "Live update error: " + e.getMessage());
            }
        });
    }

    public void stopListeningToCloudUpdates() {
        if (activeFirestoreListener != null) {
            activeFirestoreListener.remove();
            activeFirestoreListener = null;
        }
    }

    // --- Multi-User Cache Isolation & Account Cleanup ---

    /**
     * Purges local cached duties for the specified user account.
     * Prevents one user's shifts from leaking to another user logging in on the same phone.
     */
    public void clearLocalCacheForAccount(final String ownerUid, final RepositoryCallback<Void> callback) {
        stopListeningToCloudUpdates();
        diskExecutor.execute(() -> {
            try {
                if (ownerUid != null && !ownerUid.isEmpty()) {
                    dutyDao.deleteAllByOwner(ownerUid);
                }
                if (callback != null) {
                    mainHandler.post(() -> callback.onComplete(null));
                }
            } catch (Exception e) {
                if (callback != null) {
                    mainHandler.post(() -> callback.onError(e));
                }
            }
        });
    }

    /**
     * Full account deletion: purges remote Firestore collection and local Room database.
     */
    public void deleteEntireAccountData(final String ownerUid, final RepositoryCallback<Void> callback) {
        stopListeningToCloudUpdates();
        firestoreService.deleteEntireUserAccountData(ownerUid, new FirestoreService.FirestoreCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                clearLocalCacheForAccount(ownerUid, callback);
            }

            @Override
            public void onError(Exception e) {
                // If network fails, still attempt local clear
                clearLocalCacheForAccount(ownerUid, new RepositoryCallback<Void>() {
                    @Override
                    public void onComplete(Void res) {
                        if (callback != null) callback.onError(e);
                    }

                    @Override
                    public void onError(Exception inner) {
                        if (callback != null) callback.onError(e);
                    }
                });
            }
        });
    }
}
