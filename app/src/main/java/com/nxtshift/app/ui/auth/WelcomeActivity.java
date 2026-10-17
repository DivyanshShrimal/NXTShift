package com.nxtshift.app.ui.auth;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.nxtshift.app.data.repository.AuthRepository;
import com.nxtshift.app.databinding.ActivityWelcomeBinding;
import com.nxtshift.app.ui.dashboard.DashboardActivity;

/**
 * Entry / Welcome screen.
 * Automatically forwards authenticated + email-verified users to the dashboard.
 * Unverified users are sent to the email-verification screen.
 */
public class WelcomeActivity extends AppCompatActivity {

    private ActivityWelcomeBinding binding;
    private AuthRepository authRepository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        authRepository = AuthRepository.getInstance(this);

        // Session restoration: skip Welcome for returning verified users
        if (authRepository.isUserSignedIn()) {
            if (authRepository.isEmailVerified()) {
                navigateToDashboard();
            } else {
                // Account exists but email never verified — enforce verification
                startActivity(new Intent(this, EmailVerificationActivity.class));
                finish();
            }
            return;
        }

        binding = ActivityWelcomeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.btnGetStarted.setOnClickListener(v ->
                startActivity(new Intent(WelcomeActivity.this, LoginActivity.class)));

        binding.btnCreateAccount.setOnClickListener(v ->
                startActivity(new Intent(WelcomeActivity.this, RegisterActivity.class)));
    }

    private void navigateToDashboard() {
        Intent intent = new Intent(this, DashboardActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
