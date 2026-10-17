package com.nxtshift.app;

import android.app.Application;
import android.util.Log;

import com.google.firebase.FirebaseApp;
import com.nxtshift.app.data.local.NXTShiftDatabase;
import com.nxtshift.app.notifications.ShiftNotificationManager;

/**
 * NXTShift Application class.
 * Initializes notification channels, local Room database, and backend integration.
 */
public class NXTShiftApplication extends Application {
    private static final String TAG = "NXTShiftApp";
    private static NXTShiftApplication instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // Force consistent Light theme across entire app regardless of system Dark Mode
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        );

        try {
            FirebaseApp.initializeApp(this);
            Log.d(TAG, "Firebase initialized successfully");
        } catch (Exception e) {
            Log.w(TAG, "Firebase initialization notice: " + e.getMessage());
        }

        // Initialize Room Database
        NXTShiftDatabase.getInstance(this);

        // Setup notification channels (Android 8.0+)
        ShiftNotificationManager.createNotificationChannels(this);
    }

    public static NXTShiftApplication getInstance() {
        return instance;
    }
}
