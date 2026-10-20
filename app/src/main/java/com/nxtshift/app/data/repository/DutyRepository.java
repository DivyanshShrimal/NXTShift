package com.nxtshift.app.data.repository;

import android.content.Context;
import android.content.SharedPreferences;
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
import com.nxtshift.app.notifications.ShiftNotificationManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private final Context context;
    private final NXTShiftDatabase database;
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
                    Context appCtx = context.getApplicationContext();
                    NXTShiftDatabase db = NXTShiftDatabase.getInstance(appCtx);
                    INSTANCE = new DutyRepository(
                            appCtx,
                            db,
                            db.dutyDao(),
                            new FirestoreService()
                    );
                }
            }
        }
        return INSTANCE;
    }

    public DutyRepository(Context context, NXTShiftDatabase database, DutyDao dutyDao, FirestoreService firestoreService) {
        this.context = context != null ? context.getApplicationContext() : null;
        this.database = database;
        this.dutyDao = dutyDao;
        this.firestoreService = firestoreService;
        this.diskExecutor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public DutyRepository(DutyDao dutyDao, FirestoreService firestoreService) {
        this(null, null, dutyDao, firestoreService);
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

                if (context != null) {
                    ShiftNotificationManager.updatePersistentNextDutyNotification(context);
                }

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
                addPendingDeletedDutyIds(duty.getOwnerUid(), Collections.singletonList(duty.getId()));

                if (context != null) {
                    ShiftNotificationManager.updatePersistentNextDutyNotification(context);
                }

                if (callback != null) {
                    mainHandler.post(() -> callback.onComplete(null));
                }

                firestoreService.deleteDuty(duty.getId(), duty.getOwnerUid(), new FirestoreService.FirestoreCallback<Void>() {
                    @Override
                    public void onSuccess(Void result) {
                        removePendingDeletedDutyIds(duty.getOwnerUid(), Collections.singletonList(duty.getId()));
                        Log.d(TAG, "Deleted duty from Firestore: " + duty.getId());
                    }

                    @Override
                    public void onError(Exception e) {
                        Log.w(TAG, "Could not delete from Firestore immediately (queued): " + e.getMessage());
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
                if (ownerUid == null || ownerUid.isEmpty()) {
                    if (callback != null) {
                        mainHandler.post(() -> callback.onError(new IllegalArgumentException("Missing owner UID for import")));
                    }
                    return;
                }

                if (importedDuties == null || importedDuties.isEmpty()) {
                    final BulkImportResult emptyResult = new BulkImportResult(0, 0, 0, 0);
                    if (callback != null) {
                        mainHandler.post(() -> callback.onComplete(emptyResult));
                    }
                    return;
                }

                long now = System.currentTimeMillis();
                List<DutyEntity> toInsert = new ArrayList<>();
                List<String> idsToDelete = new ArrayList<>();
                int created = 0;
                int updated = 0;
                int skipped = 0;

                // 1. Group incoming duties strictly by date for this user scope
                Map<String, Duty> incomingByDate = new LinkedHashMap<>();
                for (Duty d : importedDuties) {
                    if (d.getDutyDate() != null) {
                        incomingByDate.put(d.getDutyDate(), d);
                    }
                }

                // Determine min and max covered dates
                String minDate = startDate;
                String maxDate = endDate;
                if (minDate == null || maxDate == null) {
                    for (String d : incomingByDate.keySet()) {
                        if (minDate == null || d.compareTo(minDate) < 0) minDate = d;
                        if (maxDate == null || d.compareTo(maxDate) > 0) maxDate = d;
                    }
                }

                // 2. Fetch existing local duties in this range for this user only
                List<DutyEntity> existingInRange = dutyDao.getDutiesBetweenDatesDirect(ownerUid, minDate, maxDate);
                Map<String, List<DutyEntity>> existingByDate = new HashMap<>();
                if (existingInRange != null) {
                    for (DutyEntity entity : existingInRange) {
                        existingByDate.computeIfAbsent(entity.getDutyDate(), k -> new ArrayList<>()).add(entity);
                    }
                }

                // 3. Process every explicitly covered date
                for (Map.Entry<String, Duty> entry : incomingByDate.entrySet()) {
                    String date = entry.getKey();
                    Duty incoming = entry.getValue();
                    incoming.setOwnerUid(ownerUid);
                    incoming.setUpdatedAt(now);
                    incoming.setSyncStatus(SyncStatus.PENDING_UPLOAD);

                    List<DutyEntity> existingOnDate = existingByDate.get(date);
                    if (existingOnDate == null || existingOnDate.isEmpty()) {
                        // Brand new duty for this date
                        toInsert.add(DutyEntity.fromDomain(incoming, ownerUid));
                        created++;
                    } else if (policy == ImportConflictPolicy.KEEP_EXISTING_ON_CONFLICT) {
                        // Keep existing record on conflict
                        skipped++;
                    } else {
                        // MERGE_REPLACE_CONFLICTS or REPLACE_RANGE:
                        // Identify previous roster-generated entries vs manual entries
                        List<DutyEntity> rosterDuties = new ArrayList<>();
                        for (DutyEntity e : existingOnDate) {
                            if (DutyEntity.isRosterGenerated(e.getNotes())) {
                                rosterDuties.add(e);
                            }
                        }

                        if (rosterDuties.isEmpty()) {
                            // Only manual duty exists on this date -> Preserve manual duty!
                            toInsert.add(DutyEntity.fromDomain(incoming, ownerUid));
                            created++;
                        } else {
                            // Previous roster-generated duty exists: reuse ID to update cleanly in-place
                            DutyEntity primaryRoster = rosterDuties.get(0);
                            incoming.setId(primaryRoster.getId());
                            toInsert.add(DutyEntity.fromDomain(incoming, ownerUid));
                            updated++;

                            // Remove any duplicate or contradictory previous roster entries on this same date
                            for (int i = 1; i < rosterDuties.size(); i++) {
                                idsToDelete.add(rosterDuties.get(i).getId());
                            }
                        }
                    }
                }

                // 4. Atomic Room database write inside transaction
                final List<DutyEntity> finalToInsert = toInsert;
                final List<String> finalIdsToDelete = idsToDelete;
                Runnable dbWriteAction = () -> {
                    if (!finalIdsToDelete.isEmpty()) {
                        dutyDao.deleteDutiesByIds(ownerUid, finalIdsToDelete);
                    }
                    if (!finalToInsert.isEmpty()) {
                        dutyDao.insertOrReplaceAll(finalToInsert);
                    }
                };

                if (database != null) {
                    database.runInTransaction(dbWriteAction);
                } else {
                    dbWriteAction.run();
                }

                final BulkImportResult result = new BulkImportResult(finalToInsert.size(), created, updated, skipped);
                if (callback != null) {
                    mainHandler.post(() -> callback.onComplete(result));
                }

                // 5. Update pinned notification
                if (context != null) {
                    ShiftNotificationManager.updatePersistentNextDutyNotification(context);
                }

                // 6. Delete obsolete conflicting duplicates from Firestore
                if (!finalIdsToDelete.isEmpty()) {
                    addPendingDeletedDutyIds(ownerUid, finalIdsToDelete);
                    firestoreService.deleteDutiesBatch(finalIdsToDelete, ownerUid, new FirestoreService.FirestoreCallback<Void>() {
                        @Override
                        public void onSuccess(Void unused) {
                            removePendingDeletedDutyIds(ownerUid, finalIdsToDelete);
                            Log.d(TAG, "Purged " + finalIdsToDelete.size() + " obsolete conflicting duties from Firestore");
                        }

                        @Override
                        public void onError(Exception e) {
                            Log.w(TAG, "Batch delete from Firestore queued: " + e.getMessage());
                        }
                    });
                }

                // 7. Batch upload new & updated duties to Firestore
                if (!finalToInsert.isEmpty()) {
                    List<Duty> dutiesToUpload = new ArrayList<>();
                    for (DutyEntity entity : finalToInsert) {
                        dutiesToUpload.add(entity.toDomain());
                    }
                    firestoreService.uploadDutiesBatch(dutiesToUpload, new FirestoreService.FirestoreCallback<Void>() {
                        @Override
                        public void onSuccess(Void unused) {
                            diskExecutor.execute(() -> {
                                for (DutyEntity entity : finalToInsert) {
                                    entity.setSyncStatus(SyncStatus.SYNCED.name());
                                }
                                dutyDao.insertOrReplaceAll(finalToInsert);
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

    private synchronized Set<String> getPendingDeletedDutyIds(String ownerUid) {
        if (context == null || ownerUid == null) return new HashSet<>();
        SharedPreferences sp = context.getSharedPreferences("nxtshift_sync_deletes_" + ownerUid, Context.MODE_PRIVATE);
        return new HashSet<>(sp.getStringSet("pending_deletes", Collections.emptySet()));
    }

    private synchronized void addPendingDeletedDutyIds(String ownerUid, List<String> ids) {
        if (context == null || ownerUid == null || ids.isEmpty()) return;
        SharedPreferences sp = context.getSharedPreferences("nxtshift_sync_deletes_" + ownerUid, Context.MODE_PRIVATE);
        Set<String> set = new HashSet<>(sp.getStringSet("pending_deletes", Collections.emptySet()));
        set.addAll(ids);
        sp.edit().putStringSet("pending_deletes", set).apply();
    }

    private synchronized void removePendingDeletedDutyIds(String ownerUid, List<String> ids) {
        if (context == null || ownerUid == null || ids.isEmpty()) return;
        SharedPreferences sp = context.getSharedPreferences("nxtshift_sync_deletes_" + ownerUid, Context.MODE_PRIVATE);
        Set<String> set = new HashSet<>(sp.getStringSet("pending_deletes", Collections.emptySet()));
        set.removeAll(ids);
        sp.edit().putStringSet("pending_deletes", set).apply();
    }

    public void syncWithCloud(final String ownerUid, final RepositoryCallback<Boolean> callback) {
        if (ownerUid == null || ownerUid.isEmpty()) {
            if (callback != null) callback.onError(new IllegalArgumentException("No UID provided"));
            return;
        }

        diskExecutor.execute(() -> {
            // 1. Process pending deletions from Firestore first
            Set<String> pendingDeletes = getPendingDeletedDutyIds(ownerUid);
            if (!pendingDeletes.isEmpty()) {
                List<String> toDeleteList = new ArrayList<>(pendingDeletes);
                firestoreService.deleteDutiesBatch(toDeleteList, ownerUid, new FirestoreService.FirestoreCallback<Void>() {
                    @Override
                    public void onSuccess(Void unused) {
                        removePendingDeletedDutyIds(ownerUid, toDeleteList);
                        Log.d(TAG, "Successfully synced " + toDeleteList.size() + " pending deletions to Firestore");
                    }

                    @Override
                    public void onError(Exception e) {
                        Log.w(TAG, "Pending deletions deferred: " + e.getMessage());
                    }
                });
            }

            // 2. Upload pending local additions/updates
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

            // 3. Fetch remote duties and merge into Room
            firestoreService.fetchAllDuties(ownerUid, new FirestoreService.FirestoreCallback<List<Duty>>() {
                @Override
                public void onSuccess(List<Duty> remoteDuties) {
                    diskExecutor.execute(() -> {
                        Set<String> activeDeletes = getPendingDeletedDutyIds(ownerUid);
                        List<DutyEntity> entitiesToSave = new ArrayList<>();
                        List<String> obsoleteRemoteIds = new ArrayList<>();

                        // Group remote duties by date to detect and eliminate duplicate roster duties in Firestore
                        Map<String, List<Duty>> remoteByDate = new HashMap<>();
                        for (Duty remote : remoteDuties) {
                            if (activeDeletes.contains(remote.getId())) {
                                obsoleteRemoteIds.add(remote.getId());
                                continue;
                            }
                            remoteByDate.computeIfAbsent(remote.getDutyDate(), k -> new ArrayList<>()).add(remote);
                        }

                        for (Map.Entry<String, List<Duty>> entry : remoteByDate.entrySet()) {
                            List<Duty> onDate = entry.getValue();
                            List<Duty> remoteRosterDuties = new ArrayList<>();
                            List<Duty> remoteManualDuties = new ArrayList<>();

                            for (Duty d : onDate) {
                                if (d.isRosterGenerated()) {
                                    remoteRosterDuties.add(d);
                                } else {
                                    remoteManualDuties.add(d);
                                }
                            }

                            // If multiple roster duties exist on the same date in Firestore, keep only the latest
                            Duty winningRosterDuty = null;
                            if (!remoteRosterDuties.isEmpty()) {
                                remoteRosterDuties.sort((a, b) -> Long.compare(b.getUpdatedAt(), a.getUpdatedAt()));
                                winningRosterDuty = remoteRosterDuties.get(0);
                                for (int i = 1; i < remoteRosterDuties.size(); i++) {
                                    obsoleteRemoteIds.add(remoteRosterDuties.get(i).getId());
                                }
                            }

                            if (winningRosterDuty != null) {
                                DutyEntity local = dutyDao.getDutyByIdDirect(ownerUid, winningRosterDuty.getId());
                                if (local != null && local.getSyncStatus().equals(SyncStatus.PENDING_UPLOAD.name())
                                        && local.getUpdatedAt() > winningRosterDuty.getUpdatedAt()) {
                                    // Local uncommitted change is newer; do not overwrite
                                } else {
                                    winningRosterDuty.setSyncStatus(SyncStatus.SYNCED);
                                    entitiesToSave.add(DutyEntity.fromDomain(winningRosterDuty, ownerUid));
                                }
                            }

                            for (Duty manual : remoteManualDuties) {
                                DutyEntity local = dutyDao.getDutyByIdDirect(ownerUid, manual.getId());
                                if (local != null && local.getSyncStatus().equals(SyncStatus.PENDING_UPLOAD.name())
                                        && local.getUpdatedAt() > manual.getUpdatedAt()) {
                                    continue;
                                }
                                manual.setSyncStatus(SyncStatus.SYNCED);
                                entitiesToSave.add(DutyEntity.fromDomain(manual, ownerUid));
                            }
                        }

                        if (!entitiesToSave.isEmpty()) {
                            dutyDao.insertOrReplaceAll(entitiesToSave);
                        }

                        if (!obsoleteRemoteIds.isEmpty()) {
                            firestoreService.deleteDutiesBatch(obsoleteRemoteIds, ownerUid, null);
                        }

                        if (context != null) {
                            ShiftNotificationManager.updatePersistentNextDutyNotification(context);
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
                    Set<String> activeDeletes = getPendingDeletedDutyIds(ownerUid);
                    List<DutyEntity> entities = new ArrayList<>();
                    Map<String, List<Duty>> remoteByDate = new HashMap<>();

                    for (Duty remote : remoteDuties) {
                        if (activeDeletes.contains(remote.getId())) {
                            continue;
                        }
                        remoteByDate.computeIfAbsent(remote.getDutyDate(), k -> new ArrayList<>()).add(remote);
                    }

                    for (List<Duty> onDate : remoteByDate.values()) {
                        List<Duty> roster = new ArrayList<>();
                        for (Duty d : onDate) {
                            if (d.isRosterGenerated()) {
                                roster.add(d);
                            } else {
                                d.setSyncStatus(SyncStatus.SYNCED);
                                entities.add(DutyEntity.fromDomain(d, ownerUid));
                            }
                        }
                        if (!roster.isEmpty()) {
                            roster.sort((a, b) -> Long.compare(b.getUpdatedAt(), a.getUpdatedAt()));
                            Duty winning = roster.get(0);
                            DutyEntity local = dutyDao.getDutyByIdDirect(ownerUid, winning.getId());
                            if (local == null || !local.getSyncStatus().equals(SyncStatus.PENDING_UPLOAD.name())
                                    || local.getUpdatedAt() <= winning.getUpdatedAt()) {
                                winning.setSyncStatus(SyncStatus.SYNCED);
                                entities.add(DutyEntity.fromDomain(winning, ownerUid));
                            }
                        }
                    }

                    if (!entities.isEmpty()) {
                        dutyDao.insertOrReplaceAll(entities);
                    }
                    if (context != null) {
                        ShiftNotificationManager.updatePersistentNextDutyNotification(context);
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
