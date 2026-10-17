package com.nxtshift.app.data.local;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface StaffDao {

    @Query("SELECT * FROM staff_members WHERE ownerUid = :ownerUid ORDER BY dutyCount DESC, name ASC")
    LiveData<List<StaffMemberEntity>> getAllStaffLiveData(String ownerUid);

    @Query("SELECT * FROM staff_members WHERE ownerUid = :ownerUid ORDER BY dutyCount DESC, name ASC")
    List<StaffMemberEntity> getAllStaffDirect(String ownerUid);

    @Query("SELECT * FROM staff_members WHERE ownerUid = :ownerUid AND normalizedName = :normalizedName LIMIT 1")
    StaffMemberEntity findByNormalizedName(String ownerUid, String normalizedName);

    @Query("SELECT * FROM staff_members WHERE ownerUid = :ownerUid AND id = :id LIMIT 1")
    StaffMemberEntity getStaffById(String ownerUid, String id);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertOrReplace(StaffMemberEntity staff);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertOrReplaceAll(List<StaffMemberEntity> staffList);

    @Query("DELETE FROM staff_members WHERE ownerUid = :ownerUid AND id = :id")
    void deleteStaff(String ownerUid, String id);

    @Query("DELETE FROM staff_members WHERE ownerUid = :ownerUid")
    void deleteAllStaffForUser(String ownerUid);
}
