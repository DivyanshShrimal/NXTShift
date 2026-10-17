package com.nxtshift.app.data.repository;

import android.content.Context;
import android.util.Log;

import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseAuthWeakPasswordException;
import com.google.firebase.auth.FirebaseUser;

/**
 * Handles Firebase Email/Password Authentication: registration, login, password reset,
 * email verification, sign-out, and complete account deletion with multi-device cache safety.
 */
public class AuthRepository {
    private static final String TAG = "AuthRepository";
    private static volatile AuthRepository INSTANCE;

    private final FirebaseAuth auth;
    private final DutyRepository dutyRepository;

    /** Generic success/failure callback used by all auth operations. */
    public interface AuthActionCallback {
        void onSuccess();
        void onError(Exception e);
    }

    /** Callback returned after successful registration/login carrying the signed-in user. */
    public interface AuthUserCallback {
        void onSuccess(FirebaseUser user);
        void onError(Exception e);
    }

    public static AuthRepository getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AuthRepository.class) {
                if (INSTANCE == null) {
                    INSTANCE = new AuthRepository(
                            FirebaseAuth.getInstance(),
                            DutyRepository.getInstance(context)
                    );
                }
            }
        }
        return INSTANCE;
    }

    public AuthRepository(FirebaseAuth auth, DutyRepository dutyRepository) {
        this.auth = auth;
        this.dutyRepository = dutyRepository;
    }

    // ── Session State ──────────────────────────────────────────────────────────

    public boolean isUserSignedIn() {
        return auth.getCurrentUser() != null;
    }

    /**
     * Returns true only if the signed-in user has verified their email address.
     * Guests who skipped verification are still signed in but unverified.
     */
    public boolean isEmailVerified() {
        FirebaseUser user = auth.getCurrentUser();
        return user != null && user.isEmailVerified();
    }

    public FirebaseUser getCurrentUser() {
        return auth.getCurrentUser();
    }

    public String getCurrentUid() {
        FirebaseUser user = auth.getCurrentUser();
        return user != null ? user.getUid() : null;
    }

    public String getCurrentEmail() {
        FirebaseUser user = auth.getCurrentUser();
        return user != null ? user.getEmail() : "";
    }

    public String getCurrentDisplayName() {
        FirebaseUser user = auth.getCurrentUser();
        return user != null && user.getDisplayName() != null ? user.getDisplayName() : "";
    }

    // ── Registration ───────────────────────────────────────────────────────────

    /**
     * Creates a new Firebase account with email and password,
     * then immediately sends an email verification link.
     */
    public void registerWithEmail(final String email,
                                  final String password,
                                  final AuthUserCallback callback) {
        auth.createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener((AuthResult result) -> {
                    FirebaseUser user = result.getUser();
                    if (user == null) {
                        callback.onError(new IllegalStateException("Registration succeeded but user is null"));
                        return;
                    }
                    // Send verification link immediately after account creation
                    user.sendEmailVerification()
                            .addOnCompleteListener(task -> {
                                // Proceed regardless of whether verification email sent
                                Log.d(TAG, "Verification email sent to " + email);
                                callback.onSuccess(user);
                            });
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Registration failed for " + email, e);
                    callback.onError(translateAuthException(e));
                });
    }

    // ── Sign In ────────────────────────────────────────────────────────────────

    /**
     * Signs in an existing user with email and password.
     */
    public void signInWithEmail(final String email,
                                final String password,
                                final AuthUserCallback callback) {
        auth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener(result -> {
                    FirebaseUser user = result.getUser();
                    Log.d(TAG, "Sign-in successful for UID: " + (user != null ? user.getUid() : "null"));
                    callback.onSuccess(user);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Sign-in failed for " + email, e);
                    callback.onError(translateAuthException(e));
                });
    }

    // ── Password Reset ─────────────────────────────────────────────────────────

    /**
     * Sends a password-reset link to the provided email address via Firebase.
     * Does not require the user to be signed in.
     */
    public void sendPasswordResetEmail(final String email, final AuthActionCallback callback) {
        auth.sendPasswordResetEmail(email)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "Password reset email sent to " + email);
                    callback.onSuccess();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Password reset failed for " + email, e);
                    callback.onError(e);
                });
    }

    // ── Email Verification ─────────────────────────────────────────────────────

    /**
     * Re-sends the verification link to the currently signed-in user's email.
     */
    public void resendEmailVerification(final AuthActionCallback callback) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            callback.onError(new IllegalStateException("No active session"));
            return;
        }
        user.sendEmailVerification()
                .addOnSuccessListener(aVoid -> callback.onSuccess())
                .addOnFailureListener(callback::onError);
    }

    /**
     * Reloads the FirebaseUser object from the server so that isEmailVerified()
     * reflects the latest state (useful after user clicks the verification link).
     */
    public void reloadUserAndCheckVerification(final AuthActionCallback callback) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            callback.onError(new IllegalStateException("No active session"));
            return;
        }
        user.reload()
                .addOnSuccessListener(aVoid -> {
                    if (auth.getCurrentUser() != null && auth.getCurrentUser().isEmailVerified()) {
                        callback.onSuccess();
                    } else {
                        callback.onError(new IllegalStateException("Email not yet verified"));
                    }
                })
                .addOnFailureListener(callback::onError);
    }

    // ── Sign Out ───────────────────────────────────────────────────────────────

    /**
     * Signs out the current user and clears account-specific cached records from local Room.
     * Ensures no shift data from this account leaks to subsequent logins on the same device.
     */
    public void signOut(final AuthActionCallback callback) {
        final String uid = getCurrentUid();
        dutyRepository.clearLocalCacheForAccount(uid, new DutyRepository.RepositoryCallback<Void>() {
            @Override
            public void onComplete(Void result) {
                auth.signOut();
                if (callback != null) callback.onSuccess();
            }

            @Override
            public void onError(Exception e) {
                // Still sign out even if local clear fails
                auth.signOut();
                if (callback != null) callback.onSuccess();
            }
        });
    }

    // ── Account Deletion ───────────────────────────────────────────────────────

    /**
     * Deletes user account: purges remote Firestore records, deletes local Room cache,
     * and erases the Firebase Auth credential.
     */
    public void deleteAccount(final AuthActionCallback callback) {
        final FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            if (callback != null) callback.onError(new IllegalStateException("No active user session"));
            return;
        }

        final String uid = user.getUid();
        dutyRepository.deleteEntireAccountData(uid, new DutyRepository.RepositoryCallback<Void>() {
            @Override
            public void onComplete(Void result) {
                user.delete()
                        .addOnSuccessListener(aVoid -> {
                            Log.i(TAG, "Account deleted successfully from Firebase Auth");
                            if (callback != null) callback.onSuccess();
                        })
                        .addOnFailureListener(e -> {
                            Log.e(TAG, "Firebase user delete failed", e);
                            if (callback != null) callback.onError(e);
                        });
            }

            @Override
            public void onError(Exception e) {
                user.delete().addOnCompleteListener(task -> {
                    if (callback != null) callback.onError(e);
                });
            }
        });
    }

    // ── Internal Helpers ───────────────────────────────────────────────────────

    /**
     * Translates raw Firebase exceptions into cleaner user-facing messages.
     */
    private Exception translateAuthException(Exception e) {
        if (e instanceof FirebaseAuthWeakPasswordException) {
            return new Exception("Password must be at least 6 characters.");
        } else if (e instanceof FirebaseAuthInvalidCredentialsException) {
            return new Exception("Invalid email address or incorrect password.");
        } else if (e instanceof FirebaseAuthUserCollisionException) {
            return new Exception("An account already exists with this email address.");
        }
        return e;
    }
}
