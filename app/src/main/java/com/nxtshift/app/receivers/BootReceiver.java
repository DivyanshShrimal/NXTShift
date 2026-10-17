package com.nxtshift.app.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.nxtshift.app.notifications.ShiftNotificationManager;

import java.util.concurrent.Executors;

/**
 * Reschedules alarms and refreshes persistent status when device boots up.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Log.i(TAG, "Device booted, action: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
            "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {

            Executors.newSingleThreadExecutor().execute(() -> {
                ShiftNotificationManager.scheduleDailyDutyReminder(context);
                ShiftNotificationManager.updatePersistentNextDutyNotification(context);
            });
        }
    }
}
