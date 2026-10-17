package com.nxtshift.app.excel;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.nxtshift.app.R;
import com.nxtshift.app.data.local.NXTShiftDatabase;
import com.nxtshift.app.data.local.UserProfileEntity;
import com.nxtshift.app.data.repository.AuthRepository;
import com.nxtshift.app.data.repository.DutyRepository;
import com.nxtshift.app.databinding.ActivityExcelRosterImportBinding;
import com.nxtshift.app.domain.Duty;
import com.nxtshift.app.domain.ShiftType;
import com.nxtshift.app.notifications.ShiftNotificationManager;
import com.nxtshift.app.util.DateTimeUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

/**
 * Clean, MVP workflow for importing clinical duty rosters from Excel (.xlsx) workbooks.
 * Handles file picking via SAF, full table inspection, staff extraction, monthly schedule preview,
 * conflict resolution, and duplicate-safe Room database persistence.
 */
public class ExcelRosterImportActivity extends AppCompatActivity {
    private static final String TAG = "ExcelRosterImport";

    private ActivityExcelRosterImportBinding binding;
    private DutyRepository dutyRepository;
    private AuthRepository authRepository;

    private String currentUid;
    private String userProfileDisplayName = "";

    private ExcelRosterData currentRosterData;
    private String currentlySelectedStaffName;
    private ExcelStaffSchedule currentStaffSchedule;

    private ExcelStaffPickerAdapter staffAdapter;
    private ExcelScheduleAdapter scheduleAdapter;

    private enum Step {
        FILE_SELECT,
        ROSTER_DISPLAY,
        STAFF_SELECTION,
        SCHEDULE_PREVIEW
    }

    private Step currentStep = Step.FILE_SELECT;

    private final ActivityResultLauncher<String[]> openDocumentLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    processSelectedWorkbookUri(uri);
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityExcelRosterImportBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        authRepository = AuthRepository.getInstance(this);
        if (!authRepository.isUserSignedIn()) {
            Toast.makeText(this, "Please sign in to import rosters.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        currentUid = authRepository.getCurrentUid();
        dutyRepository = DutyRepository.getInstance(this);

        loadUserProfile();
        setupUI();
        updateStepView(Step.FILE_SELECT);
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
        // Back Button
        binding.btnBack.setOnClickListener(v -> handleBackNavigation());

        // Step 1: File Selection
        binding.btnSelectFile.setOnClickListener(v -> {
            openDocumentLauncher.launch(new String[]{
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-excel",
                    "application/octet-stream",
                    "*/*"
            });
        });

        // Step 2: Roster Display
        binding.btnImportDutyFromRoster.setOnClickListener(v -> {
            if (currentRosterData != null && currentRosterData.isValid()) {
                updateStepView(Step.STAFF_SELECTION);
            }
        });

        // Step 3: Staff Selection
        staffAdapter = new ExcelStaffPickerAdapter(this::onStaffMemberSelected);
        binding.rvStaffList.setLayoutManager(new LinearLayoutManager(this));
        binding.rvStaffList.setAdapter(staffAdapter);

        binding.etSearchStaff.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int count, int after) {
                staffAdapter.filter(s != null ? s.toString() : "");
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // Step 4: Schedule Preview
        scheduleAdapter = new ExcelScheduleAdapter(this::onScheduleItemClicked);
        binding.rvSchedulePreview.setLayoutManager(new LinearLayoutManager(this));
        binding.rvSchedulePreview.setAdapter(scheduleAdapter);

        binding.btnChangeStaff.setOnClickListener(v -> updateStepView(Step.STAFF_SELECTION));
        binding.btnConfirmAndAddToCalendar.setOnClickListener(v -> confirmAndAddToCalendar());
    }

    private void handleBackNavigation() {
        switch (currentStep) {
            case SCHEDULE_PREVIEW:
                updateStepView(Step.STAFF_SELECTION);
                break;
            case STAFF_SELECTION:
                updateStepView(Step.ROSTER_DISPLAY);
                break;
            case ROSTER_DISPLAY:
                updateStepView(Step.FILE_SELECT);
                break;
            case FILE_SELECT:
            default:
                finish();
                break;
        }
    }

    @Override
    public void onBackPressed() {
        if (currentStep != Step.FILE_SELECT) {
            handleBackNavigation();
        } else {
            super.onBackPressed();
        }
    }

    private void updateStepView(Step step) {
        this.currentStep = step;

        binding.viewFileSelection.setVisibility(step == Step.FILE_SELECT ? View.VISIBLE : View.GONE);
        binding.viewRosterDisplay.setVisibility(step == Step.ROSTER_DISPLAY ? View.VISIBLE : View.GONE);
        binding.viewStaffSelection.setVisibility(step == Step.STAFF_SELECTION ? View.VISIBLE : View.GONE);
        binding.viewSchedulePreview.setVisibility(step == Step.SCHEDULE_PREVIEW ? View.VISIBLE : View.GONE);

        switch (step) {
            case FILE_SELECT:
                binding.tvHeaderSubtitle.setText("Select Hospital Workbook (.xlsx)");
                binding.badgeStep.setText("Step 1");
                break;
            case ROSTER_DISPLAY:
                binding.tvHeaderSubtitle.setText("Inspect Hospital Roster Grid");
                binding.badgeStep.setText("Step 2");
                break;
            case STAFF_SELECTION:
                binding.tvHeaderSubtitle.setText("Select Staff Identity");
                binding.badgeStep.setText("Step 3");
                if (currentRosterData != null) {
                    staffAdapter.setStaffList(currentRosterData.getUniqueStaffNames(), userProfileDisplayName);
                    binding.tvStaffSelectionSubtitle.setText("Choose your name from " + currentRosterData.getUniqueStaffNames().size() + " extracted hospital staff members");
                }
                break;
            case SCHEDULE_PREVIEW:
                binding.tvHeaderSubtitle.setText("Review & Confirm Monthly Schedule");
                binding.badgeStep.setText("Step 4");
                break;
        }
    }

    // --- Step 1: Processing Selected Workbook ---

    private void processSelectedWorkbookUri(Uri uri) {
        String fileName = queryFileName(uri);
        binding.tvSelectedFileName.setText(fileName);
        binding.cardSelectedFileInfo.setVisibility(View.VISIBLE);
        binding.tvFileError.setVisibility(View.GONE);

        showLoading(true, "Parsing " + fileName + "...");

        Executors.newSingleThreadExecutor().execute(() -> {
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is == null) {
                    throw new IllegalArgumentException("Unable to open stream for selected file");
                }

                ExcelRosterData result = ExcelRosterParser.parse(is, fileName);

                runOnUiThread(() -> {
                    showLoading(false, "");
                    if (result.isValid()) {
                        this.currentRosterData = result;
                        populateRosterGrid(result);
                        updateStepView(Step.ROSTER_DISPLAY);
                    } else {
                        String errMsg = result.getErrors().isEmpty() ? "Invalid or unsupported roster file." : String.join("\n• ", result.getErrors());
                        binding.tvFileError.setVisibility(View.VISIBLE);
                        binding.tvFileError.setText("Error reading workbook:\n• " + errMsg);
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    showLoading(false, "");
                    binding.tvFileError.setVisibility(View.VISIBLE);
                    binding.tvFileError.setText("Failed to parse Excel file: " + e.getMessage() + "\nPlease ensure the workbook contains the 'Roster Oct 2026' column structure.");
                });
            }
        });
    }

