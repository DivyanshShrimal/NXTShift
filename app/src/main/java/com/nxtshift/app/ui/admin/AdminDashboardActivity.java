package com.nxtshift.app.ui.admin;

import android.content.DialogInterface;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.nxtshift.app.R;
import com.nxtshift.app.data.model.MonthlyRoster;
import com.nxtshift.app.data.remote.CentralRosterService;
import com.nxtshift.app.data.repository.AuthRepository;
import com.nxtshift.app.databinding.ActivityAdminDashboardBinding;
import com.nxtshift.app.ocr.RosterCsvParser;
import com.nxtshift.app.data.model.GlobalAccessConfig;
import com.nxtshift.app.data.model.UserAccessRecord;
import com.nxtshift.app.data.repository.AccessControlRepository;
import com.nxtshift.app.util.DateTimeUtils;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.Calendar;
import java.util.Locale;

public class AdminDashboardActivity extends AppCompatActivity {
    private static final String TAG = "AdminDashboardActivity";

    private ActivityAdminDashboardBinding binding;
    private CentralRosterService centralRosterService;
    private AuthRepository authRepository;
    private AdminRosterPreviewAdapter previewAdapter;

    private String currentUid;
    private String currentEmail;
    private AccessControlRepository accessControlRepository;
    private UserAccessRecord searchedUserRecord;

    private int selectedYear;
    private int selectedMonth; // 1-12
    private MonthlyRoster activeRoster;
    private MonthlyRoster existingPublishedRoster;

