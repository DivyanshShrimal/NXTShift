package com.nxtshift.app.ui.duty;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.nxtshift.app.R;
import com.nxtshift.app.data.repository.AuthRepository;
import com.nxtshift.app.data.repository.DutyRepository;
import com.nxtshift.app.databinding.ActivityEditDutyBinding;
import com.nxtshift.app.domain.Duty;
import com.nxtshift.app.domain.DutyStatus;
import com.nxtshift.app.domain.ShiftType;
import com.nxtshift.app.notifications.ShiftNotificationManager;
import com.nxtshift.app.util.DateTimeUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class EditDutyActivity extends AppCompatActivity {

    public static final String EXTRA_DUTY = "extra_duty";
    public static final String EXTRA_INITIAL_DATE = "extra_initial_date";

    private ActivityEditDutyBinding binding;
    private DutyRepository dutyRepository;
    private String currentUid;

    private Duty existingDuty;
    private LocalDate selectedDate;
    private ShiftType selectedShiftType = ShiftType.MORNING;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityEditDutyBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        dutyRepository = DutyRepository.getInstance(this);
        currentUid = AuthRepository.getInstance(this).getCurrentUid();

        existingDuty = (Duty) getIntent().getSerializableExtra(EXTRA_DUTY);
        String initialDateStr = getIntent().getStringExtra(EXTRA_INITIAL_DATE);

        if (existingDuty != null) {
            binding.tvScreenTitle.setText(R.string.title_edit_duty);
            binding.btnDelete.setVisibility(View.VISIBLE);
            selectedDate = DateTimeUtils.parseIsoDate(existingDuty.getDutyDate());
            populateExistingDuty(existingDuty);
        } else {
            binding.tvScreenTitle.setText(R.string.title_add_duty);
            binding.btnDelete.setVisibility(View.GONE);
            selectedDate = initialDateStr != null ? DateTimeUtils.parseIsoDate(initialDateStr) : LocalDate.now();
            updateDateDisplay();
            selectShiftType(ShiftType.MORNING, true);
        }

        setupListeners();
    }

    private void setupListeners() {
        binding.btnBack.setOnClickListener(v -> finish());

        binding.etDate.setOnClickListener(v -> showDatePicker());
        binding.tilDate.setEndIconOnClickListener(v -> showDatePicker());

        binding.etStartTime.setOnClickListener(v -> showTimePicker(true));
        binding.tilStartTime.setEndIconOnClickListener(v -> showTimePicker(true));

        binding.etEndTime.setOnClickListener(v -> showTimePicker(false));
        binding.tilEndTime.setEndIconOnClickListener(v -> showTimePicker(false));

        binding.chipGroupShiftTypes.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.chipMorning) {
                selectShiftType(ShiftType.MORNING, false);
            } else if (checkedId == R.id.chipEvening) {
                selectShiftType(ShiftType.EVENING, false);
            } else if (checkedId == R.id.chipNight) {
                selectShiftType(ShiftType.NIGHT, false);
            } else if (checkedId == R.id.chipGeneral) {
                selectShiftType(ShiftType.GENERAL, false);
            } else if (checkedId == R.id.chipOff) {
                selectShiftType(ShiftType.OFF, false);
            } else if (checkedId == R.id.chipLeave) {
                selectShiftType(ShiftType.LEAVE, false);
            }
        });

        binding.btnSaveDuty.setOnClickListener(v -> saveDuty());

        binding.btnDelete.setOnClickListener(v -> confirmDelete());
    }

    private void populateExistingDuty(Duty duty) {
        updateDateDisplay();
        selectShiftType(duty.getShiftType(), false);

        if (duty.getStartTime() != null && !duty.getStartTime().isEmpty()) {
            binding.etStartTime.setText(duty.getStartTime());
        }
        if (duty.getEndTime() != null && !duty.getEndTime().isEmpty()) {
            binding.etEndTime.setText(duty.getEndTime());
        }
        binding.switchOvernight.setChecked(duty.isOvernight());

        if (duty.getWardOrDepartment() != null) {
            binding.etWard.setText(duty.getWardOrDepartment());
        }
        if (duty.getColleagues() != null) {
            binding.etColleagues.setText(duty.getColleagues());
        }
        if (duty.getNotes() != null) {
            binding.etNotes.setText(duty.getNotes());
        }
    }

    private void selectShiftType(ShiftType type, boolean forceDefaultTimes) {
        this.selectedShiftType = type;

        if (type == ShiftType.MORNING) {
            binding.chipMorning.setChecked(true);
        } else if (type == ShiftType.EVENING) {
            binding.chipEvening.setChecked(true);
        } else if (type == ShiftType.NIGHT) {
            binding.chipNight.setChecked(true);
        } else if (type == ShiftType.GENERAL) {
            binding.chipGeneral.setChecked(true);
        } else if (type == ShiftType.OFF) {
            binding.chipOff.setChecked(true);
        } else if (type == ShiftType.LEAVE) {
            binding.chipLeave.setChecked(true);
        }

        if (type == ShiftType.OFF || type == ShiftType.LEAVE) {
            binding.layoutTimeRow.setVisibility(View.GONE);
            binding.switchOvernight.setVisibility(View.GONE);
        } else {
            binding.layoutTimeRow.setVisibility(View.VISIBLE);
            binding.switchOvernight.setVisibility(View.VISIBLE);
            if (forceDefaultTimes || existingDuty == null) {
                binding.etStartTime.setText(type.getDefaultStartTime());
                binding.etEndTime.setText(type.getDefaultEndTime());
                binding.switchOvernight.setChecked(type.isDefaultOvernight());
            }
        }
    }

    private void showDatePicker() {
        DatePickerDialog dialog = new DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> {
                    selectedDate = LocalDate.of(year, month + 1, dayOfMonth);
                    updateDateDisplay();
                },
                selectedDate.getYear(),
                selectedDate.getMonthValue() - 1,
                selectedDate.getDayOfMonth()
        );
        dialog.show();
    }

    private void updateDateDisplay() {
        binding.etDate.setText(DateTimeUtils.formatDisplayDate(DateTimeUtils.toIsoDate(selectedDate)));
    }

    private void showTimePicker(final boolean isStartTime) {
        String currentText = isStartTime ?
                (binding.etStartTime.getText() != null ? binding.etStartTime.getText().toString() : "07:00 AM") :
                (binding.etEndTime.getText() != null ? binding.etEndTime.getText().toString() : "02:00 PM");

        int initialHour = 7;
        int initialMinute = 0;
        try {
            LocalTime lt = LocalTime.parse(DateTimeUtils.normalizeTime12Hour(currentText), DateTimeFormatter.ofPattern("hh:mm a", Locale.US));
            initialHour = lt.getHour();
            initialMinute = lt.getMinute();
        } catch (Exception ignored) {}

        TimePickerDialog dialog = new TimePickerDialog(
                this,
                (view, hourOfDay, minute) -> {
                    LocalTime lt = LocalTime.of(hourOfDay, minute);
                    String formatted = lt.format(DateTimeFormatter.ofPattern("hh:mm a", Locale.US));
                    if (isStartTime) {
                        binding.etStartTime.setText(formatted);
                    } else {
                        binding.etEndTime.setText(formatted);
                        // Auto-toggle overnight if end time is before start time
                        checkOvernightAutoToggle();
                    }
                },
                initialHour,
                initialMinute,
                false
        );
        dialog.show();
    }

    private void checkOvernightAutoToggle() {
        String start = binding.etStartTime.getText() != null ? binding.etStartTime.getText().toString() : "";
        String end = binding.etEndTime.getText() != null ? binding.etEndTime.getText().toString() : "";
        try {
            LocalTime st = LocalTime.parse(DateTimeUtils.normalizeTime12Hour(start), DateTimeFormatter.ofPattern("hh:mm a", Locale.US));
            LocalTime et = LocalTime.parse(DateTimeUtils.normalizeTime12Hour(end), DateTimeFormatter.ofPattern("hh:mm a", Locale.US));
            if (et.isBefore(st)) {
                binding.switchOvernight.setChecked(true);
            }
        } catch (Exception ignored) {}
    }

    private void saveDuty() {
        String isoDate = DateTimeUtils.toIsoDate(selectedDate);
        String startTime = binding.etStartTime.getText() != null ? binding.etStartTime.getText().toString().trim() : "";
        String endTime = binding.etEndTime.getText() != null ? binding.etEndTime.getText().toString().trim() : "";
        boolean isOvernight = binding.switchOvernight.isChecked();
        String ward = binding.etWard.getText() != null ? binding.etWard.getText().toString().trim() : "";
        String colleagues = binding.etColleagues.getText() != null ? binding.etColleagues.getText().toString().trim() : "";
        String notes = binding.etNotes.getText() != null ? binding.etNotes.getText().toString().trim() : "";

        Duty duty = existingDuty != null ? existingDuty : new Duty();
        duty.setOwnerUid(currentUid);
        duty.setDutyDate(isoDate);
        duty.setShiftType(selectedShiftType);
        duty.setShiftName(selectedShiftType.getDisplayName());
        duty.setStartTime(startTime);
        duty.setEndTime(endTime);
        duty.setOvernight(isOvernight);
        duty.setWardOrDepartment(ward);
        duty.setColleagues(colleagues);
        duty.setNotes(notes);

        if (selectedShiftType == ShiftType.OFF) {
            duty.setStatus(DutyStatus.OFF);
        } else if (selectedShiftType == ShiftType.LEAVE) {
            duty.setStatus(DutyStatus.LEAVE);
        } else {
            duty.setStatus(DutyStatus.SCHEDULED);
        }

        dutyRepository.saveDuty(duty, new DutyRepository.RepositoryCallback<Duty>() {
            @Override
            public void onComplete(Duty result) {
                Toast.makeText(EditDutyActivity.this, "Shift saved successfully", Toast.LENGTH_SHORT).show();
                ShiftNotificationManager.scheduleDailyDutyReminder(EditDutyActivity.this);
                ShiftNotificationManager.updatePersistentNextDutyNotification(EditDutyActivity.this);
                finish();
            }

            @Override
            public void onError(Exception e) {
                Toast.makeText(EditDutyActivity.this, "Error saving duty: " + e.getLocalizedMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void confirmDelete() {
        if (existingDuty == null) return;

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.confirm_delete_duty_title)
                .setMessage(R.string.confirm_delete_duty_message)
                .setPositiveButton(R.string.dialog_confirm, (dialog, which) -> {
                    dutyRepository.deleteDuty(existingDuty, new DutyRepository.RepositoryCallback<Void>() {
                        @Override
                        public void onComplete(Void result) {
                            Toast.makeText(EditDutyActivity.this, "Duty deleted", Toast.LENGTH_SHORT).show();
                            ShiftNotificationManager.scheduleDailyDutyReminder(EditDutyActivity.this);
                            ShiftNotificationManager.updatePersistentNextDutyNotification(EditDutyActivity.this);
                            finish();
                        }

                        @Override
                        public void onError(Exception e) {
                            Toast.makeText(EditDutyActivity.this, "Could not delete duty: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }
}