    private String queryFileName(Uri uri) {
        String result = "hospital_roster.xlsx";
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex >= 0) {
                        result = cursor.getString(nameIndex);
                    }
                }
            } catch (Exception ignored) {}
        } else if (uri.getPath() != null) {
            String path = uri.getPath();
            int cut = path.lastIndexOf('/');
            if (cut != -1) {
                result = path.substring(cut + 1);
            }
        }
        return result;
    }

    // --- Step 2: Populating Roster Grid ---

    private void populateRosterGrid(ExcelRosterData roster) {
        binding.tvRosterMonthYear.setText(roster.getMonthLabel());
        binding.badgeSheetName.setText("Sheet: " + roster.getSheetName());
        binding.tvRosterStats.setText(roster.getDays().size() + " calendar days · " + roster.getColumns().size() + " columns · " + roster.getUniqueStaffNames().size() + " staff members");

        binding.tableRosterGrid.removeAllViews();

        int pad = dpToPx(8);
        int cellWidth = dpToPx(130);

        // 1. Header Row
        TableRow headerRow = new TableRow(this);
        headerRow.setBackgroundColor(ContextCompat.getColor(this, R.color.brand_primary));

        // Date Column Header
        TextView tvDateHdr = createTableCell("Date", dpToPx(80), pad, true);
        tvDateHdr.setTextColor(ContextCompat.getColor(this, R.color.text_on_dark));
        headerRow.addView(tvDateHdr);

        // Shift Headers
        for (ExcelShiftColumn col : roster.getColumns()) {
            TextView tvCol = createTableCell(col.getHeaderName(), cellWidth, pad, true);
            tvCol.setTextColor(ContextCompat.getColor(this, R.color.text_on_dark));
            headerRow.addView(tvCol);
        }
        binding.tableRosterGrid.addView(headerRow);

        // 2. Data Rows
        int rowIndex = 0;
        for (ExcelDayRow day : roster.getDays()) {
            TableRow row = new TableRow(this);
            int bgColor = (rowIndex % 2 == 0) ? ContextCompat.getColor(this, R.color.bg_card) : ContextCompat.getColor(this, R.color.bg_light);
            row.setBackgroundColor(bgColor);

            // Date cell
            String displayDate = DateTimeUtils.formatDisplayDate(day.getIsoDate());
            TextView tvDate = createTableCell(displayDate, dpToPx(80), pad, false);
            tvDate.setTextColor(ContextCompat.getColor(this, R.color.brand_primary));
            tvDate.setTypeface(null, Typeface.BOLD);
            row.addView(tvDate);

            // Shift cells
            for (ExcelShiftColumn col : roster.getColumns()) {
                List<String> staff = day.getStaffForColumn(col.getColumnIndex());
                String cellText = staff.isEmpty() ? "—" : String.join(", ", staff);
                TextView tvCell = createTableCell(cellText, cellWidth, pad, false);

                if (col.isOff() && !staff.isEmpty()) {
                    tvCell.setTextColor(ContextCompat.getColor(this, R.color.shift_off_text));
                } else if (col.isLeave() && !staff.isEmpty()) {
                    tvCell.setTextColor(ContextCompat.getColor(this, R.color.shift_off_text));
                } else if (!staff.isEmpty()) {
                    tvCell.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
                } else {
                    tvCell.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                }

                row.addView(tvCell);
            }

            binding.tableRosterGrid.addView(row);
            rowIndex++;
        }
    }

    private TextView createTableCell(String text, int minWidth, int padding, boolean isHeader) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setMinimumWidth(minWidth);
        tv.setPadding(padding, padding, padding, padding);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setTextSize(isHeader ? 12 : 11);
        if (isHeader) {
            tv.setTypeface(null, Typeface.BOLD);
        }
        return tv;
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    // --- Step 3: Staff Selection ---

    private void onStaffMemberSelected(String staffName) {
        if (currentRosterData == null) return;

        this.currentlySelectedStaffName = staffName;
        this.currentStaffSchedule = currentRosterData.extractScheduleForStaff(staffName);

        populateSchedulePreview(currentStaffSchedule);
        updateStepView(Step.SCHEDULE_PREVIEW);
    }

    // --- Step 4: Schedule Preview & Conflict Resolution ---

    private void populateSchedulePreview(ExcelStaffSchedule schedule) {
        binding.tvPreviewNurseName.setText(schedule.getStaffName());

        binding.badgeDutyCount.setText(schedule.getDutyCount() + " Duties");
        binding.badgeOffCount.setText(schedule.getOffCount() + " Off");
        binding.badgeLeaveCount.setText(schedule.getLeaveCount() + " Leave");

        if (schedule.getConflictCount() > 0) {
            binding.badgeConflictCount.setVisibility(View.VISIBLE);
            binding.badgeConflictCount.setText(schedule.getConflictCount() + " Conflict" + (schedule.getConflictCount() > 1 ? "s" : ""));
        } else {
            binding.badgeConflictCount.setVisibility(View.GONE);
        }

        scheduleAdapter.setItems(schedule.getItems());
    }

    private void onScheduleItemClicked(ExcelStaffScheduleItem item, int position) {
        if (!item.hasConflict()) {
            return;
        }

        // Show conflict resolution modal
        List<String> options = new ArrayList<>();
        for (String c : item.getConflictingAssignments()) {
            options.add("Keep: " + c);
        }
        options.add("Skip this date (Do not import)");

        String[] optionsArray = options.toArray(new String[0]);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Resolve Conflict: " + DateTimeUtils.formatDisplayDate(item.getDate()))
                .setMessage("Multiple assignments found:\n• " + String.join("\n• ", item.getConflictingAssignments()) + "\n\nSelect which assignment to apply for your calendar:")
                .setItems(optionsArray, (dialog, which) -> {
                    if (which < item.getConflictingAssignments().size()) {
                        String chosen = item.getConflictingAssignments().get(which);
                        resolveItemWithChoice(item, chosen);
                    } else {
                        // Skip date
                        item.setSkipped(true);
                        item.setStatus(ExcelStaffScheduleItem.StatusType.UNASSIGNED);
                        item.setHasConflict(false);
                        item.getValidationWarnings().clear();
                        item.addWarning("Date skipped by user resolution");
                    }
                    currentStaffSchedule.recomputeCounts();
                    populateSchedulePreview(currentStaffSchedule);
                    Toast.makeText(this, "Resolved " + item.getDate(), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void resolveItemWithChoice(ExcelStaffScheduleItem item, String chosen) {
        item.setHasConflict(false);
        item.getValidationWarnings().clear();

        if (chosen.equalsIgnoreCase("Weekly Off")) {
            item.setStatus(ExcelStaffScheduleItem.StatusType.OFF);
            item.setShiftType(ShiftType.OFF);
            item.setShiftName("Weekly Off");
            item.setColleagues("");
        } else if (chosen.equalsIgnoreCase("On Leave")) {
            item.setStatus(ExcelStaffScheduleItem.StatusType.LEAVE);
            item.setShiftType(ShiftType.LEAVE);
            item.setShiftName("On Leave");
            item.setColleagues("");
        } else {
            item.setStatus(ExcelStaffScheduleItem.StatusType.DUTY);
            // Match with columns in current roster data
            for (ExcelShiftColumn col : currentRosterData.getColumns()) {
                if (col.getHeaderName().equalsIgnoreCase(chosen)) {
                    item.setShiftType(col.getShiftType());
                    item.setShiftName(col.getHeaderName());
                    item.setStartTime(col.getStartTime());
                    item.setEndTime(col.getEndTime());
                    item.setOvernight(col.isOvernight());
                    break;
                }
            }
        }
    }

    // --- Step 5: Confirm & Add to Calendar ---

    private void confirmAndAddToCalendar() {
        if (currentStaffSchedule == null || currentUid == null) {
            Toast.makeText(this, "No valid schedule loaded", Toast.LENGTH_SHORT).show();
            return;
        }

        // Check if any unresolved conflicts remain
        if (currentStaffSchedule.getConflictCount() > 0) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Unresolved Conflicts Found")
                    .setMessage("There are " + currentStaffSchedule.getConflictCount() + " unresolved conflicting entries. These dates will be safely skipped.\n\nProceed with importing the remaining " + (currentStaffSchedule.getDutyCount() + currentStaffSchedule.getOffCount() + currentStaffSchedule.getLeaveCount()) + " valid duties?")
                    .setPositiveButton("Proceed & Import", (dialog, which) -> executeCalendarImport())
                    .setNegativeButton("Review Conflicts", null)
                    .show();
            return;
        }

        executeCalendarImport();
    }

    private void executeCalendarImport() {
        List<Duty> duties = currentStaffSchedule.getImportableDuties(currentUid);
        if (duties.isEmpty()) {
            Toast.makeText(this, "No valid duties to import", Toast.LENGTH_SHORT).show();
            return;
        }

        String minDate = duties.get(0).getDutyDate();
        String maxDate = duties.get(0).getDutyDate();
        for (Duty d : duties) {
            if (d.getDutyDate().compareTo(minDate) < 0) minDate = d.getDutyDate();
            if (d.getDutyDate().compareTo(maxDate) > 0) maxDate = d.getDutyDate();
        }

        DutyRepository.ImportConflictPolicy policy = DutyRepository.ImportConflictPolicy.MERGE_REPLACE_CONFLICTS;
        if (binding.rbReplaceRange.isChecked()) {
            policy = DutyRepository.ImportConflictPolicy.REPLACE_RANGE;
        } else if (binding.rbKeep.isChecked()) {
            policy = DutyRepository.ImportConflictPolicy.KEEP_EXISTING_ON_CONFLICT;
        }

        binding.btnConfirmAndAddToCalendar.setEnabled(false);
        showLoading(true, "Saving duties to local calendar and syncing...");

        final String staffName = currentlySelectedStaffName != null ? currentlySelectedStaffName : "Staff";
        final String monthLabel = currentStaffSchedule.getMonthLabel();

        dutyRepository.importRosterDutiesDetailed(currentUid, duties, policy, minDate, maxDate, new DutyRepository.RepositoryCallback<DutyRepository.BulkImportResult>() {
            @Override
            public void onComplete(DutyRepository.BulkImportResult result) {
                showLoading(false, "");
                ShiftNotificationManager.scheduleDailyDutyReminder(ExcelRosterImportActivity.this);
                ShiftNotificationManager.updatePersistentNextDutyNotification(ExcelRosterImportActivity.this);

                new MaterialAlertDialogBuilder(ExcelRosterImportActivity.this)
                        .setTitle("Calendar Updated Successfully")
                        .setMessage("Imported " + result.totalImported + " duties for " + staffName + " from Excel Roster (" + monthLabel + ").\n\n" +
                                "• Newly scheduled shifts: " + result.createdCount + "\n" +
                                "• Updated existing shifts: " + result.updatedCount + "\n" +
                                "• Skipped existing records: " + result.skippedCount + "\n\n" +
                                "Your local personal calendar and shift reminders are up to date.")
                        .setPositiveButton("View Dashboard", (dialog, which) -> finish())
                        .setCancelable(false)
                        .show();
            }

            @Override
            public void onError(Exception e) {
                showLoading(false, "");
                binding.btnConfirmAndAddToCalendar.setEnabled(true);
                Toast.makeText(ExcelRosterImportActivity.this, "Calendar import error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showLoading(boolean show, String message) {
        binding.layoutLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        if (message != null) {
            binding.tvLoadingMessage.setText(message);
        }
    }
}
