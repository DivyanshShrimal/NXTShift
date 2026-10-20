package com.nxtshift.app.notifications;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.nxtshift.app.R;
import com.nxtshift.app.data.local.NXTShiftDatabase;
import com.nxtshift.app.domain.Duty;
import com.nxtshift.app.domain.ShiftType;
import com.nxtshift.app.receivers.AlarmReceiver;
import com.nxtshift.app.ui.dashboard.DashboardActivity;
import com.nxtshift.app.util.DateTimeUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages daily shift reminder scheduling and persistent next-duty notifications.
 * Operates strictly with verified local cached duty records for the authenticated user.
 */
public class ShiftNotificationManager {
    private static final String TAG = "ShiftNotifManager";

    public static final String CHANNEL_REMINDERS_ID = "nxtshift_daily_reminders";
    public static final String CHANNEL_PERSISTENT_ID = "nxtshift_persistent_status";

    public static final int NOTIFICATION_ID_TOMORROW = 1001;
    public static final int NOTIFICATION_ID_PERSISTENT = 1002;
    private static final int ALARM_REQUEST_CODE = 2001;

    public static final String PREFS_NAME = "nxtshift_preferences";
    public static final String KEY_REMINDERS_ENABLED = "pref_reminders_enabled";
    public static final String KEY_REMINDER_HOUR = "pref_reminder_hour";     // default 20 (8 PM)
    public static final String KEY_REMINDER_MINUTE = "pref_reminder_minute"; // default 0
    public static final String KEY_PERSISTENT_NOTIF = "pref_persistent_notif";
    public static final String KEY_LOCKSCREEN_PRIVACY = "pref_lockscreen_privacy";

    public static void createNotificationChannels(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm == null) return;

            // 1. Daily shift reminder channel (High importance)
            NotificationChannel reminderChannel = new NotificationChannel(
                    CHANNEL_REMINDERS_ID,
                    context.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
            );
            reminderChannel.setDescription(context.getString(R.string.notification_channel_desc));
            reminderChannel.enableVibration(true);
            nm.createNotificationChannel(reminderChannel);

            // 2. Ongoing next-duty notification channel (Low/Default importance, non-intrusive)
            NotificationChannel persistentChannel = new NotificationChannel(
                    CHANNEL_PERSISTENT_ID,
                    context.getString(R.string.notification_persistent_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            persistentChannel.setDescription(context.getString(R.string.notification_persistent_channel_desc));
            nm.createNotificationChannel(persistentChannel);
        }
    }

    /**
     * Schedules the next 8:00 PM (or user-configured time) alarm.
     */
    public static void scheduleDailyDutyReminder(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(KEY_REMINDERS_ENABLED, true);
        if (!enabled) {
            cancelDailyDutyReminder(context);
            return;
        }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            // No user signed in; cancel reminders to avoid leaking data
            cancelDailyDutyReminder(context);
            return;
        }

        int hour = prefs.getInt(KEY_REMINDER_HOUR, 20); // Default 8:00 PM
        int minute = prefs.getInt(KEY_REMINDER_MINUTE, 0);

