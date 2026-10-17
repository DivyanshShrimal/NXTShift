package com.nxtshift.app.data.local;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface UserProfileDao {

    @Query("SELECT * FROM user_profiles WHERE uid = :uid LIMIT 1")
    LiveData<UserProfileEntity> getProfileLiveData(String uid);

    @Query("SELECT * FROM user_profiles WHERE uid = :uid LIMIT 1")
    UserProfileEntity getProfileDirect(String uid);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertOrReplace(UserProfileEntity profile);

    @Query("DELETE FROM user_profiles WHERE uid = :uid")
    int deleteProfile(String uid);

    @Query("DELETE FROM user_profiles")
    int deleteAllProfiles();
}
