package com.nxtshift.app.ui.importroster;

import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.mlkit.vision.text.Text;
import com.nxtshift.app.R;
import com.nxtshift.app.data.local.NXTShiftDatabase;
import com.nxtshift.app.data.local.StaffMemberEntity;
import com.nxtshift.app.data.local.UserProfileEntity;
import com.nxtshift.app.data.repository.AuthRepository;
import com.nxtshift.app.data.repository.DutyRepository;
import com.nxtshift.app.data.repository.StaffRepository;
import com.nxtshift.app.databinding.ActivityRosterImportBinding;
import com.nxtshift.app.domain.Duty;
import com.nxtshift.app.domain.ShiftType;
import com.nxtshift.app.notifications.ShiftNotificationManager;
import com.nxtshift.app.ocr.FullMonthRosterParser;
import com.nxtshift.app.ocr.RosterOcrProcessor;
import com.nxtshift.app.ocr.RosterTextParser;
import com.nxtshift.app.util.DateTimeUtils;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import android.util.Log;

public class RosterImportActivity extends AppCompatActivity {
    private static final String TAG = "RosterImportActivity";

    private ActivityRosterImportBinding binding;
    private RosterOcrProcessor ocrProcessor;
    private DutyRepository dutyRepository;
    private StaffRepository staffRepository;
    private NXTShiftDatabase database;
    private String currentUid;

    private ExtractedShiftAdapter dutyAdapter;
    private StaffDirectoryReviewAdapter staffReviewAdapter;

    private Bitmap currentBitmap;
    private int currentRotationDegrees = 0;

    // OCR Results Cache
    private Text lastVisionText;
    private String lastFullText;
    private FullMonthRosterParser.MonthYearResult detectedMonthYear;
    private final List<FullMonthRosterParser.DiscoveredStaffMember> discoveredStaffList = new ArrayList<>();
    private FullMonthRosterParser.DiscoveredStaffMember currentlySelectedStaff;
    private String userProfileDisplayName = "";

    private final ActivityResultLauncher<PickVisualMediaRequest> pickMediaLauncher =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    handleSelectedImage(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityRosterImportBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ocrProcessor = new RosterOcrProcessor();
        dutyRepository = DutyRepository.getInstance(this);
        staffRepository = StaffRepository.getInstance(this);
        database = NXTShiftDatabase.getInstance(this);
        currentUid = AuthRepository.getInstance(this).getCurrentUid();

        loadUserProfile();
        setupUI();
    }

    private void loadUserProfile() {
        Executors.newSingleThreadExecutor().execute(() -> {
            if (currentUid != null) {
                UserProfileEntity profile = database.userProfileDao().getProfileDirect(currentUid);
                if (profile != null && profile.getDisplayName() != null) {
                    userProfileDisplayName = profile.getDisplayName();
                }
            }
        });
    }