    private final ActivityResultLauncher<String[]> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    handleSelectedFile(uri);
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAdminDashboardBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        authRepository = AuthRepository.getInstance(this);
        if (!authRepository.isUserSignedIn()) {
            Toast.makeText(this, "Please sign in first.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        currentUid = authRepository.getCurrentUid();
        currentEmail = authRepository.getCurrentEmail();
        accessControlRepository = AccessControlRepository.getInstance(this);
        centralRosterService = new CentralRosterService(accessControlRepository);

        // Initialize default month/year (current month)
        LocalDate now = LocalDate.now();
        selectedYear = now.getYear();
        selectedMonth = now.getMonthValue();

        setupUI();
        verifyAdminAccess();
    }

    private void setupUI() {
        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnFormatHelp.setOnClickListener(v -> showFormatHelpDialog());

        binding.btnChangeMonth.setOnClickListener(v -> showMonthYearPickerDialog());

        binding.btnChooseFile.setOnClickListener(v -> launchFilePicker());

        previewAdapter = new AdminRosterPreviewAdapter();
        binding.rvAdminPreview.setLayoutManager(new LinearLayoutManager(this));
        binding.rvAdminPreview.setAdapter(previewAdapter);

        binding.btnSaveDraft.setOnClickListener(v -> saveDraft());
        binding.btnPublishRoster.setOnClickListener(v -> confirmPublishRoster());

        setupAccessControlUI();
        updateMonthDisplay();
    }

    private void verifyAdminAccess() {
        binding.progressBar.setVisibility(View.VISIBLE);
        binding.cardUpload.setVisibility(View.GONE);
        binding.cardAccessControl.setVisibility(View.GONE);

        centralRosterService.checkIsAdmin(currentUid, new CentralRosterService.Callback<Boolean>() {
            @Override
            public void onSuccess(Boolean isAdmin) {
                binding.progressBar.setVisibility(View.GONE);
                if (Boolean.TRUE.equals(isAdmin)) {
                    binding.cardUpload.setVisibility(View.VISIBLE);
                    binding.cardAccessControl.setVisibility(View.VISIBLE);
                    loadCurrentMonthRosterStatus();
                    loadGlobalAccessConfig();
                } else {
                    new MaterialAlertDialogBuilder(AdminDashboardActivity.this)
                            .setTitle("Access Restricted")
                            .setMessage("Only authorized hospital administrator accounts can access the Central Roster Publishing Portal.\n\nPermissions are strictly enforced via Firestore Security Rules.")
                            .setPositiveButton("Close", (dialog, which) -> finish())
                            .setCancelable(false)
                            .show();
                }
            }

            @Override
            public void onError(Exception e) {
                binding.progressBar.setVisibility(View.GONE);
                Toast.makeText(AdminDashboardActivity.this, "Security check failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    private void updateMonthDisplay() {
        String monthName = Month.of(selectedMonth).getDisplayName(TextStyle.FULL, Locale.US);
        binding.tvSelectedMonthYear.setText(monthName + " " + selectedYear);
    }

    private void showMonthYearPickerDialog() {
        final String[] months = {
                "January", "February", "March", "April", "May", "June",
                "July", "August", "September", "October", "November", "December"
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle("Select Roster Month")
                .setSingleChoiceItems(months, selectedMonth - 1, (dialog, which) -> {
                    selectedMonth = which + 1;
                    updateMonthDisplay();
                    dialog.dismiss();
                    loadCurrentMonthRosterStatus();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void loadCurrentMonthRosterStatus() {
        binding.tvStatusTitle.setText("Status: Checking Firestore...");
        binding.tvStatusSubtitle.setText("Querying central publication status for " + binding.tvSelectedMonthYear.getText());

        centralRosterService.fetchPublishedRoster(selectedYear, selectedMonth, new CentralRosterService.Callback<MonthlyRoster>() {
            @Override
            public void onSuccess(MonthlyRoster published) {
                existingPublishedRoster = published;

                if (published != null) {
                    binding.viewStatusDot.setBackgroundTintList(ContextCompat.getColorStateList(AdminDashboardActivity.this, R.color.status_synced));
                    binding.tvStatusTitle.setText("Published: Version " + published.getVersion());
                    String dateStr = published.getPublishedAt() > 0 ? DateTimeUtils.formatDisplayDate(DateTimeUtils.formatTimestampToIso(published.getPublishedAt())) : "Recently";
                    String by = published.getPublishedByEmail() != null && !published.getPublishedByEmail().isEmpty() ? " by " + published.getPublishedByEmail() : "";
                    binding.tvStatusSubtitle.setText("Published on " + dateStr + by + " · " + published.getDays().size() + " days · " + published.getStaffList().size() + " staff");

                    if (activeRoster == null) {
                        displayRosterPreview(published, false);
                    }
                } else {
                    // Check for draft
                    checkDraftRoster();
                }
            }

            @Override
            public void onError(Exception e) {
                checkDraftRoster();
            }
        });
    }

    private void checkDraftRoster() {
        centralRosterService.fetchDraftRoster(selectedYear, selectedMonth, new CentralRosterService.Callback<MonthlyRoster>() {
            @Override
            public void onSuccess(MonthlyRoster draft) {
                if (draft != null) {
                    binding.viewStatusDot.setBackgroundTintList(ContextCompat.getColorStateList(AdminDashboardActivity.this, R.color.status_pending));
                    binding.tvStatusTitle.setText("Draft Saved (Unpublished)");
                    binding.tvStatusSubtitle.setText("Last saved: " + DateTimeUtils.formatTimestampToIso(draft.getUpdatedAt()) + " · Ready to review and publish");
                    if (activeRoster == null) {
                        displayRosterPreview(draft, false);
                    }
                } else {
                    binding.viewStatusDot.setBackgroundTintList(ContextCompat.getColorStateList(AdminDashboardActivity.this, R.color.text_muted));
                    binding.tvStatusTitle.setText("No Roster Uploaded Yet");
                    binding.tvStatusSubtitle.setText("Upload an Excel (.xlsx) or CSV file below to create this month's schedule.");
                    if (activeRoster == null) {
                        binding.layoutPreviewContainer.setVisibility(View.GONE);
                        binding.layoutAdminFooter.setVisibility(View.GONE);
                    }
                }
            }

            @Override
            public void onError(Exception e) {
                binding.tvStatusTitle.setText("Status: Offline or Unknown");
                binding.tvStatusSubtitle.setText(e.getMessage());
            }
        });
    }

    private void launchFilePicker() {
        String[] mimeTypes = {
                "text/csv",
                "text/comma-separated-values",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "*/*"
        };
        filePickerLauncher.launch(mimeTypes);
    }

    private void handleSelectedFile(Uri uri) {
        String fileName = getFileName(uri);
        binding.tvSelectedFileName.setVisibility(View.VISIBLE);
        binding.tvSelectedFileName.setText("Processing: " + fileName);
        binding.progressBar.setVisibility(View.VISIBLE);

        new Thread(() -> {
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is == null) throw new IllegalArgumentException("Cannot open file stream");

                RosterCsvParser.ParseResult result = RosterCsvParser.parse(is, fileName, selectedYear, selectedMonth);

                runOnUiThread(() -> {
                    binding.progressBar.setVisibility(View.GONE);
                    binding.tvSelectedFileName.setText("Loaded: " + fileName);
                    handleParseResult(result);
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    binding.progressBar.setVisibility(View.GONE);
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("Parsing Error")
                            .setMessage("Failed to parse file: " + e.getMessage() + "\n\nPlease ensure the spreadsheet contains valid Date and Shift columns.")
                            .setPositiveButton("Dismiss", null)
                            .show();
                });
            }
        }).start();
    }

    private void handleParseResult(RosterCsvParser.ParseResult result) {
        if (!result.errors.isEmpty()) {
            binding.layoutPreviewContainer.setVisibility(View.VISIBLE);
            binding.layoutAdminFooter.setVisibility(View.GONE);

            binding.tvValidationBadge.setText("PARSING ERRORS");
            binding.tvValidationBadge.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.status_error));

            StringBuilder sb = new StringBuilder();
            for (String err : result.errors) {
                sb.append("• ").append(err).append("\n");
            }
            binding.tvValidationIssues.setVisibility(View.VISIBLE);
            binding.tvValidationIssues.setText(sb.toString().trim());
            binding.tvValidationIssues.setTextColor(ContextCompat.getColor(this, R.color.status_error));

            binding.tvRosterMetrics.setText("Failed to parse roster");
            binding.tvStaffListPreview.setVisibility(View.GONE);
            return;
        }

        activeRoster = result.roster;
        displayRosterPreview(result.roster, true);

        // Display warnings if any
        if (!result.warnings.isEmpty()) {
            binding.tvValidationBadge.setText("WARNINGS DETECTED");
            binding.tvValidationBadge.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.status_pending));

            StringBuilder sb = new StringBuilder();
            sb.append("Please review ").append(result.warnings.size()).append(" non-fatal warnings before publishing:\n");
            int count = 0;
            for (String w : result.warnings) {
                if (count++ < 5) {
                    sb.append("• ").append(w).append("\n");
                }
            }
            if (result.warnings.size() > 5) {
                sb.append("• ... and ").append(result.warnings.size() - 5).append(" more notices.");
            }
            binding.tvValidationIssues.setVisibility(View.VISIBLE);
            binding.tvValidationIssues.setText(sb.toString().trim());
            binding.tvValidationIssues.setTextColor(ContextCompat.getColor(this, R.color.status_pending));
        } else {
            binding.tvValidationBadge.setText("VALID ROSTER");
            binding.tvValidationBadge.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.status_synced));
            binding.tvValidationIssues.setVisibility(View.GONE);
        }
    }

    private void displayRosterPreview(MonthlyRoster roster, boolean fromNewUpload) {
        if (roster == null) return;
        activeRoster = roster;

        binding.layoutPreviewContainer.setVisibility(View.VISIBLE);
        binding.layoutAdminFooter.setVisibility(View.VISIBLE);

        binding.tvRosterMetrics.setText(roster.getDays().size() + " Days · " +
                roster.getStaffList().size() + " Staff Members · " +
                roster.getShiftColumns().size() + " Shift Columns");

        if (!roster.getStaffList().isEmpty()) {
            binding.tvStaffListPreview.setVisibility(View.VISIBLE);
            binding.tvStaffListPreview.setText("Approved Staff: " + String.join(", ", roster.getStaffList()));
        } else {
            binding.tvStaffListPreview.setVisibility(View.GONE);
        }

        previewAdapter.setDays(roster.getDays());

        if (fromNewUpload) {
            Toast.makeText(this, "Loaded " + roster.getDays().size() + " days and " + roster.getStaffList().size() + " staff members", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveDraft() {
        if (activeRoster == null) {
            Toast.makeText(this, "No roster loaded to save", Toast.LENGTH_SHORT).show();
            return;
        }

        binding.progressBar.setVisibility(View.VISIBLE);
        centralRosterService.saveDraftRoster(activeRoster, new CentralRosterService.Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                binding.progressBar.setVisibility(View.GONE);
                Toast.makeText(AdminDashboardActivity.this, "Roster draft saved to Firestore", Toast.LENGTH_SHORT).show();
                loadCurrentMonthRosterStatus();
            }

            @Override
            public void onError(Exception e) {
                binding.progressBar.setVisibility(View.GONE);
                Toast.makeText(AdminDashboardActivity.this, "Failed to save draft: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void confirmPublishRoster() {
        if (activeRoster == null) {
            Toast.makeText(this, "No roster loaded to publish", Toast.LENGTH_SHORT).show();
            return;
        }

        int nextVersion = existingPublishedRoster != null ? existingPublishedRoster.getVersion() + 1 : 1;
        String monthName = Month.of(selectedMonth).getDisplayName(TextStyle.FULL, Locale.US);
        String versionMessage = existingPublishedRoster != null ?
                "An existing published version (v" + existingPublishedRoster.getVersion() + ") already exists.\n\nPublishing this corrected roster will release Version " + nextVersion + ".\nExisting user calendars will NOT be silently modified; users will receive the update option upon import." :
                "This will publish Version 1 of the official duty roster for " + monthName + " " + selectedYear + ".\nAll hospital clinical staff will be able to import their approved shifts.";

        new MaterialAlertDialogBuilder(this)
                .setTitle("Publish Official Roster?")
                .setMessage(versionMessage)
                .setPositiveButton("Publish Roster (v" + nextVersion + ")", (dialog, which) -> executePublish(nextVersion))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void executePublish(int targetVersion) {
        binding.progressBar.setVisibility(View.VISIBLE);
        binding.btnPublishRoster.setEnabled(false);
        binding.btnSaveDraft.setEnabled(false);

        centralRosterService.publishRoster(activeRoster, currentUid, currentEmail, new CentralRosterService.Callback<Integer>() {
            @Override
            public void onSuccess(Integer publishedVersion) {
                binding.progressBar.setVisibility(View.GONE);
                binding.btnPublishRoster.setEnabled(true);
                binding.btnSaveDraft.setEnabled(true);

                new MaterialAlertDialogBuilder(AdminDashboardActivity.this)
                        .setTitle("Roster Published Successfully!")
                        .setMessage("Version " + publishedVersion + " of the " + activeRoster.getMonthLabel() + " roster is now live.\n\n" +
                                "• Total days published: " + activeRoster.getDays().size() + "\n" +
                                "• Approved staff included: " + activeRoster.getStaffList().size() + "\n" +
                                "• Published by: " + currentEmail + "\n\n" +
                                "Staff members can now select their name and import their exact duties.")
                        .setPositiveButton("Done", (dialog, which) -> loadCurrentMonthRosterStatus())
                        .setCancelable(false)
                        .show();
            }

            @Override
            public void onError(Exception e) {
                binding.progressBar.setVisibility(View.GONE);
                binding.btnPublishRoster.setEnabled(true);
                binding.btnSaveDraft.setEnabled(true);

                new MaterialAlertDialogBuilder(AdminDashboardActivity.this)
                        .setTitle("Publication Failed")
                        .setMessage("Firestore rejected publication: " + e.getMessage() + "\n\nPlease ensure your user account is present in the Firestore '/admins' collection.")
                        .setPositiveButton("Dismiss", null)
                        .show();
            }
        });
    }

    private void showFormatHelpDialog() {
        String template = "Date, 8 AM - 2 PM, 2 PM - 8 PM, 8 PM - 8 AM, 9 AM - 5 PM, Off, Leave\n" +
                "01/10/2026, Munesh; Pradeep, Ajay Jangid; Yamini, Govind; Rohit, Vishal, Shiv, Ram\n" +
                "02/10/2026, Kuldeep; Hansraj, Naveen; Vikas, Munesh; Pradeep, Ajay Jindoliya, Yamini, -\n" +
                "03/10/2026, Govind; Rohit, Shiv; Vishal, Kuldeep; Ram, Naveen, Ajay Jangid, -";

        new MaterialAlertDialogBuilder(this)
                .setTitle("Hospital Roster Spreadsheet Format")
                .setMessage("Upload your hospital roster as a CSV or Excel (.xlsx) file with the following column structure:\n\n" +
                        "1. Date column: (e.g. '01/10/2026', '2026-10-01', or day numbers 1..31)\n" +
                        "2. Shift columns: Morning ('8 AM - 2 PM'), Evening ('2 PM - 8 PM'), Night ('8 PM - 8 AM'), General ('9 AM - 5 PM')\n" +
                        "3. Status columns: 'Off' and 'Leave' (kept strictly distinct)\n\n" +
                        "Multiple staff per cell can be separated by commas, semicolons, or slashes.\n\n" +
                        "Sample CSV Template:\n" + template)
                .setPositiveButton("Got It", null)
                .show();
    }

    private String getFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        result = cursor.getString(index);
                    }
                }
            } catch (Exception ignored) {}
        }
        if (result == null) {
            result = uri.getPath();
            if (result != null) {
                int cut = result.lastIndexOf('/');
                if (cut != -1) result = result.substring(cut + 1);
            }
        }
        return result != null ? result : "roster_upload.csv";
    }

    // --- Remote Access Control UI Logic ---

    private void setupAccessControlUI() {
        binding.btnSaveGlobalAccess.setOnClickListener(v -> confirmUpdateGlobalAccess());
        binding.btnCheckUserAccess.setOnClickListener(v -> lookupUserAccess());
        binding.btnRevokeUser.setOnClickListener(v -> confirmRevokeUserAccess());
        binding.btnRestoreUser.setOnClickListener(v -> confirmRestoreUserAccess());
    }

    private void loadGlobalAccessConfig() {
        accessControlRepository.fetchGlobalAccessConfig(new AccessControlRepository.ActionCallback<GlobalAccessConfig>() {
            @Override
            public void onSuccess(GlobalAccessConfig config) {
                if (config != null) {
                    binding.switchGlobalAccess.setChecked(config.isEnabled());
                    binding.etGlobalMessage.setText(config.getMessage() != null ? config.getMessage() : "");
                    updateGlobalStatusDisplay(config.isEnabled());
                }
            }

            @Override
            public void onError(Exception e) {
                Toast.makeText(AdminDashboardActivity.this, "Could not fetch global access config: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateGlobalStatusDisplay(boolean enabled) {
        if (enabled) {
            binding.viewGlobalStatusDot.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.status_synced));
            binding.tvGlobalStatusText.setText("Global App Access: Enabled");
        } else {
            binding.viewGlobalStatusDot.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.status_error));
            binding.tvGlobalStatusText.setText("Global App Access: Disabled (Maintenance)");
        }
    }

    private void confirmUpdateGlobalAccess() {
        boolean targetEnabled = binding.switchGlobalAccess.isChecked();
        String message = binding.etGlobalMessage.getText() != null ? binding.etGlobalMessage.getText().toString().trim() : "";

        String title = targetEnabled ? "Enable Global Access?" : "Disable Global Access (Maintenance Mode)?";
        String alertMsg = targetEnabled
                ? "This will allow all authorized users to connect to Cloud Firestore and sync duty rosters."
                : "This will place the application in Maintenance Mode for all users. Remote cloud mutations and roster publishing will be blocked until re-enabled.\n\nOffline calendar and local cached duties will remain accessible.";

        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(alertMsg)
                .setPositiveButton("Apply Status", (dialog, which) -> executeUpdateGlobalAccess(targetEnabled, message))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void executeUpdateGlobalAccess(boolean enabled, String message) {
        setAccessControlLoading(true);

        accessControlRepository.setGlobalAccess(enabled, message, currentUid, new AccessControlRepository.ActionCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                setAccessControlLoading(false);
                updateGlobalStatusDisplay(enabled);
                String toast = enabled ? "Global app access enabled successfully." : "Global app access disabled (Maintenance Mode).";
                Toast.makeText(AdminDashboardActivity.this, toast, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onError(Exception e) {
                setAccessControlLoading(false);
                Toast.makeText(AdminDashboardActivity.this, "Failed to update global access: " + e.getMessage(), Toast.LENGTH_LONG).show();
                loadGlobalAccessConfig(); // Rollback to actual server state
            }
        });
    }

    private void lookupUserAccess() {
        String targetUid = binding.etTargetUid.getText() != null ? binding.etTargetUid.getText().toString().trim() : "";
        if (targetUid.isEmpty()) {
            binding.tilTargetUid.setError("Please enter a valid Firebase User UID");
            return;
        }
        binding.tilTargetUid.setError(null);
        setAccessControlLoading(true);

        accessControlRepository.fetchUserAccess(targetUid, new AccessControlRepository.ActionCallback<UserAccessRecord>() {
            @Override
            public void onSuccess(UserAccessRecord record) {
                setAccessControlLoading(false);
                searchedUserRecord = record;
                displayUserAccessResult(record);
            }

            @Override
            public void onError(Exception e) {
                setAccessControlLoading(false);
                Toast.makeText(AdminDashboardActivity.this, "User lookup failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void displayUserAccessResult(UserAccessRecord record) {
        binding.layoutUserResult.setVisibility(View.VISIBLE);
        if (record.isEnabled()) {
            binding.tvUserStatusInfo.setText("User " + record.getUid() + ": Active (Allowed)");
            binding.tvUserStatusInfo.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            binding.tvUserReasonInfo.setText("Reason: " + (record.getReason().isEmpty() ? "No restrictions" : record.getReason()));
            binding.btnRevokeUser.setVisibility(View.VISIBLE);
            binding.btnRestoreUser.setVisibility(View.GONE);
        } else {
            binding.tvUserStatusInfo.setText("User " + record.getUid() + ": Revoked (Blocked)");
            binding.tvUserStatusInfo.setTextColor(ContextCompat.getColor(this, R.color.status_error));
            binding.tvUserReasonInfo.setText("Reason: " + (record.getReason().isEmpty() ? "Revoked by admin" : record.getReason()));
            binding.btnRevokeUser.setVisibility(View.GONE);
            binding.btnRestoreUser.setVisibility(View.VISIBLE);
        }
    }

    private void confirmRevokeUserAccess() {
        if (searchedUserRecord == null) return;
        final String targetUid = searchedUserRecord.getUid();

        final android.widget.EditText inputReason = new android.widget.EditText(this);
        inputReason.setHint("Reason for revocation (e.g. Inactive, Security review)");

        new MaterialAlertDialogBuilder(this)
                .setTitle("Revoke User Cloud Access")
                .setMessage("Are you sure you want to revoke remote cloud access for user:\n" + targetUid + "\n\nThey will be blocked from syncing cloud duties. Local calendar access remains available.")
                .setView(inputReason)
                .setPositiveButton("Revoke Access", (dialog, which) -> {
                    String reason = inputReason.getText() != null ? inputReason.getText().toString().trim() : "";
                    if (reason.isEmpty()) reason = "Access revoked by administrator";
                    executeUserAccessUpdate(targetUid, false, reason);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmRestoreUserAccess() {
        if (searchedUserRecord == null) return;
        final String targetUid = searchedUserRecord.getUid();

        new MaterialAlertDialogBuilder(this)
                .setTitle("Restore User Cloud Access")
                .setMessage("Restore remote cloud access for user:\n" + targetUid + "?\n\nThey will be re-authorized to synchronize duty rosters with Firestore.")
                .setPositiveButton("Restore Access", (dialog, which) -> {
                    executeUserAccessUpdate(targetUid, true, "Access restored by administrator");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void executeUserAccessUpdate(String targetUid, boolean enabled, String reason) {
        setAccessControlLoading(true);

        accessControlRepository.setUserAccess(targetUid, enabled, reason, currentUid, new AccessControlRepository.ActionCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                setAccessControlLoading(false);
                String toast = enabled ? "Access restored for user: " + targetUid : "Access revoked for user: " + targetUid;
                Toast.makeText(AdminDashboardActivity.this, toast, Toast.LENGTH_SHORT).show();
                // Refresh lookup record
                lookupUserAccess();
            }

            @Override
            public void onError(Exception e) {
                setAccessControlLoading(false);
                Toast.makeText(AdminDashboardActivity.this, "Failed to update user access: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void setAccessControlLoading(boolean loading) {
        binding.pbAccessAction.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.btnSaveGlobalAccess.setEnabled(!loading);
        binding.btnCheckUserAccess.setEnabled(!loading);
        binding.btnRevokeUser.setEnabled(!loading);
        binding.btnRestoreUser.setEnabled(!loading);
        binding.switchGlobalAccess.setEnabled(!loading);
    }
}
