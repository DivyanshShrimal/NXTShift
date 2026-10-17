package com.nxtshift.app.data.local;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {DutyEntity.class, UserProfileEntity.class, StaffMemberEntity.class}, version = 3, exportSchema = false)
public abstract class NXTShiftDatabase extends RoomDatabase {

    private static final String DATABASE_NAME = "nxtshift_database.db";
    private static volatile NXTShiftDatabase INSTANCE;

    public abstract DutyDao dutyDao();
    public abstract UserProfileDao userProfileDao();
    public abstract StaffDao staffDao();

    /**
     * v1 → v2: added `email` column to user_profiles table (nullable TEXT).
     */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE user_profiles ADD COLUMN email TEXT");
        }
    };

    /**
     * v2 → v3: added `staff_members` table for automated hospital staff directory.
     */
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS staff_members (" +
                    "id TEXT NOT NULL PRIMARY KEY, " +
                    "ownerUid TEXT NOT NULL, " +
                    "name TEXT NOT NULL, " +
                    "normalizedName TEXT NOT NULL, " +
                    "aliases TEXT, " +
                    "department TEXT, " +
                    "role TEXT, " +
                    "dutyCount INTEGER NOT NULL, " +
                    "createdAt INTEGER NOT NULL, " +
                    "updatedAt INTEGER NOT NULL, " +
                    "syncStatus TEXT NOT NULL)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_staff_members_ownerUid ON staff_members(ownerUid)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_staff_members_normalizedName ON staff_members(normalizedName)");
        }
    };

    public static NXTShiftDatabase getInstance(final Context context) {
        if (INSTANCE == null) {
            synchronized (NXTShiftDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            NXTShiftDatabase.class,
                            DATABASE_NAME
                    )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build();
                }
            }
        }
        return INSTANCE;
    }
}
