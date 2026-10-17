package com.nxtshift.app.ui.auth;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.nxtshift.app.R;
import com.nxtshift.app.data.repository.AuthRepository;
import com.nxtshift.app.databinding.ActivityEmailVerificationBinding;
import com.nxtshift.app.ui.dashboard.DashboardActivity;

/**
 * Email-verification holding screen shown immediately after registration.
 * Instructs the user to open their inbox, click the link, then tap "I've Verified".
 * Also offers an open-email-app button, resend button (with 60-second cooldown), and spam guidance.
 */
public class EmailVerificationActivity extends AppCompatActivity {

    private static final int POLLING_INTERVAL_MS = 5000; // Check every 5 s while app is open
    private static final long RESEND_COOLDOWN_MS  = 60_000L;

    private ActivityEmailVerificationBinding binding;
    private AuthRepository authRepository;
    private CountDownTimer resendCooldownTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityEmailVerificationBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        authRepository = AuthRepository.getInstance(this);

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            // Safety: no session → back to login
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        binding.tvEmailAddress.setText(
                getString(R.string.msg_verification_sent_to, user.getEmail()));

        binding.btnOpenEmailApp.setOnClickListener(v -> openEmailApp());

        binding.btnVerified.setOnClickListener(v -> checkVerification());

        binding.btnResend.setOnClickListener(v -> resendVerification());

        binding.tvSignOut.setOnClickListener(v -> signOutAndGoBack());

        startResendCooldown();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Auto-check whenever user comes back to the app (e.g. after tapping the email link)
        checkVerificationSilent();
    }

    // ── Actions ────────────────────────────────────────────────────────────────

    private void checkVerification() {
        setLoading(true);
        authRepository.reloadUserAndCheckVerification(new AuthRepository.AuthActionCallback() {
            @Override
            public void onSuccess() {
                setLoading(false);
                navigateToDashboard();
            }

            @Override
            public void onError(Exception e) {
                setLoading(false);
                binding.tvStatusMessage.setVisibility(View.VISIBLE);
                binding.tvStatusMessage.setText(R.string.msg_email_not_yet_verified);
            }
        });
    }

    /** Silent check on resume — navigates automatically if verified without user action. */
    private void checkVerificationSilent() {
        authRepository.reloadUserAndCheckVerification(new AuthRepository.AuthActionCallback() {
            @Override
            public void onSuccess() {
                navigateToDashboard();
            }

            @Override
            public void onError(Exception e) {
                // Still unverified — stay on screen
            }
        });
    }

    private void resendVerification() {
        authRepository.resendEmailVerification(new AuthRepository.AuthActionCallback() {
            @Override
            public void onSuccess() {
                Toast.makeText(EmailVerificationActivity.this,
                        R.string.msg_verification_resent, Toast.LENGTH_SHORT).show();
                startResendCooldown();
            }

            @Override
            public void onError(Exception e) {
                Toast.makeText(EmailVerificationActivity.this,
                        getString(R.string.err_resend_failed, e.getMessage()),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void signOutAndGoBack() {
        authRepository.signOut(new AuthRepository.AuthActionCallback() {
            @Override
            public void onSuccess() {
                Intent intent = new Intent(EmailVerificationActivity.this, WelcomeActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            }

            @Override
            public void onError(Exception e) {
                onSuccess(); // sign out regardless
            }
        });
    }

    // ── Navigation ─────────────────────────────────────────────────────────────

    private void navigateToDashboard() {
        Intent intent = new Intent(this, DashboardActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    // ── UI Helpers ─────────────────────────────────────────────────────────────

    private void startResendCooldown() {
        binding.btnResend.setEnabled(false);
        binding.tvCountdown.setVisibility(View.VISIBLE);

        if (resendCooldownTimer != null) resendCooldownTimer.cancel();

        resendCooldownTimer = new CountDownTimer(RESEND_COOLDOWN_MS, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                binding.tvCountdown.setText(
                        getString(R.string.resend_countdown, (int) (millisUntilFinished / 1000)));
            }

            @Override
            public void onFinish() {
                binding.tvCountdown.setVisibility(View.GONE);
                binding.btnResend.setEnabled(true);
            }
        }.start();
    }

    private void openEmailApp() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_APP_EMAIL);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception e) {
            try {
                Intent mailtoIntent = new Intent(Intent.ACTION_SENDTO);
                mailtoIntent.setData(Uri.parse("mailto:"));
                startActivity(Intent.createChooser(mailtoIntent, getString(R.string.btn_open_email_app)));
            } catch (Exception ex) {
                Toast.makeText(this, R.string.msg_check_email_client, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void setLoading(boolean isLoading) {
        binding.progressBar.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        binding.btnVerified.setEnabled(!isLoading);
        binding.btnOpenEmailApp.setEnabled(!isLoading);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (resendCooldownTimer != null) resendCooldownTimer.cancel();
    }
}
