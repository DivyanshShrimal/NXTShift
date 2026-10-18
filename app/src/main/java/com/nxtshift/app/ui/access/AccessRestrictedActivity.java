package com.nxtshift.app.ui.access;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.nxtshift.app.R;
import com.nxtshift.app.data.model.AccessState;
import com.nxtshift.app.data.model.GlobalAccessConfig;
import com.nxtshift.app.data.model.UserAccessRecord;
import com.nxtshift.app.data.repository.AccessControlRepository;
import com.nxtshift.app.data.repository.AuthRepository;
import com.nxtshift.app.databinding.ActivityAccessRestrictedBinding;
import com.nxtshift.app.notifications.ShiftNotificationManager;
import com.nxtshift.app.ui.auth.WelcomeActivity;
import com.nxtshift.app.ui.calendar.CalendarActivity;

/**
 * Screen displayed when application access is globally disabled or individually revoked.
 * In accordance with the offline-first policy, medical staff can still tap
 * "View Offline Calendar" to inspect previously saved duty schedules from Room SQLite.
 */
public class AccessRestrictedActivity extends AppCompatActivity {

    public static final String EXTRA_RESTRICTED_STATE = "extra_restricted_state";
    public static final String EXTRA_RESTRICTED_MESSAGE = "extra_restricted_message";

    private ActivityAccessRestrictedBinding binding;
    private AccessControlRepository accessControlRepository;
    private AuthRepository authRepository;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAccessRestrictedBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        accessControlRepository = AccessControlRepository.getInstance(this);
        authRepository = AuthRepository.getInstance(this);

        setupUI();
        observeAccessState();
    }

    private void setupUI() {
        // Initial values from Intent extras or current state
        updateDisplay(accessControlRepository.getCurrentAccessState());

        // View Offline Calendar button
        binding.btnViewOfflineCalendar.setOnClickListener(v -> {
            Intent intent = new Intent(this, CalendarActivity.class);
            startActivity(intent);
        });

        // Recheck Access button
        binding.btnRetryAccess.setOnClickListener(v -> recheckAccess());

        // Sign Out button
        binding.btnSignOut.setOnClickListener(v -> confirmSignOut());
    }

    private void observeAccessState() {
        accessControlRepository.getAccessStateLiveData().observe(this, state -> {
            if (state == AccessState.ALLOWED) {
                // Access has been restored! Dismiss screen and resume normal operations
                Toast.makeText(this, "Access re-authorized. Resuming application.", Toast.LENGTH_SHORT).show();
                finish();
            } else {
                updateDisplay(state);
            }
        });
    }

    private void updateDisplay(AccessState state) {
        if (state == null) state = AccessState.GLOBAL_DISABLED;

        GlobalAccessConfig globalConfig = accessControlRepository.getCurrentGlobalConfig();
        UserAccessRecord userAccess = accessControlRepository.getCurrentUserAccess();

        switch (state) {
            case GLOBAL_DISABLED:
                binding.tvRestrictedTitle.setText("Maintenance Mode");
                binding.tvRestrictedBadge.setText("GLOBAL MAINTENANCE");
                String globalMsg = (globalConfig != null && globalConfig.getMessage() != null && !globalConfig.getMessage().isEmpty())
                        ? globalConfig.getMessage()
                        : "NXTShift is currently offline for scheduled maintenance. Cloud synchronization has been temporarily disabled.";
                binding.tvRestrictedMessage.setText(globalMsg);
                binding.ivRestrictedIcon.setImageResource(R.drawable.ic_warning);
                break;

            case USER_REVOKED:
                binding.tvRestrictedTitle.setText("Account Access Revoked");
                binding.tvRestrictedBadge.setText("USER ACCESS REVOKED");
                String userReason = (userAccess != null && userAccess.getReason() != null && !userAccess.getReason().isEmpty())
                        ? userAccess.getReason()
                        : "Your account access to NXTShift online cloud services has been revoked by an administrator.";
                binding.tvRestrictedMessage.setText(userReason);
                binding.ivRestrictedIcon.setImageResource(R.drawable.ic_warning);
                break;

            case OFFLINE_UNVERIFIED:
                binding.tvRestrictedTitle.setText("Offline Mode");
                binding.tvRestrictedBadge.setText("DISCONNECTED");
                binding.tvRestrictedMessage.setText("You are currently offline. Cloud sync requires an active connection to verify permissions, but your local schedule remains accessible.");
                binding.ivRestrictedIcon.setImageResource(R.drawable.ic_warning);
                break;

            case ERROR:
            default:
                binding.tvRestrictedTitle.setText("Access Error");
                binding.tvRestrictedBadge.setText("ERROR");
                binding.tvRestrictedMessage.setText("Unable to verify access status. Cloud synchronization is suspended for safety.");
                binding.ivRestrictedIcon.setImageResource(R.drawable.ic_warning);
                break;
        }
    }

    private void recheckAccess() {
        binding.progressBar.setVisibility(View.VISIBLE);
        binding.btnRetryAccess.setEnabled(false);

        String uid = authRepository.getCurrentUid();
        if (uid != null) {
            accessControlRepository.startMonitoring(uid);
        }

        binding.progressBar.postDelayed(() -> {
            binding.progressBar.setVisibility(View.GONE);
            binding.btnRetryAccess.setEnabled(true);
            AccessState state = accessControlRepository.getCurrentAccessState();
            if (state == AccessState.ALLOWED) {
                Toast.makeText(this, "Access verified successfully.", Toast.LENGTH_SHORT).show();
                finish();
            } else {
                Toast.makeText(this, "Access status: " + state.name(), Toast.LENGTH_SHORT).show();
                updateDisplay(state);
            }
        }, 1200);
    }

    private void confirmSignOut() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.confirm_sign_out_title)
                .setMessage(R.string.confirm_sign_out_message)
                .setPositiveButton(R.string.dialog_confirm, (dialog, which) -> {
                    ShiftNotificationManager.cancelAllNotifications(this);
                    accessControlRepository.stopMonitoring();
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

    private void navigateToWelcome() {
        Intent intent = new Intent(this, WelcomeActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    public void onBackPressed() {
        // Medical staff pressing back can exit or jump to offline calendar
        super.onBackPressed();
    }
}
