package com.nxtshift.app.ui.auth;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Patterns;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseUser;
import com.nxtshift.app.R;
import com.nxtshift.app.data.repository.AuthRepository;
import com.nxtshift.app.databinding.ActivityLoginBinding;
import com.nxtshift.app.notifications.ShiftNotificationManager;
import com.nxtshift.app.ui.dashboard.DashboardActivity;

/**
 * Email + Password login screen.
 * On successful sign-in with a verified email, proceeds to DashboardActivity.
 * Unverified accounts are forwarded to EmailVerificationActivity to complete verification.
 */
public class LoginActivity extends AppCompatActivity {

    private ActivityLoginBinding binding;
    private AuthRepository authRepository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        authRepository = AuthRepository.getInstance(this);

        binding.btnBack.setOnClickListener(v -> finish());

        binding.btnSignIn.setOnClickListener(v -> handleSignIn());

        binding.tvForgotPassword.setOnClickListener(v ->
                startActivity(new Intent(LoginActivity.this, ForgotPasswordActivity.class)));

        binding.tvCreateAccount.setOnClickListener(v -> {
            startActivity(new Intent(LoginActivity.this, RegisterActivity.class));
            finish();
        });
    }

    private void handleSignIn() {
        String email = binding.etEmail.getText() != null ?
                binding.etEmail.getText().toString().trim() : "";
        String password = binding.etPassword.getText() != null ?
                binding.etPassword.getText().toString() : "";

        // Validate email
        if (TextUtils.isEmpty(email) || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.setError(getString(R.string.err_invalid_email));
            return;
        }
        binding.tilEmail.setError(null);

        // Validate password
        if (TextUtils.isEmpty(password)) {
            binding.tilPassword.setError(getString(R.string.err_password_required));
            return;
        }
        binding.tilPassword.setError(null);

        setLoading(true);

        authRepository.signInWithEmail(email, password, new AuthRepository.AuthUserCallback() {
            @Override
            public void onSuccess(FirebaseUser user) {
                setLoading(false);
                if (user == null) {
                    showError(getString(R.string.err_generic_auth));
                    return;
                }

                // Schedule daily reminder immediately after sign-in
                ShiftNotificationManager.scheduleDailyDutyReminder(LoginActivity.this);

                if (!user.isEmailVerified()) {
                    // Send them to the verification screen — don't let unverified users access the app
                    startActivity(new Intent(LoginActivity.this, EmailVerificationActivity.class));
                    finish();
                } else {
                    navigateToDashboard();
                }
            }

            @Override
            public void onError(Exception e) {
                setLoading(false);
                showError(e.getMessage());
            }
        });
    }

    private void navigateToDashboard() {
        Intent intent = new Intent(this, DashboardActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void showError(String message) {
        binding.tvErrorMessage.setVisibility(View.VISIBLE);
        binding.tvErrorMessage.setText(message != null ? message : getString(R.string.err_generic_auth));
    }

    private void setLoading(boolean isLoading) {
        binding.progressBar.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        binding.btnSignIn.setEnabled(!isLoading);
        binding.etEmail.setEnabled(!isLoading);
        binding.etPassword.setEnabled(!isLoading);
    }
}
