package com.nxtshift.app.ui.settings;

import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.nxtshift.app.R;
import com.nxtshift.app.data.repository.AuthRepository;
import com.nxtshift.app.data.repository.DutyRepository;
import com.nxtshift.app.databinding.ActivitySettingsBinding;
import com.nxtshift.app.notifications.ShiftNotificationManager;
import com.nxtshift.app.ui.auth.WelcomeActivity;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class SettingsActivity extends AppCompatActivity {

    private ActivitySettingsBinding binding;
    private SharedPreferences prefs;
    private AuthRepository authRepository;
    private DutyRepository dutyRepository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        authRepository = AuthRepository.getInstance(this);
        dutyRepository = DutyRepository.getInstance(this);
        prefs = getSharedPreferences(ShiftNotificationManager.PREFS_NAME, Context.MODE_PRIVATE);

        setupUI();
    }

    private void setupUI() {
        binding.btnBack.setOnClickListener(v -> finish());

        // 1. Account info
        binding.tvAccountEmail.setText(authRepository.getCurrentEmail());
        binding.tvAccountUid.setText(authRepository.getCurrentUid());

        // 2. Daily Reminder Toggle
        boolean remindersEnabled = prefs.getBoolean(ShiftNotificationManager.KEY_REMINDERS_ENABLED, true);
        binding.switchDailyReminder.setChecked(remindersEnabled);
        binding.switchDailyReminder.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(ShiftNotificationManager.KEY_REMINDERS_ENABLED, isChecked).apply();
            if (isChecked) {
                ShiftNotificationManager.scheduleDailyDutyReminder(this);
            } else {
                ShiftNotificationManager.cancelDailyDutyReminder(this);
            }
        });

        // 3. Reminder Time
        updateReminderTimeDisplay();
        binding.layoutReminderTime.setOnClickListener(v -> showReminderTimePicker());

        // 4. Pin Notification Toggle
        boolean persistentEnabled = prefs.getBoolean(ShiftNotificationManager.KEY_PERSISTENT_NOTIF, false);
        binding.switchPersistentNotif.setChecked(persistentEnabled);
        binding.switchPersistentNotif.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                    Toast.makeText(this, "Notifications are disabled. Please enable them in system settings to use Pin notification.", Toast.LENGTH_LONG).show();
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
                    }
                }
            }
            prefs.edit().putBoolean(ShiftNotificationManager.KEY_PERSISTENT_NOTIF, isChecked).apply();
            ShiftNotificationManager.updatePersistentNextDutyNotification(this);
        });

        // 5. Lockscreen Privacy
        boolean lockscreenPrivacy = prefs.getBoolean(ShiftNotificationManager.KEY_LOCKSCREEN_PRIVACY, false);
        binding.switchLockscreenPrivacy.setChecked(lockscreenPrivacy);
        binding.switchLockscreenPrivacy.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(ShiftNotificationManager.KEY_LOCKSCREEN_PRIVACY, isChecked).apply();
            ShiftNotificationManager.updatePersistentNextDutyNotification(this);
        });

        // 6. Manual Sync
        binding.btnManualSync.setOnClickListener(v -> {
            Toast.makeText(this, "Synchronizing with Cloud Firestore...", Toast.LENGTH_SHORT).show();
            dutyRepository.syncWithCloud(authRepository.getCurrentUid(), new DutyRepository.RepositoryCallback<Boolean>() {
                @Override
                public void onComplete(Boolean result) {
                    Toast.makeText(SettingsActivity.this, "Cloud synchronization complete", Toast.LENGTH_SHORT).show();
                    ShiftNotificationManager.updatePersistentNextDutyNotification(SettingsActivity.this);
                }

                @Override
                public void onError(Exception e) {
                    Toast.makeText(SettingsActivity.this, "Sync offline: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        });

        // 7. Sign Out
        binding.btnSignOut.setOnClickListener(v -> confirmSignOut());

        // 8. Delete Account
        binding.btnDeleteAccount.setOnClickListener(v -> confirmDeleteAccount());

        // 9. Privacy Policy
        binding.cardPrivacyPolicy.setOnClickListener(v -> showPrivacyPolicyDialog());
    }

    private void updateReminderTimeDisplay() {
        int hour = prefs.getInt(ShiftNotificationManager.KEY_REMINDER_HOUR, 20); // Default 8 PM
        int minute = prefs.getInt(ShiftNotificationManager.KEY_REMINDER_MINUTE, 0);
        LocalTime lt = LocalTime.of(hour, minute);
        binding.tvReminderTimeValue.setText(lt.format(DateTimeFormatter.ofPattern("hh:mm a", Locale.US)));
    }

    private void showReminderTimePicker() {
        int hour = prefs.getInt(ShiftNotificationManager.KEY_REMINDER_HOUR, 20);
        int minute = prefs.getInt(ShiftNotificationManager.KEY_REMINDER_MINUTE, 0);

        TimePickerDialog dialog = new TimePickerDialog(
                this,
                (view, hourOfDay, minuteOfHour) -> {
                    prefs.edit()
                            .putInt(ShiftNotificationManager.KEY_REMINDER_HOUR, hourOfDay)
                            .putInt(ShiftNotificationManager.KEY_REMINDER_MINUTE, minuteOfHour)
                            .apply();
                    updateReminderTimeDisplay();
                    ShiftNotificationManager.scheduleDailyDutyReminder(this);
                    Toast.makeText(this, "Reminder alert time updated", Toast.LENGTH_SHORT).show();
                },
                hour,
                minute,
                false
        );
        dialog.show();
    }

    private void confirmSignOut() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.confirm_sign_out_title)
                .setMessage(R.string.confirm_sign_out_message)
                .setPositiveButton(R.string.dialog_confirm, (dialog, which) -> {
                    ShiftNotificationManager.cancelAllNotifications(this);
                    authRepository.signOut(new AuthRepository.AuthActionCallback() {
                        @Override
                        public void onSuccess() {
                            navigateToWelcome();
                        }

                        @Override
                        public void onError(Exception e) {
                            navigateToWelcome();
                        }
                    });
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void confirmDeleteAccount() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.confirm_delete_account_title)
                .setMessage(R.string.confirm_delete_account_message)
                .setPositiveButton("Erase Everything", (dialog, which) -> {
                    ShiftNotificationManager.cancelAllNotifications(this);
                    Toast.makeText(this, "Deleting account and all associated cloud data...", Toast.LENGTH_SHORT).show();
                    authRepository.deleteAccount(new AuthRepository.AuthActionCallback() {
                        @Override
                        public void onSuccess() {
                            Toast.makeText(SettingsActivity.this, "Account and cloud records successfully deleted", Toast.LENGTH_LONG).show();
                            navigateToWelcome();
                        }

                        @Override
                        public void onError(Exception e) {
                            Toast.makeText(SettingsActivity.this, "Notice: " + e.getLocalizedMessage(), Toast.LENGTH_LONG).show();
                            navigateToWelcome();
                        }
                    });
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void showPrivacyPolicyDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Privacy & Data Safety Policy")
                .setMessage("NXTShift is engineered with privacy-first architecture for healthcare staff:\n\n" +
                        "1. Local On-Device OCR: Uploading of roster screenshots or raw OCR text is strictly blocked. All image recognition runs entirely on your phone via Google ML Kit bundled models.\n\n" +
                        "2. Cloud Isolation: Your structured duty rosters are synchronized to Cloud Firestore and protected by strict Firestore Security Rules. No user can view or alter another nurse's roster.\n\n" +
                        "3. Multi-User Safety: If another user signs in on this device, the local cache is strictly isolated and purged upon sign-out to prevent data leaks.\n\n" +
                        "4. Account Deletion: You can permanently erase all your duty records and profile at any time directly from Settings.")
                .setPositiveButton("I Understand", null)
                .show();
    }

    private void navigateToWelcome() {
        Intent intent = new Intent(this, WelcomeActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