    private void setupUI() {
        binding.btnBack.setOnClickListener(v -> finish());

        // Duty Adapter
        dutyAdapter = new ExtractedShiftAdapter((item, position) -> showEditShiftDialog(item, position));
        binding.rvExtractedShifts.setLayoutManager(new LinearLayoutManager(this));
        binding.rvExtractedShifts.setAdapter(dutyAdapter);

        // Staff Review Adapter
        staffReviewAdapter = new StaffDirectoryReviewAdapter(discoveredStaffList, userProfileDisplayName, new StaffDirectoryReviewAdapter.StaffActionListener() {
            @Override
            public void onStaffSelected(FullMonthRosterParser.DiscoveredStaffMember staff) {
                currentlySelectedStaff = staff;
                updateSelectedEmployeeIndicator();
            }

            @Override
            public void onEditStaff(FullMonthRosterParser.DiscoveredStaffMember staff, int position) {
                showEditStaffNameDialog(staff, position);
            }

            @Override
            public void onSplitStaff(FullMonthRosterParser.DiscoveredStaffMember staff, int position) {
                showSplitStaffDialog(staff, position);
            }

            @Override
            public void onMergeStaff(FullMonthRosterParser.DiscoveredStaffMember staff, int position) {
                showMergeStaffDialog(staff, position);
            }

            @Override
            public void onDeleteStaff(FullMonthRosterParser.DiscoveredStaffMember staff, int position) {
                confirmDeleteStaff(staff, position);
            }
        });

        binding.rvStaffDirectoryReview.setLayoutManager(new LinearLayoutManager(this));
        binding.rvStaffDirectoryReview.setAdapter(staffReviewAdapter);

        // Photo Picking
        binding.cardSelectPhoto.setOnClickListener(v -> launchPhotoPicker());
        binding.btnSelectPhoto.setOnClickListener(v -> launchPhotoPicker());
        binding.btnRotate.setOnClickListener(v -> rotateImage());

        // Step 1: Staff Directory Actions
        binding.btnAddStaffMember.setOnClickListener(v -> showAddStaffDialog());
        binding.etSearchStaffDirectory.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                staffReviewAdapter.filter(s != null ? s.toString() : "");
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        binding.btnConfirmStaffAndExtract.setOnClickListener(v -> confirmStaffAndExtractDuties());

        // Step 2: Duty Review Actions
        binding.btnChangeEmployee.setOnClickListener(v -> returnToStaffReview());
        binding.btnSaveShifts.setOnClickListener(v -> saveImportedShifts());
    }

    private void launchPhotoPicker() {
        pickMediaLauncher.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
    }

    private void handleSelectedImage(Uri uri) {
        try {
            currentBitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), uri);
            binding.ivPreview.setImageBitmap(currentBitmap);
            binding.btnRotate.setVisibility(View.VISIBLE);
            binding.btnSelectPhoto.setText("Choose Different Photo");

