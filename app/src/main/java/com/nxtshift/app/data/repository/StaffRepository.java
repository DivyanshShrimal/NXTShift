package com.nxtshift.app.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.lifecycle.LiveData;

import com.nxtshift.app.data.local.NXTShiftDatabase;
import com.nxtshift.app.data.local.StaffDao;
import com.nxtshift.app.data.local.StaffMemberEntity;
import com.nxtshift.app.data.remote.FirestoreService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
    private final ExecutorService diskExecutor;
    private final Handler mainHandler;

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
                            new FirestoreService()
                    );
                }
            }
        }
        return INSTANCE;
    }

    public StaffRepository(StaffDao staffDao, FirestoreService firestoreService) {
        this.staffDao = staffDao;
        this.firestoreService = firestoreService;
        this.diskExecutor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
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
                mainHandler.post(() -> {
                    if (callback != null) callback.onComplete(count);
                });

                // Batch sync to Firestore
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

            } catch (Exception e) {
                Log.e(TAG, "Error saving discovered staff", e);
                mainHandler.post(() -> {
                    if (callback != null) callback.onError(e);
                });
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
