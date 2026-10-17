package com.nxtshift.app.ui.auth;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Patterns;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseUser;
import com.nxtshift.app.R;
import com.nxtshift.app.data.repository.AuthRepository;
import com.nxtshift.app.databinding.ActivityRegisterBinding;
import com.nxtshift.app.notifications.ShiftNotificationManager;

/**
 * Registration screen: email, password, confirm-password.
 * On successful account creation, sends a verification email
 * and forwards the user to EmailVerificationActivity.
 */
public class RegisterActivity extends AppCompatActivity {

    private ActivityRegisterBinding binding;
    private AuthRepository authRepository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityRegisterBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        authRepository = AuthRepository.getInstance(this);

        binding.btnBack.setOnClickListener(v -> finish());

        binding.btnRegister.setOnClickListener(v -> handleRegister());

        binding.tvSignIn.setOnClickListener(v -> {
            startActivity(new Intent(RegisterActivity.this, LoginActivity.class));
            finish();
        });
    }

    private void handleRegister() {
        String email = binding.etEmail.getText() != null ?
                binding.etEmail.getText().toString().trim() : "";
        String password = binding.etPassword.getText() != null ?
                binding.etPassword.getText().toString() : "";
        String confirmPassword = binding.etConfirmPassword.getText() != null ?
                binding.etConfirmPassword.getText().toString() : "";

        boolean valid = true;

        // Validate email
        if (TextUtils.isEmpty(email) || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.setError(getString(R.string.err_invalid_email));
            valid = false;
        } else {
            binding.tilEmail.setError(null);
        }

        // Validate password length
        if (TextUtils.isEmpty(password) || password.length() < 6) {
            binding.tilPassword.setError(getString(R.string.err_password_too_short));
            valid = false;
        } else {
            binding.tilPassword.setError(null);
        }

        // Validate confirm password
        if (!password.equals(confirmPassword)) {
            binding.tilConfirmPassword.setError(getString(R.string.err_passwords_do_not_match));
            valid = false;
        } else {
            binding.tilConfirmPassword.setError(null);
        }

        if (!valid) return;

        setLoading(true);
        binding.tvErrorMessage.setVisibility(View.GONE);

        authRepository.registerWithEmail(email, password, new AuthRepository.AuthUserCallback() {
            @Override
            public void onSuccess(FirebaseUser user) {
                setLoading(false);
                // Schedule daily reminder
                ShiftNotificationManager.scheduleDailyDutyReminder(RegisterActivity.this);
                // Go to email verification screen
                Intent intent = new Intent(RegisterActivity.this, EmailVerificationActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            }

            @Override
            public void onError(Exception e) {
                setLoading(false);
                binding.tvErrorMessage.setVisibility(View.VISIBLE);
                binding.tvErrorMessage.setText(e.getMessage() != null ?
                        e.getMessage() : getString(R.string.err_generic_auth));
            }
        });
    }

    private void setLoading(boolean isLoading) {
        binding.progressBar.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        binding.btnRegister.setEnabled(!isLoading);
        binding.etEmail.setEnabled(!isLoading);
        binding.etPassword.setEnabled(!isLoading);
        binding.etConfirmPassword.setEnabled(!isLoading);
    }
}
