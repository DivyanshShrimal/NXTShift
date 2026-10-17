package com.nxtshift.app.ui.importroster;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.nxtshift.app.R;
import com.nxtshift.app.data.local.NXTShiftDatabase;
import com.nxtshift.app.data.local.UserProfileEntity;
import com.nxtshift.app.data.model.MonthlyRoster;
import com.nxtshift.app.data.remote.CentralRosterService;
import com.nxtshift.app.data.repository.AuthRepository;
import com.nxtshift.app.data.repository.DutyRepository;
import com.nxtshift.app.databinding.ActivityPublishedRosterImportBinding;
import com.nxtshift.app.domain.Duty;
import com.nxtshift.app.notifications.ShiftNotificationManager;
import com.nxtshift.app.ocr.RosterTextParser;
import com.nxtshift.app.util.DateTimeUtils;

import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public class PublishedRosterImportActivity extends AppCompatActivity {
    private static final String TAG = "PublishedRosterImportActivity";

    private ActivityPublishedRosterImportBinding binding;
    private CentralRosterService centralRosterService;
    private DutyRepository dutyRepository;
    private AuthRepository authRepository;

    private String currentUid;
    private String userProfileDisplayName = "";

    private int selectedYear;
    private int selectedMonth; // 1-12

    private MonthlyRoster currentPublishedRoster;
    private String currentlySelectedStaffName = null;

    private PublishedStaffSelectionAdapter staffAdapter;
    private ExtractedShiftAdapter dutyAdapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPublishedRosterImportBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        authRepository = AuthRepository.getInstance(this);
        if (!authRepository.isUserSignedIn()) {
            Toast.makeText(this, "Please sign in to access published rosters.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        currentUid = authRepository.getCurrentUid();
        centralRosterService = new CentralRosterService();
        dutyRepository = DutyRepository.getInstance(this);

        LocalDate now = LocalDate.now();
        selectedYear = now.getYear();
        selectedMonth = now.getMonthValue();

        loadUserProfile();
        setupUI();
        fetchPublishedRoster();
    }

    private void loadUserProfile() {
        Executors.newSingleThreadExecutor().execute(() -> {
            if (currentUid != null) {
                UserProfileEntity profile = NXTShiftDatabase.getInstance(this).userProfileDao().getProfileDirect(currentUid);
                if (profile != null && profile.getDisplayName() != null) {
                    userProfileDisplayName = profile.getDisplayName();
                }
            }
        });
    }

    private void setupUI() {
        binding.btnBack.setOnClickListener(v -> {
            if (binding.layoutDutyReview.getVisibility() == View.VISIBLE) {
                showStaffSelectionView();
            } else {
                finish();
            }
        });

        binding.btnChangeMonth.setOnClickListener(v -> showMonthPicker());
        binding.btnRetryFetch.setOnClickListener(v -> fetchPublishedRoster());

        // Staff Selection Setup
        staffAdapter = new PublishedStaffSelectionAdapter(null, userProfileDisplayName, this::onStaffSelected);
        binding.rvPublishedStaff.setLayoutManager(new LinearLayoutManager(this));
        binding.rvPublishedStaff.setAdapter(staffAdapter);

        binding.etSearchStaff.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int count, int after) {
                staffAdapter.filter(s != null ? s.toString() : "");
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        // Duty Review Setup
        dutyAdapter = new ExtractedShiftAdapter((item, position) -> {
            // Optional preview edit
        });
        binding.rvReviewShifts.setLayoutManager(new LinearLayoutManager(this));
        binding.rvReviewShifts.setAdapter(dutyAdapter);

        binding.btnChangeStaff.setOnClickListener(v -> showStaffSelectionView());
        binding.btnSaveToCalendar.setOnClickListener(v -> saveDutiesToCalendar());

        updateMonthLabel();
    }

    private void updateMonthLabel() {
        String mName = Month.of(selectedMonth).getDisplayName(TextStyle.FULL, Locale.US);
        binding.tvCurrentMonthLabel.setText(mName + " " + selectedYear);
    }

    private void showMonthPicker() {
        final String[] months = {
                "January", "February", "March", "April", "May", "June",
                "July", "August", "September", "October", "November", "December"
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle("Select Roster Month")
                .setSingleChoiceItems(months, selectedMonth - 1, (dialog, which) -> {
                    selectedMonth = which + 1;
                    updateMonthLabel();
                    dialog.dismiss();
                    fetchPublishedRoster();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void fetchPublishedRoster() {
        binding.progressBar.setVisibility(View.VISIBLE);
        binding.layoutEmptyState.setVisibility(View.GONE);
        binding.layoutStaffSelection.setVisibility(View.GONE);
        binding.layoutDutyReview.setVisibility(View.GONE);
        binding.badgeVersion.setVisibility(View.GONE);

        centralRosterService.fetchPublishedRoster(selectedYear, selectedMonth, new CentralRosterService.Callback<MonthlyRoster>() {
            @Override
            public void onSuccess(MonthlyRoster roster) {
                binding.progressBar.setVisibility(View.GONE);
                currentPublishedRoster = roster;

                if (roster == null) {
                    // "Roster not updated yet. Please check again later."
                    binding.tvPublicationMeta.setText("No official roster available for " + binding.tvCurrentMonthLabel.getText());
                    binding.layoutEmptyState.setVisibility(View.VISIBLE);
                    binding.tvEmptyTitle.setText(R.string.roster_not_updated_yet);
                } else {
                    binding.layoutEmptyState.setVisibility(View.GONE);
                    binding.badgeVersion.setVisibility(View.VISIBLE);
                    binding.badgeVersion.setText("Version " + roster.getVersion());

                    String pubDate = roster.getPublishedAt() > 0 ?
                            DateTimeUtils.formatDisplayDate(DateTimeUtils.formatTimestampToIso(roster.getPublishedAt())) : "Recently";
                    binding.tvPublicationMeta.setText("Published on " + pubDate + " · " + roster.getDays().size() + " days · " + roster.getStaffList().size() + " staff");

                    showStaffSelectionView();
                }
            }

            @Override
            public void onError(Exception e) {
                binding.progressBar.setVisibility(View.GONE);
                binding.layoutEmptyState.setVisibility(View.VISIBLE);
                binding.tvEmptyTitle.setText("Connection Issue");
                binding.tvPublicationMeta.setText("Could not reach Firestore: " + e.getMessage());
            }
        });
    }

    private void showStaffSelectionView() {
        binding.layoutDutyReview.setVisibility(View.GONE);
        binding.layoutStaffSelection.setVisibility(View.VISIBLE);

        if (currentPublishedRoster != null) {
            staffAdapter.setRoster(currentPublishedRoster, userProfileDisplayName);
            binding.etSearchStaff.setText("");
        }
    }

    private void onStaffSelected(String staffName) {
        if (currentPublishedRoster == null) return;

        // Verify if staff name is present in published staff list
        boolean exists = false;
        for (String s : currentPublishedRoster.getStaffList()) {
            if (s.trim().equalsIgnoreCase(staffName.trim())) {
                exists = true;
                break;
            }
        }

        if (!exists) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Staff Identity Missing")
                    .setMessage(getString(R.string.staff_not_found))
                    .setPositiveButton("Select Another", null)
                    .show();
            return;
        }

        currentlySelectedStaffName = staffName;
        List<RosterTextParser.ExtractedShiftItem> shifts = currentPublishedRoster.extractDutiesForStaff(staffName);

        if (shifts.isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("No Duties Found")
                    .setMessage("There are no scheduled shifts, off, or leave recorded for " + staffName + " in the " + currentPublishedRoster.getMonthLabel() + " roster.")
                    .setPositiveButton("Back to Staff List", null)
                    .show();
            return;
        }

        showDutyReviewView(staffName, shifts);
    }

    private void showDutyReviewView(String staffName, List<RosterTextParser.ExtractedShiftItem> shifts) {
        binding.layoutStaffSelection.setVisibility(View.GONE);
        binding.layoutDutyReview.setVisibility(View.VISIBLE);

        binding.tvReviewHeading.setText("Review " + shifts.size() + " Extracted Shifts");
        binding.tvSelectedStaffSubtitle.setText("Extracting duties for " + staffName);

        dutyAdapter.setItems(shifts);
    }

    private void saveDutiesToCalendar() {
        List<RosterTextParser.ExtractedShiftItem> items = dutyAdapter.getItems();
        if (items.isEmpty() || currentUid == null) {
            Toast.makeText(this, "No shifts to import", Toast.LENGTH_SHORT).show();
            return;
        }

        List<Duty> duties = new ArrayList<>();
        String minDate = items.get(0).date;
        String maxDate = items.get(0).date;

        for (RosterTextParser.ExtractedShiftItem item : items) {
            Duty d = item.toDuty(currentUid);
            duties.add(d);
            if (item.date.compareTo(minDate) < 0) minDate = item.date;
            if (item.date.compareTo(maxDate) > 0) maxDate = item.date;
        }

        DutyRepository.ImportConflictPolicy policy = DutyRepository.ImportConflictPolicy.MERGE_REPLACE_CONFLICTS;
        if (binding.rbReplaceRange.isChecked()) {
            policy = DutyRepository.ImportConflictPolicy.REPLACE_RANGE;
        } else if (binding.rbKeepExisting.isChecked()) {
            policy = DutyRepository.ImportConflictPolicy.KEEP_EXISTING_ON_CONFLICT;
        }

        binding.btnSaveToCalendar.setEnabled(false);
        final String staffName = currentlySelectedStaffName != null ? currentlySelectedStaffName : "Staff";
        final String monthLabel = currentPublishedRoster != null ? currentPublishedRoster.getMonthLabel() : "Duty Roster";
        final int version = currentPublishedRoster != null ? currentPublishedRoster.getVersion() : 1;

        dutyRepository.importRosterDutiesDetailed(currentUid, duties, policy, minDate, maxDate, new DutyRepository.RepositoryCallback<DutyRepository.BulkImportResult>() {
            @Override
            public void onComplete(DutyRepository.BulkImportResult result) {
                ShiftNotificationManager.scheduleDailyDutyReminder(PublishedRosterImportActivity.this);
                ShiftNotificationManager.updatePersistentNextDutyNotification(PublishedRosterImportActivity.this);

                new MaterialAlertDialogBuilder(PublishedRosterImportActivity.this)
                        .setTitle("Calendar Updated Successfully")
                        .setMessage("Imported " + result.totalImported + " shifts for " + staffName + " from official published roster (" + monthLabel + " v" + version + ").\n\n" +
                                "• Newly scheduled duties: " + result.createdCount + "\n" +
                                "• Updated existing duties: " + result.updatedCount + "\n" +
                                "• Skipped unchanged duties: " + result.skippedCount + "\n\n" +
                                "Your personal calendar and shift reminders have been synchronized.")
                        .setPositiveButton("View Dashboard", (dialog, which) -> finish())
                        .setCancelable(false)
                        .show();
            }

            @Override
            public void onError(Exception e) {
                binding.btnSaveToCalendar.setEnabled(true);
                Toast.makeText(PublishedRosterImportActivity.this, "Import failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }
}
