package com.nxtshift.app.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.nxtshift.app.notifications.ShiftNotificationManager;

import java.util.concurrent.Executors;

/**
 * Re-calibrates shift reminder alarms when system clock or timezone is altered.
 */
public class TimeChangeReceiver extends BroadcastReceiver {
    private static final String TAG = "TimeChangeReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Log.i(TAG, "Time/Timezone changed: " + action);

        if (Intent.ACTION_TIME_CHANGED.equals(action) ||
            Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {

            Executors.newSingleThreadExecutor().execute(() -> {
                ShiftNotificationManager.scheduleDailyDutyReminder(context);
                ShiftNotificationManager.updatePersistentNextDutyNotification(context);
            });
        }
    }
}
