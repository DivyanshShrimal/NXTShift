package com.nxtshift.app.data.local;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Update;

import java.util.List;

@Dao
public interface DutyDao {

    @Query("SELECT * FROM duties WHERE ownerUid = :ownerUid ORDER BY dutyDate ASC, startTime ASC")
    LiveData<List<DutyEntity>> getAllDutiesLiveData(String ownerUid);

    @Query("SELECT * FROM duties WHERE ownerUid = :ownerUid ORDER BY dutyDate ASC, startTime ASC")
    List<DutyEntity> getAllDutiesDirect(String ownerUid);

    @Query("SELECT * FROM duties WHERE ownerUid = :ownerUid AND dutyDate >= :startDate AND dutyDate <= :endDate ORDER BY dutyDate ASC")
    LiveData<List<DutyEntity>> getDutiesBetweenDatesLiveData(String ownerUid, String startDate, String endDate);

    @Query("SELECT * FROM duties WHERE ownerUid = :ownerUid AND dutyDate >= :startDate AND dutyDate <= :endDate ORDER BY dutyDate ASC")
    List<DutyEntity> getDutiesBetweenDatesDirect(String ownerUid, String startDate, String endDate);

    @Query("SELECT * FROM duties WHERE ownerUid = :ownerUid AND dutyDate = :dutyDate LIMIT 1")
    DutyEntity getDutyForDateDirect(String ownerUid, String dutyDate);

    @Query("SELECT * FROM duties WHERE ownerUid = :ownerUid AND dutyDate = :dutyDate LIMIT 1")
    LiveData<DutyEntity> getDutyForDateLiveData(String ownerUid, String dutyDate);

    @Query("SELECT * FROM duties WHERE ownerUid = :ownerUid AND id = :id LIMIT 1")
    DutyEntity getDutyByIdDirect(String ownerUid, String id);

    @Query("SELECT * FROM duties WHERE ownerUid = :ownerUid AND dutyDate >= :fromDate ORDER BY dutyDate ASC LIMIT :limit")
    LiveData<List<DutyEntity>> getUpcomingDutiesLiveData(String ownerUid, String fromDate, int limit);

    @Query("SELECT * FROM duties WHERE ownerUid = :ownerUid AND dutyDate >= :fromDate ORDER BY dutyDate ASC LIMIT :limit")
    List<DutyEntity> getUpcomingDutiesDirect(String ownerUid, String fromDate, int limit);

    @Query("SELECT * FROM duties WHERE ownerUid = :ownerUid AND syncStatus != 'SYNCED'")
    List<DutyEntity> getPendingUploadDuties(String ownerUid);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertOrReplace(DutyEntity duty);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertOrReplaceAll(List<DutyEntity> duties);

    @Update
    void update(DutyEntity duty);

    @Query("DELETE FROM duties WHERE id = :id AND ownerUid = :ownerUid")
    int deleteDutyById(String id, String ownerUid);

    @Query("DELETE FROM duties WHERE ownerUid = :ownerUid AND dutyDate >= :startDate AND dutyDate <= :endDate")
    int deleteRangeByOwner(String ownerUid, String startDate, String endDate);

    @Query("DELETE FROM duties WHERE ownerUid = :ownerUid")
    int deleteAllByOwner(String ownerUid);

    @Transaction
    default void replaceRangeWithDuties(String ownerUid, String startDate, String endDate, List<DutyEntity> newDuties) {
        deleteRangeByOwner(ownerUid, startDate, endDate);
        insertOrReplaceAll(newDuties);
    }
}
