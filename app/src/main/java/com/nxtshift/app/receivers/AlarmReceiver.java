package com.nxtshift.app.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.nxtshift.app.notifications.ShiftNotificationManager;

import java.util.concurrent.Executors;

/**
 * BroadcastReceiver triggered by AlarmManager to post tomorrow's shift alert.
 */
public class AlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "AlarmReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Shift reminder alarm triggered");

        // Execute notification dispatch in background thread
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                ShiftNotificationManager.buildAndShowTomorrowDutyNotification(context);
                ShiftNotificationManager.updatePersistentNextDutyNotification(context);
            } catch (Exception e) {
                Log.e(TAG, "Failed posting shift reminder notification", e);
            } finally {
                // Re-schedule for the following day
                ShiftNotificationManager.scheduleDailyDutyReminder(context);
            }
        });
    }
}