            runOcr();
        } catch (IOException e) {
            Toast.makeText(this, "Error loading image: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void rotateImage() {
        if (currentBitmap == null) return;
        Matrix matrix = new Matrix();
        matrix.postRotate(90);
        currentRotationDegrees = (currentRotationDegrees + 90) % 360;
        currentBitmap = Bitmap.createBitmap(currentBitmap, 0, 0, currentBitmap.getWidth(), currentBitmap.getHeight(), matrix, true);
        binding.ivPreview.setImageBitmap(currentBitmap);

        runOcr();
    }

    private void runOcr() {
        if (currentBitmap == null) return;

        binding.layoutOcrProgress.setVisibility(View.VISIBLE);
        binding.layoutStaffReviewContainer.setVisibility(View.GONE);
        binding.layoutReviewContainer.setVisibility(View.GONE);
        binding.layoutSaveFooter.setVisibility(View.GONE);

        ocrProcessor.processBitmapFull(currentBitmap, 0, new RosterOcrProcessor.FullOcrCallback() {
            @Override
            public void onSuccess(Text visionText, String fullExtractedText) {
                binding.layoutOcrProgress.setVisibility(View.GONE);

                if (fullExtractedText == null || fullExtractedText.trim().isEmpty()) {
                    Toast.makeText(RosterImportActivity.this, R.string.ocr_no_shifts_found, Toast.LENGTH_LONG).show();
                    return;
                }

                lastVisionText = visionText;
                lastFullText = fullExtractedText;

                // 1. Detect Month and Year from headers
                detectedMonthYear = FullMonthRosterParser.detectMonthYear(fullExtractedText);

                // 2. Discover Staff Directory automatically
                discoveredStaffList.clear();
                discoveredStaffList.addAll(FullMonthRosterParser.discoverStaffDirectory(visionText, fullExtractedText));

                // 3. Display Step 1: Staff Directory Review
                binding.layoutStaffReviewContainer.setVisibility(View.VISIBLE);
                staffReviewAdapter.setItems(discoveredStaffList);

                currentlySelectedStaff = staffReviewAdapter.getSelectedStaff();
                updateSelectedEmployeeIndicator();

                if (discoveredStaffList.isEmpty()) {
                    Toast.makeText(RosterImportActivity.this, "No staff names auto-detected. Use '+ Add' to manually add names.", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(RosterImportActivity.this, "Found " + discoveredStaffList.size() + " staff members. Review before extraction.", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(Exception e) {
                binding.layoutOcrProgress.setVisibility(View.GONE);
                Toast.makeText(RosterImportActivity.this, "OCR error: " + e.getLocalizedMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void updateSelectedEmployeeIndicator() {
        if (currentlySelectedStaff != null) {
            binding.tvSelectedEmployeeIndicator.setText("Extracting duties for: " + currentlySelectedStaff.name + " (" + currentlySelectedStaff.dutyCount + " shifts found)");
            binding.btnConfirmStaffAndExtract.setEnabled(true);
        } else {
            binding.tvSelectedEmployeeIndicator.setText("No employee selected. Choose who you are from the list.");
            binding.btnConfirmStaffAndExtract.setEnabled(false);
        }
    }

    // ── Staff Directory Review & Corrections ───────────────────────────────────

    private void showEditStaffNameDialog(FullMonthRosterParser.DiscoveredStaffMember staff, int position) {
        final TextInputEditText input = new TextInputEditText(this);
        input.setText(staff.name);
        input.setSelection(staff.name.length());

        new MaterialAlertDialogBuilder(this)
                .setTitle("Edit Staff Name")
                .setMessage("Correct any OCR spelling mistakes:")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String newName = input.getText() != null ? input.getText().toString().trim() : "";
                    if (!newName.isEmpty()) {
                        staff.updateName(newName);
                        staffReviewAdapter.notifyItemChanged(position);
                        updateSelectedEmployeeIndicator();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showSplitStaffDialog(FullMonthRosterParser.DiscoveredStaffMember staff, int position) {
        View view = getLayoutInflater().inflate(R.layout.dialog_select_staff, null);
        // Prompt for Name 1 and Name 2
        String[] words = staff.name.split("\\s+");
        String def1 = words.length > 0 ? words[0] : staff.name;
        String def2 = words.length > 1 ? staff.name.substring(def1.length()).trim() : "";

        final EditText et1 = new EditText(this);
        et1.setHint("Name 1 (e.g. " + def1 + ")");
        et1.setText(def1);

        final EditText et2 = new EditText(this);
        et2.setHint("Name 2 (e.g. " + def2 + ")");
        et2.setText(def2);

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 10);
        layout.addView(et1);
        layout.addView(et2);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Split Combined Staff Entry")
                .setMessage("Split '" + staff.name + "' into two separate staff members:")
                .setView(layout)
                .setPositiveButton("Split", (d, w) -> {
                    String n1 = et1.getText().toString().trim();
                    String n2 = et2.getText().toString().trim();
                    if (!n1.isEmpty() && !n2.isEmpty()) {
                        List<FullMonthRosterParser.DiscoveredStaffMember> split =
                                FullMonthRosterParser.splitStaffMember(staff, n1, n2);
                        discoveredStaffList.remove(staff);
                        discoveredStaffList.addAll(split);
                        staffReviewAdapter.setItems(discoveredStaffList);
                        currentlySelectedStaff = staffReviewAdapter.getSelectedStaff();
                        updateSelectedEmployeeIndicator();
                        Toast.makeText(this, "Split into " + n1 + " and " + n2, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showMergeStaffDialog(FullMonthRosterParser.DiscoveredStaffMember staff, int position) {
        List<FullMonthRosterParser.DiscoveredStaffMember> others = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (FullMonthRosterParser.DiscoveredStaffMember s : staffReviewAdapter.getAllStaff()) {
            if (!s.normalizedName.equals(staff.normalizedName)) {
                others.add(s);
                names.add(s.name + " (" + s.dutyCount + " shifts)");
            }
        }

        if (others.isEmpty()) {
            Toast.makeText(this, "No other staff members to merge with", Toast.LENGTH_SHORT).show();
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("Combine '" + staff.name + "' With...")
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    FullMonthRosterParser.DiscoveredStaffMember target = others.get(which);
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("Confirm Staff Merge")
                            .setMessage("Are you sure '" + staff.name + "' and '" + target.name + "' are the exact same person?\n\nTheir shift counts will be combined into '" + target.name + "'.")
                            .setPositiveButton("Merge", (d2, w2) -> {
                                target.mergeWith(staff);
                                discoveredStaffList.remove(staff);
                                staffReviewAdapter.setItems(discoveredStaffList);
                                currentlySelectedStaff = staffReviewAdapter.getSelectedStaff();
                                updateSelectedEmployeeIndicator();
                                Toast.makeText(this, "Combined into " + target.name, Toast.LENGTH_SHORT).show();
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDeleteStaff(FullMonthRosterParser.DiscoveredStaffMember staff, int position) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Remove Non-Person Entry")
                .setMessage("Remove '" + staff.name + "' from the staff directory? (Use this for header text, dates, or non-person text)")
                .setPositiveButton("Remove", (d, w) -> {
                    discoveredStaffList.remove(staff);
                    staffReviewAdapter.setItems(discoveredStaffList);
                    currentlySelectedStaff = staffReviewAdapter.getSelectedStaff();
                    updateSelectedEmployeeIndicator();
                    Toast.makeText(this, "Removed '" + staff.name + "'", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAddStaffDialog() {
        final TextInputEditText input = new TextInputEditText(this);
        input.setHint("Staff Name (e.g. Yamini)");

        new MaterialAlertDialogBuilder(this)
                .setTitle("Add Missing Staff Member")
                .setMessage("Enter the staff name to include in directory and extract duties for:")
                .setView(input)
                .setPositiveButton("Add", (d, w) -> {
                    String name = input.getText() != null ? input.getText().toString().trim() : "";
                    if (!name.isEmpty() && FullMonthRosterParser.isValidStaffName(name)) {
                        FullMonthRosterParser.DiscoveredStaffMember member =
                                new FullMonthRosterParser.DiscoveredStaffMember(FullMonthRosterParser.cleanName(name), FullMonthRosterParser.normalizeForComparison(name), 1);
                        discoveredStaffList.add(member);
                        staffReviewAdapter.setItems(discoveredStaffList);
                        staffReviewAdapter.setSelectedStaff(member.normalizedName);
                        currentlySelectedStaff = member;
                        updateSelectedEmployeeIndicator();
                    } else {
                        Toast.makeText(this, "Please enter a valid staff name", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Step 2: Duty Extraction & Review ────────────────────────────────────────

    private void confirmStaffAndExtractDuties() {
        currentlySelectedStaff = staffReviewAdapter.getSelectedStaff();
        if (currentlySelectedStaff == null) {
            Toast.makeText(this, "Please select which employee roster you want to import", Toast.LENGTH_LONG).show();
            return;
        }

        // 1. Save ONLY confirmed staff entries to Room & Firestore
        saveConfirmedStaffDirectory(staffReviewAdapter.getAllStaff());

        // 2. Transition from Step 1 to Step 2
        binding.layoutStaffReviewContainer.setVisibility(View.GONE);
        binding.layoutReviewContainer.setVisibility(View.VISIBLE);
        binding.layoutSaveFooter.setVisibility(View.VISIBLE);

        binding.tvSelectedEmployee.setText("Roster for: " + currentlySelectedStaff.name);
        binding.tvRosterMonthYear.setText(detectedMonthYear != null ? detectedMonthYear.getDisplayLabel() : "");

        int year = detectedMonthYear != null ? detectedMonthYear.year : LocalDate.now().getYear();
        int month = detectedMonthYear != null ? detectedMonthYear.month : LocalDate.now().getMonthValue();

        List<RosterTextParser.ExtractedShiftItem> shifts =
                FullMonthRosterParser.extractDutiesForEmployee(lastVisionText, lastFullText, currentlySelectedStaff.name, year, month);

        Log.i(TAG, "=== Diagnostic Import Report ===");
        Log.i(TAG, "Total OCR TextBlocks: " + (lastVisionText != null ? lastVisionText.getTextBlocks().size() : 0));
        Log.i(TAG, "Total staff occurrences for " + currentlySelectedStaff.name + ": " + currentlySelectedStaff.dutyCount);
        Log.i(TAG, "Number of records matching " + currentlySelectedStaff.name + ": " + shifts.size());
        Log.i(TAG, "Number of records shown on the review screen: " + shifts.size());

        if (shifts.isEmpty()) {
            dutyAdapter.setItems(new ArrayList<>());
            binding.tvReviewHeading.setText("No Duties Found for " + currentlySelectedStaff.name);
            new MaterialAlertDialogBuilder(this)
                    .setTitle("No Duties Found")
                    .setMessage("Could not find duty rows for '" + currentlySelectedStaff.name + "' in this roster layout.\n\nYou can switch to another staff member or check if the table orientation is correct.")
                    .setPositiveButton("Switch Staff", (d, w) -> returnToStaffReview())
                    .setNegativeButton("Dismiss", null)
                    .show();
            return;
        }

        binding.tvReviewHeading.setText("Review " + shifts.size() + " Extracted Shifts");
        dutyAdapter.setItems(shifts);
        Toast.makeText(this, "Loaded " + shifts.size() + " shifts for " + currentlySelectedStaff.name, Toast.LENGTH_SHORT).show();
    }

    private void returnToStaffReview() {
        binding.layoutReviewContainer.setVisibility(View.GONE);
        binding.layoutSaveFooter.setVisibility(View.GONE);
        binding.layoutStaffReviewContainer.setVisibility(View.VISIBLE);
        staffReviewAdapter.setItems(discoveredStaffList);
        updateSelectedEmployeeIndicator();
    }

    private void saveConfirmedStaffDirectory(List<FullMonthRosterParser.DiscoveredStaffMember> confirmed) {
        if (confirmed == null || confirmed.isEmpty() || currentUid == null) return;

        List<StaffMemberEntity> entities = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (FullMonthRosterParser.DiscoveredStaffMember item : confirmed) {
            String aliases = String.join(", ", item.rawVariants);
            StaffMemberEntity entity = new StaffMemberEntity(
                    currentUid,
                    item.name,
                    item.normalizedName,
                    null,
                    aliases,
                    item.dutyCount,
                    now,
                    now
            );
            entities.add(entity);
        }

        staffRepository.saveOrMergeDiscoveredStaff(currentUid, entities, new StaffRepository.StaffCallback<Integer>() {
            @Override
            public void onComplete(Integer result) {}

            @Override
            public void onError(Exception e) {}
        });
    }

    private void showEditShiftDialog(RosterTextParser.ExtractedShiftItem item, int position) {
        String[] shiftTypes = {"Morning (08:00–14:00)", "Evening (14:00–20:00)", "Night (20:00–08:00 +1d)", "General (09:00–17:00)", "Off / Leave"};
        int selectedIndex = 0;
        if (item.shiftType == ShiftType.EVENING) selectedIndex = 1;
        else if (item.shiftType == ShiftType.NIGHT) selectedIndex = 2;
        else if (item.shiftType == ShiftType.GENERAL) selectedIndex = 3;
        else if (item.shiftType == ShiftType.OFF || item.shiftType == ShiftType.LEAVE) selectedIndex = 4;

        final int[] chosenIndex = {selectedIndex};

        new MaterialAlertDialogBuilder(this)
                .setTitle("Edit Duty: " + DateTimeUtils.formatDisplayDate(item.date))
                .setSingleChoiceItems(shiftTypes, selectedIndex, (dialog, which) -> chosenIndex[0] = which)
                .setPositiveButton("Update", (dialog, which) -> {
                    switch (chosenIndex[0]) {
                        case 0:
                            item.shiftType = ShiftType.MORNING;
                            item.startTime = "08:00 AM";
                            item.endTime = "02:00 PM";
                            item.isOvernight = false;
                            break;
                        case 1:
                            item.shiftType = ShiftType.EVENING;
                            item.startTime = "02:00 PM";
                            item.endTime = "08:00 PM";
                            item.isOvernight = false;
                            break;
                        case 2:
                            item.shiftType = ShiftType.NIGHT;
                            item.startTime = "08:00 PM";
                            item.endTime = "08:00 AM";
                            item.isOvernight = true;
                            break;
                        case 3:
                            item.shiftType = ShiftType.GENERAL;
                            item.startTime = "09:00 AM";
                            item.endTime = "05:00 PM";
                            item.isOvernight = false;
                            break;
                        case 4:
                            item.shiftType = ShiftType.OFF;
                            item.startTime = "";
                            item.endTime = "";
                            item.isOvernight = false;
                            break;
                    }
                    item.isAmbiguous = false;
                    dutyAdapter.notifyItemChanged(position);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveImportedShifts() {
        List<RosterTextParser.ExtractedShiftItem> items = dutyAdapter.getItems();
        if (items.isEmpty()) {
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

        // Determine conflict policy
        DutyRepository.ImportConflictPolicy policy = DutyRepository.ImportConflictPolicy.MERGE_REPLACE_CONFLICTS;
        if (binding.rbReplaceRange.isChecked()) {
            policy = DutyRepository.ImportConflictPolicy.REPLACE_RANGE;
        } else if (binding.rbKeepExisting.isChecked()) {
            policy = DutyRepository.ImportConflictPolicy.KEEP_EXISTING_ON_CONFLICT;
        }

        binding.btnSaveShifts.setEnabled(false);
        final String employeeName = currentlySelectedStaff != null ? currentlySelectedStaff.name : "Staff";
        Log.i(TAG, "Number of records selected for import: " + duties.size());

        dutyRepository.importRosterDutiesDetailed(currentUid, duties, policy, minDate, maxDate, new DutyRepository.RepositoryCallback<DutyRepository.BulkImportResult>() {
            @Override
            public void onComplete(DutyRepository.BulkImportResult result) {
                Log.i(TAG, "=== Database Import Result ===");
                Log.i(TAG, "Number successfully inserted (new): " + result.createdCount);
                Log.i(TAG, "Number updated (existing): " + result.updatedCount);
                Log.i(TAG, "Number skipped: " + result.skippedCount);
                Log.i(TAG, "Total duties in calendar range: " + result.totalImported);

                ShiftNotificationManager.scheduleDailyDutyReminder(RosterImportActivity.this);
                ShiftNotificationManager.updatePersistentNextDutyNotification(RosterImportActivity.this);

                String monthLabel = detectedMonthYear != null ? detectedMonthYear.getDisplayLabel() : "Duty Roster";

                new MaterialAlertDialogBuilder(RosterImportActivity.this)
                        .setTitle("Roster Imported Successfully")
                        .setMessage("Imported " + result.totalImported + " shifts for " + employeeName + " (" + monthLabel + ").\n\n" +
                                "• Created new duties: " + result.createdCount + "\n" +
                                "• Updated existing duties: " + result.updatedCount + "\n" +
                                "• Skipped unchanged duties: " + result.skippedCount + "\n\n" +
                                "Your personal calendar and daily shift alarms have been updated.")
                        .setPositiveButton("View Calendar", (dialog, which) -> finish())
                        .setCancelable(false)
                        .show();
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "Import failed: " + e.getMessage(), e);
                binding.btnSaveShifts.setEnabled(true);
                Toast.makeText(RosterImportActivity.this, "Import failed: " + e.getLocalizedMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (ocrProcessor != null) {
            ocrProcessor.close();
        }
    }
}
