package com.nxtshift.app.ui.auth;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Patterns;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.nxtshift.app.R;
import com.nxtshift.app.data.repository.AuthRepository;
import com.nxtshift.app.databinding.ActivityForgotPasswordBinding;

/**
 * Forgot-password screen.
 * Accepts an email address and triggers Firebase's password-reset email flow.
 * Requires no authentication — available pre-login.
 */
public class ForgotPasswordActivity extends AppCompatActivity {

    private ActivityForgotPasswordBinding binding;
    private AuthRepository authRepository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityForgotPasswordBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        authRepository = AuthRepository.getInstance(this);

        binding.btnBack.setOnClickListener(v -> finish());

        binding.btnSendReset.setOnClickListener(v -> handleSendReset());
    }

    private void handleSendReset() {
        String email = binding.etEmail.getText() != null ?
                binding.etEmail.getText().toString().trim() : "";

        if (TextUtils.isEmpty(email) || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.setError(getString(R.string.err_invalid_email));
            return;
        }
        binding.tilEmail.setError(null);

        setLoading(true);
        binding.tvErrorMessage.setVisibility(View.GONE);
        binding.layoutSuccess.setVisibility(View.GONE);

        authRepository.sendPasswordResetEmail(email, new AuthRepository.AuthActionCallback() {
            @Override
            public void onSuccess() {
                setLoading(false);
                // Show success state
                binding.layoutSuccess.setVisibility(View.VISIBLE);
                binding.tvSuccessMessage.setText(
                        getString(R.string.msg_reset_email_sent, email));
                binding.btnSendReset.setEnabled(false);
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
        binding.btnSendReset.setEnabled(!isLoading);
        binding.etEmail.setEnabled(!isLoading);
    }
}
