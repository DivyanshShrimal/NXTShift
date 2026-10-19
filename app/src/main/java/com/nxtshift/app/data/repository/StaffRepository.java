package com.nxtshift.app.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.lifecycle.LiveData;

import com.nxtshift.app.data.local.NXTShiftDatabase;
import com.nxtshift.app.data.local.StaffDao;
import com.nxtshift.app.data.local.StaffMemberEntity;
import com.nxtshift.app.data.model.AccessState;
import com.nxtshift.app.data.remote.FirestoreService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Repository coordinating local Room persistence and Firestore cloud synchronization
 * for the automated hospital staff directory. Strictly isolated by user UID.
 */
public class StaffRepository {
    private static final String TAG = "StaffRepository";
    private static volatile StaffRepository INSTANCE;

    private final StaffDao staffDao;
    private final FirestoreService firestoreService;
    private final Executor diskExecutor;
    private final Handler mainHandler;
    private AccessControlRepository accessControlRepository;

    public interface StaffCallback<T> {
        void onComplete(T result);
        void onError(Exception e);
    }

    public static StaffRepository getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (StaffRepository.class) {
                if (INSTANCE == null) {
                    INSTANCE = new StaffRepository(
                            NXTShiftDatabase.getInstance(context).staffDao(),
                            new FirestoreService(),
                            AccessControlRepository.getInstance(context)
                    );
                }
            }
        }
        return INSTANCE;
    }

    public StaffRepository(StaffDao staffDao, FirestoreService firestoreService) {
        this(staffDao, firestoreService, null);
    }

    public StaffRepository(StaffDao staffDao, FirestoreService firestoreService, AccessControlRepository accessControlRepository) {
        this(staffDao, firestoreService, accessControlRepository, Executors.newSingleThreadExecutor(), new Handler(Looper.getMainLooper()));
    }

    public StaffRepository(StaffDao staffDao, FirestoreService firestoreService, AccessControlRepository accessControlRepository,
                           Executor diskExecutor, Handler mainHandler) {
        this.staffDao = staffDao;
        this.firestoreService = firestoreService;
        this.accessControlRepository = accessControlRepository;
        this.diskExecutor = diskExecutor != null ? diskExecutor : Executors.newSingleThreadExecutor();
        this.mainHandler = mainHandler;
    }

    public void setAccessControlRepository(AccessControlRepository accessControlRepository) {
        this.accessControlRepository = accessControlRepository;
    }

    public AccessControlRepository getAccessControlRepository() {
        return accessControlRepository;
    }

    public LiveData<List<StaffMemberEntity>> getAllStaffLiveData(String ownerUid) {
        return staffDao.getAllStaffLiveData(ownerUid);
    }

    public void getAllStaff(String ownerUid, final StaffCallback<List<StaffMemberEntity>> callback) {
        diskExecutor.execute(() -> {
            try {
                List<StaffMemberEntity> list = staffDao.getAllStaffDirect(ownerUid);
                mainHandler.post(() -> {
                    if (callback != null) callback.onComplete(list);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (callback != null) callback.onError(e);
                });
            }
        });
    }

    /**
     * Saves or merges a proposed list of staff members discovered during OCR.
     * Prevents duplicate entries by checking normalized names and managing aliases.
     */
    public void saveOrMergeDiscoveredStaff(String ownerUid, List<StaffMemberEntity> discoveredList, final StaffCallback<Integer> callback) {
        diskExecutor.execute(() -> {
            try {
                List<StaffMemberEntity> existingList = staffDao.getAllStaffDirect(ownerUid);
                if (existingList == null) {
                    existingList = Collections.emptyList();
                }
                List<StaffMemberEntity> toSave = new ArrayList<>();

                for (StaffMemberEntity discovered : discoveredList) {
                    StaffMemberEntity existing = null;
                    for (StaffMemberEntity e : existingList) {
                        if (e.getNormalizedName().equalsIgnoreCase(discovered.getNormalizedName())) {
                            existing = e;
                            break;
                        }
                    }

                    if (existing != null) {
                        // Merge aliases and update count
                        existing.setDutyCount(existing.getDutyCount() + discovered.getDutyCount());
                        existing.setUpdatedAt(System.currentTimeMillis());
                        if (!existing.getName().equalsIgnoreCase(discovered.getName())) {
                            String currAliases = existing.getAliases() != null ? existing.getAliases() : "";
                            if (!currAliases.contains(discovered.getName())) {
                                existing.setAliases(currAliases.isEmpty() ? discovered.getName() : currAliases + ", " + discovered.getName());
                            }
                        }
                        toSave.add(existing);
                    } else {
                        discovered.setOwnerUid(ownerUid);
                        toSave.add(discovered);
                    }
                }

                staffDao.insertOrReplaceAll(toSave);
                final int count = toSave.size();
                if (mainHandler == null || Looper.getMainLooper() == null) {
                    if (callback != null) callback.onComplete(count);
                } else {
                    mainHandler.post(() -> {
                        if (callback != null) callback.onComplete(count);
                    });
                }

                // Batch sync to Firestore gated by access control
                if (accessControlRepository != null) {
                    accessControlRepository.checkCloudAccess(new AccessControlRepository.AccessCheckCallback() {
                        @Override
                        public void onAllowed() {
                            uploadStaffBatchToFirestore(ownerUid, toSave, count);
                        }

                        @Override
                        public void onBlocked(AccessState state, String message) {
                            Log.w(TAG, "Staff directory cloud upload blocked by access control: " + state + " - " + message);
                        }
                    });
                } else {
                    uploadStaffBatchToFirestore(ownerUid, toSave, count);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error saving discovered staff", e);
                if (mainHandler == null || Looper.getMainLooper() == null) {
                    if (callback != null) callback.onError(e);
                } else {
                    mainHandler.post(() -> {
                        if (callback != null) callback.onError(e);
                    });
                }
            }
        });
    }

    private void uploadStaffBatchToFirestore(String ownerUid, List<StaffMemberEntity> toSave, int count) {
        firestoreService.uploadStaffBatch(ownerUid, toSave, new FirestoreService.FirestoreCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                Log.d(TAG, "Successfully synced " + count + " staff members to Firestore");
            }

            @Override
            public void onError(Exception e) {
                Log.w(TAG, "Offline or pending sync for staff members: " + e.getMessage());
            }
        });
    }

    public void clearStaffForUser(String ownerUid) {
        diskExecutor.execute(() -> {
            try {
                staffDao.deleteAllStaffForUser(ownerUid);
            } catch (Exception e) {
                Log.w(TAG, "Error clearing staff for user: " + e.getMessage());
            }
        });
    }
}