        Calendar target = Calendar.getInstance();
        target.set(Calendar.HOUR_OF_DAY, hour);
        target.set(Calendar.MINUTE, minute);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);

        // If today's alarm time has already passed, schedule for tomorrow
        if (target.getTimeInMillis() <= System.currentTimeMillis()) {
            target.add(Calendar.DAY_OF_YEAR, 1);
        }

        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(context, AlarmReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        long triggerMs = target.getTimeInMillis();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi);
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi);
                }
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi);
            }
            Log.d(TAG, "Daily reminder scheduled for " + target.getTime());
        } catch (SecurityException e) {
            Log.w(TAG, "Exact alarm permission restricted, falling back to inexact: " + e.getMessage());
            am.set(AlarmManager.RTC_WAKEUP, triggerMs, pi);
        }
    }

    public static void cancelDailyDutyReminder(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(context, AlarmReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
        );
        if (pi != null) {
            am.cancel(pi);
            pi.cancel();
            Log.d(TAG, "Daily reminder alarm cancelled");
        }
    }

    /**
     * Builds and displays the 8 PM notification for tomorrow's duty.
     */
    public static void buildAndShowTomorrowDutyNotification(Context context) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.d(TAG, "Skipping notification: No user logged in");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Cannot post notification: POST_NOTIFICATIONS permission not granted");
                return;
            }
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean hideOnLockscreen = prefs.getBoolean(KEY_LOCKSCREEN_PRIVACY, false);

        String tomorrowDate = DateTimeUtils.getTomorrowIsoDate();
        NXTShiftDatabase db = NXTShiftDatabase.getInstance(context);
        com.nxtshift.app.data.local.DutyEntity entity = db.dutyDao().getDutyForDateDirect(user.getUid(), tomorrowDate);

        String title = context.getString(R.string.notif_tomorrow_title);
        StringBuilder content = new StringBuilder();
        StringBuilder bigText = new StringBuilder();

        if (entity == null) {
            content.append("No duty scheduled for tomorrow. Tap to view your roster.");
            bigText.append("No duty record found for tomorrow (").append(tomorrowDate).append(").\nTap to check or update your roster.");
        } else {
            Duty duty = entity.toDomain();
            if (duty.isOffOrLeave()) {
                content.append(duty.getShiftType().getDisplayName()).append(" — Rest and recharge!");
                bigText.append(duty.getShiftType().getDisplayName()).append(" scheduled for tomorrow.\nEnjoy your off day!");
            } else {
                content.append(duty.getShiftName()).append(" · ").append(duty.getFormattedDutyTime());
                bigText.append(duty.getShiftName()).append(" · ").append(duty.getFormattedDutyTime());

                if (duty.getColleagues() != null && !duty.getColleagues().isEmpty()) {
                    bigText.append("\nWith: ").append(duty.getColleagues());
                }
                if (duty.getWardOrDepartment() != null && !duty.getWardOrDepartment().isEmpty()) {
                    bigText.append("\nWard: ").append(duty.getWardOrDepartment());
                }
                if (duty.getNotes() != null && !duty.getNotes().isEmpty()) {
                    bigText.append("\nNotes: ").append(duty.getNotes());
                }
            }
        }

        Intent tapIntent = new Intent(context, DashboardActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_REMINDERS_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(content.toString())
                .setStyle(new NotificationCompat.BigTextStyle().bigText(bigText.toString()))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        if (hideOnLockscreen) {
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE);
        } else {
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        }

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_TOMORROW, builder.build());
    }

    public static class DutyTimeRange {
        public final LocalDateTime start;
        public final LocalDateTime end;

        public DutyTimeRange(LocalDateTime start, LocalDateTime end) {
            this.start = start;
            this.end = end;
        }
    }

    public static class PinnedDutyResolution {
        public final Duty duty;
        public final boolean isCurrentDuty;
        public final String displayTitle;
        public final String displayContent;
        public final String bigText;

        public PinnedDutyResolution(Duty duty, boolean isCurrentDuty, String displayTitle, String displayContent, String bigText) {
            this.duty = duty;
            this.isCurrentDuty = isCurrentDuty;
            this.displayTitle = displayTitle;
            this.displayContent = displayContent;
            this.bigText = bigText;
        }
    }

    public static DutyTimeRange parseDutyTimeRange(Duty duty) {
        if (duty == null || duty.getDutyDate() == null) return null;
        try {
            LocalDate date = LocalDate.parse(duty.getDutyDate());
            String startTimeStr = duty.getStartTime();
            String endTimeStr = duty.getEndTime();
            if (startTimeStr == null || startTimeStr.isEmpty()) {
                startTimeStr = duty.getShiftType().getDefaultStartTime();
            }
            if (endTimeStr == null || endTimeStr.isEmpty()) {
                endTimeStr = duty.getShiftType().getDefaultEndTime();
            }
            if (startTimeStr == null || startTimeStr.isEmpty() || endTimeStr == null || endTimeStr.isEmpty()) {
                return null;
            }

            DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("hh:mm a", Locale.US);
            LocalTime start = LocalTime.parse(DateTimeUtils.normalizeTime12Hour(startTimeStr), timeFormatter);
            LocalTime end = LocalTime.parse(DateTimeUtils.normalizeTime12Hour(endTimeStr), timeFormatter);

            LocalDateTime dutyStart = LocalDateTime.of(date, start);
            LocalDateTime dutyEnd;
            if (duty.isOvernight() || end.isBefore(start)) {
                dutyEnd = LocalDateTime.of(date.plusDays(1), end);
            } else {
                dutyEnd = LocalDateTime.of(date, end);
            }
            return new DutyTimeRange(dutyStart, dutyEnd);
        } catch (Exception e) {
            return null;
        }
    }

    public static PinnedDutyResolution resolvePinnedDuty(List<Duty> duties, LocalDateTime now) {
        if (duties == null || duties.isEmpty() || now == null) {
            return null;
        }

        Duty activeDuty = null;
        Duty nextUpcomingDuty = null;
        LocalDateTime earliestUpcomingStart = null;

        for (Duty duty : duties) {
            if (duty.isOffOrLeave()) {
                try {
                    LocalDate dutyDate = LocalDate.parse(duty.getDutyDate());
                    LocalDateTime offStart = dutyDate.atStartOfDay();
                    LocalDateTime offEnd = dutyDate.plusDays(1).atStartOfDay();
                    if (!now.isBefore(offStart) && now.isBefore(offEnd)) {
                        if (activeDuty == null) {
                            activeDuty = duty;
                        }
                    } else if (offStart.isAfter(now)) {
                        if (earliestUpcomingStart == null || offStart.isBefore(earliestUpcomingStart)) {
                            earliestUpcomingStart = offStart;
                            nextUpcomingDuty = duty;
                        }
                    }
                } catch (Exception ignored) {}
                continue;
            }

            DutyTimeRange range = parseDutyTimeRange(duty);
            if (range == null) continue;

            if (!now.isBefore(range.start) && now.isBefore(range.end)) {
                activeDuty = duty;
                break; // Found active in-progress duty
            } else if (range.start.isAfter(now)) {
                if (earliestUpcomingStart == null || range.start.isBefore(earliestUpcomingStart)) {
                    earliestUpcomingStart = range.start;
                    nextUpcomingDuty = duty;
                }
            }
        }

        Duty target = (activeDuty != null) ? activeDuty : nextUpcomingDuty;
        if (target == null) return null;

        boolean isCurrent = (activeDuty != null);
        String title;
        String content;
        StringBuilder big = new StringBuilder();

        if (isCurrent) {
            if (target.isOffOrLeave()) {
                title = "Today: " + target.getShiftType().getDisplayName();
                content = target.getShiftType().getDisplayName() + " — Rest and recharge!";
                big.append(content);
            } else {
                title = "Current Duty: " + target.getShiftName();
                content = target.getShiftName() + " · " + target.getFormattedDutyTime() + " (Ends " + target.getEndTime() + ")";
                big.append(content);
                if (target.getWardOrDepartment() != null && !target.getWardOrDepartment().isEmpty()) {
                    big.append("\nWard: ").append(target.getWardOrDepartment());
                }
                if (target.getColleagues() != null && !target.getColleagues().isEmpty()) {
                    big.append("\nWith: ").append(target.getColleagues());
                }
            }
        } else {
            if (target.isOffOrLeave()) {
                title = "Next: " + target.getShiftType().getDisplayName();
                content = DateTimeUtils.formatDisplayDate(target.getDutyDate()) + ": " + target.getShiftType().getDisplayName() + " — Rest and recharge";
                big.append(content);
            } else {
                title = "Next Duty: " + target.getShiftName();
                content = DateTimeUtils.formatDisplayDate(target.getDutyDate()) + ": " + target.getShiftName() + " (" + target.getFormattedDutyTime() + ")";
                big.append(content);
                if (target.getWardOrDepartment() != null && !target.getWardOrDepartment().isEmpty()) {
                    big.append("\nWard: ").append(target.getWardOrDepartment());
                }
                if (target.getColleagues() != null && !target.getColleagues().isEmpty()) {
                    big.append("\nWith: ").append(target.getColleagues());
                }
            }
        }

        return new PinnedDutyResolution(target, isCurrent, title, content, big.toString());
    }

    private static final ExecutorService NOTIF_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * Updates ongoing pinned current/next-duty notification asynchronously in a background thread.
     * Guarantees zero Room database access on the Android UI thread.
     */
    public static void updatePersistentNextDutyNotification(Context context) {
        if (context == null) return;
        final Context appContext = context.getApplicationContext();
        NOTIF_EXECUTOR.execute(() -> {
            try {
                updatePersistentNextDutyNotificationInternal(appContext);
            } catch (Exception e) {
                Log.e(TAG, "Error updating pinned duty notification", e);
            }
        });
    }

    private static void updatePersistentNextDutyNotificationInternal(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean pinEnabled = prefs.getBoolean(KEY_PERSISTENT_NOTIF, false);

        if (!pinEnabled) {
            cancelPinnedNotification(context);
            return;
        }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            cancelPinnedNotification(context);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "POST_NOTIFICATIONS permission not granted");
                return;
            }
        }

        createNotificationChannels(context);

        NotificationManagerCompat nmCompat = NotificationManagerCompat.from(context);
        if (!nmCompat.areNotificationsEnabled()) {
            Log.w(TAG, "Notifications are disabled at the system level");
            return;
        }

        LocalDate today = LocalDate.now();
        String yesterdayIso = today.minusDays(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US));
        String futureIso = today.plusDays(7).format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US));

        NXTShiftDatabase db = NXTShiftDatabase.getInstance(context);
        List<com.nxtshift.app.data.local.DutyEntity> rawDuties = db.dutyDao().getDutiesBetweenDatesDirect(user.getUid(), yesterdayIso, futureIso);

        List<Duty> domainDuties = new ArrayList<>();
        if (rawDuties != null) {
            for (com.nxtshift.app.data.local.DutyEntity e : rawDuties) {
                domainDuties.add(e.toDomain());
            }
        }

        PinnedDutyResolution res = resolvePinnedDuty(domainDuties, LocalDateTime.now());
        if (res == null) {
            cancelPinnedNotification(context);
            return;
        }

        boolean hideOnLockscreen = prefs.getBoolean(KEY_LOCKSCREEN_PRIVACY, false);

        Intent tapIntent = new Intent(context, DashboardActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                context,
                1,
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_PERSISTENT_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(res.displayTitle)
                .setContentText(res.displayContent)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(res.bigText))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(false)
                .setContentIntent(pi);

        if (hideOnLockscreen) {
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE);
            NotificationCompat.Builder publicBuilder = new NotificationCompat.Builder(context, CHANNEL_PERSISTENT_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(res.isCurrentDuty ? "Current Duty" : "Next Duty")
                    .setContentText("Duty scheduled (details hidden on lock screen)")
                    .setOngoing(true)
                    .setContentIntent(pi);
            builder.setPublicVersion(publicBuilder.build());
        } else {
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        }

        nmCompat.notify(NOTIFICATION_ID_PERSISTENT, builder.build());
    }

    public static void cancelPinnedNotification(Context context) {
        if (context == null) return;
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_PERSISTENT);
    }

    public static void cancelAllNotifications(Context context) {
        if (context == null) return;
        cancelPinnedNotification(context);
        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
        nm.cancel(NOTIFICATION_ID_TOMORROW);
        cancelDailyDutyReminder(context);
    }
}
