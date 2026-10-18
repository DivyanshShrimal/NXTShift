package com.nxtshift.app.ui.dashboard;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.nxtshift.app.R;
import com.nxtshift.app.data.remote.CentralRosterService;
import com.nxtshift.app.data.repository.AuthRepository;
import com.nxtshift.app.data.repository.DutyRepository;
import com.nxtshift.app.databinding.ActivityDashboardBinding;
import com.nxtshift.app.domain.Duty;
import com.nxtshift.app.domain.ShiftType;
import com.nxtshift.app.notifications.ShiftNotificationManager;
import com.nxtshift.app.ui.admin.AdminDashboardActivity;
import com.nxtshift.app.ui.auth.WelcomeActivity;
import com.nxtshift.app.ui.calendar.CalendarActivity;
import com.nxtshift.app.ui.duty.EditDutyActivity;
import com.nxtshift.app.ui.importroster.PublishedRosterImportActivity;
import com.nxtshift.app.ui.importroster.RosterImportActivity;
import com.nxtshift.app.excel.ExcelRosterImportActivity;
import com.nxtshift.app.ui.settings.SettingsActivity;
import com.nxtshift.app.data.model.AccessState;
import com.nxtshift.app.data.repository.AccessControlRepository;
import com.nxtshift.app.ui.access.AccessRestrictedActivity;
import com.nxtshift.app.util.DateTimeUtils;

public class DashboardActivity extends AppCompatActivity {

    private ActivityDashboardBinding binding;
    private DutyRepository dutyRepository;
    private AuthRepository authRepository;
    private CentralRosterService centralRosterService;
    private DutyAdapter dutyAdapter;

    private String currentUid;
    private AccessControlRepository accessControlRepository;
    private boolean isRestrictedScreenShown = false;
    private Duty todayDuty;
    private Duty tomorrowDuty;

    private final ActivityResultLauncher<String> requestNotificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    ShiftNotificationManager.scheduleDailyDutyReminder(this);
                    ShiftNotificationManager.updatePersistentNextDutyNotification(this);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        authRepository = AuthRepository.getInstance(this);
        if (!authRepository.isUserSignedIn()) {
            startActivity(new Intent(this, WelcomeActivity.class));
            finish();
            return;
        }

        currentUid = authRepository.getCurrentUid();
        dutyRepository = DutyRepository.getInstance(this);
        centralRosterService = new CentralRosterService();
        accessControlRepository = AccessControlRepository.getInstance(this);

        binding = ActivityDashboardBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setupRecyclerView();
        setupClickListeners();
        observeDutyData();
        checkNotificationPermission();
        checkAdminStatus();
        observeAccessControl();
    }

    private void observeAccessControl() {
        accessControlRepository.startMonitoring(currentUid);
        accessControlRepository.getAccessStateLiveData().observe(this, state -> {
            if (state == AccessState.GLOBAL_DISABLED || state == AccessState.USER_REVOKED) {
                dutyRepository.stopListeningToCloudUpdates();
                binding.tvSyncStatus.setText(state == AccessState.GLOBAL_DISABLED ? "Maintenance Mode" : "Access Revoked");
                binding.viewSyncDot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_error)));
                if (!isRestrictedScreenShown) {
                    isRestrictedScreenShown = true;
                    Intent intent = new Intent(this, AccessRestrictedActivity.class);
                    startActivity(intent);
                }
            } else if (state == AccessState.ALLOWED) {
                isRestrictedScreenShown = false;
                dutyRepository.startListeningToCloudUpdates(currentUid);
                triggerSync();
            } else if (state == AccessState.OFFLINE_UNVERIFIED) {
                binding.tvSyncStatus.setText("Offline Cache");
                binding.viewSyncDot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_pending)));
            }
        });
    }

    private void checkAdminStatus() {
        if (currentUid == null) return;
        centralRosterService.checkIsAdmin(currentUid, new CentralRosterService.Callback<Boolean>() {
            @Override
            public void onSuccess(Boolean isAdmin) {
                if (Boolean.TRUE.equals(isAdmin)) {
                    binding.cardAdminPortal.setVisibility(View.VISIBLE);
                } else {
                    binding.cardAdminPortal.setVisibility(View.GONE);
                }
            }

            @Override
            public void onError(Exception e) {
                binding.cardAdminPortal.setVisibility(View.GONE);
            }
        });
    }

    private void setupRecyclerView() {
        dutyAdapter = new DutyAdapter(duty -> {
            Intent intent = new Intent(DashboardActivity.this, EditDutyActivity.class);
            intent.putExtra(EditDutyActivity.EXTRA_DUTY, duty);
            startActivity(intent);
        });

        binding.rvUpcomingDuties.setLayoutManager(new LinearLayoutManager(this));
        binding.rvUpcomingDuties.setAdapter(dutyAdapter);
    }

    private void setupClickListeners() {
        binding.cardAdminPortal.setOnClickListener(v -> {
            startActivity(new Intent(DashboardActivity.this, AdminDashboardActivity.class));
        });

        binding.btnOpenAdmin.setOnClickListener(v -> {
            startActivity(new Intent(DashboardActivity.this, AdminDashboardActivity.class));
        });

        binding.cardImportPublishedRoster.setOnClickListener(v -> {
            startActivity(new Intent(DashboardActivity.this, PublishedRosterImportActivity.class));
        });

        binding.cardImportExcelRoster.setOnClickListener(v -> {
            startActivity(new Intent(DashboardActivity.this, ExcelRosterImportActivity.class));
        });

        binding.cardImportRoster.setOnClickListener(v -> {
            startActivity(new Intent(DashboardActivity.this, RosterImportActivity.class));
        });

        binding.cardAddDuty.setOnClickListener(v -> {
            Intent intent = new Intent(DashboardActivity.this, EditDutyActivity.class);
            intent.putExtra(EditDutyActivity.EXTRA_INITIAL_DATE, DateTimeUtils.getTodayIsoDate());
            startActivity(intent);
        });

        binding.cardCalendar.setOnClickListener(v -> {
            startActivity(new Intent(DashboardActivity.this, CalendarActivity.class));
        });

        binding.tvViewAllCalendar.setOnClickListener(v -> {
            startActivity(new Intent(DashboardActivity.this, CalendarActivity.class));
        });

        binding.btnSettings.setOnClickListener(v -> {
            startActivity(new Intent(DashboardActivity.this, SettingsActivity.class));
        });

        binding.btnSyncNow.setOnClickListener(v -> triggerSync());

        binding.cardTodayDuty.setOnClickListener(v -> {
            Intent intent = new Intent(DashboardActivity.this, EditDutyActivity.class);
            if (todayDuty != null) {
                intent.putExtra(EditDutyActivity.EXTRA_DUTY, todayDuty);
            } else {
                intent.putExtra(EditDutyActivity.EXTRA_INITIAL_DATE, DateTimeUtils.getTodayIsoDate());
            }
            startActivity(intent);
        });

        binding.cardTomorrowDuty.setOnClickListener(v -> {
            Intent intent = new Intent(DashboardActivity.this, EditDutyActivity.class);
            if (tomorrowDuty != null) {
                intent.putExtra(EditDutyActivity.EXTRA_DUTY, tomorrowDuty);
            } else {
                intent.putExtra(EditDutyActivity.EXTRA_INITIAL_DATE, DateTimeUtils.getTomorrowIsoDate());
            }
            startActivity(intent);
        });
    }

    private void observeDutyData() {
        String todayIso = DateTimeUtils.getTodayIsoDate();
        String tomorrowIso = DateTimeUtils.getTomorrowIsoDate();

        // 1. Observe Today's Duty
        dutyRepository.getDutyForDate(currentUid, todayIso).observe(this, duty -> {
            this.todayDuty = duty;
            renderTodayCard(duty, todayIso);
        });

        // 2. Observe Tomorrow's Duty
        dutyRepository.getDutyForDate(currentUid, tomorrowIso).observe(this, duty -> {
            this.tomorrowDuty = duty;
            renderTomorrowCard(duty, tomorrowIso);
        });

        // 3. Observe Upcoming Duties (next 14 days)
        dutyRepository.getUpcomingDuties(currentUid, todayIso, 14).observe(this, duties -> {
            if (duties == null || duties.isEmpty()) {
                binding.tvEmptyRoster.setVisibility(View.VISIBLE);
                binding.rvUpcomingDuties.setVisibility(View.GONE);
                dutyAdapter.setDuties(null);
            } else {
                binding.tvEmptyRoster.setVisibility(View.GONE);
                binding.rvUpcomingDuties.setVisibility(View.VISIBLE);
                dutyAdapter.setDuties(duties);
            }
        });
    }

    private void renderTodayCard(Duty duty, String todayIso) {
        binding.tvTodayDate.setText(DateTimeUtils.formatDisplayDate(todayIso));

        if (duty == null) {
            binding.badgeTodayShiftType.setText("Not Scheduled");
            binding.badgeTodayShiftType.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.brand_primary_variant)));
            binding.badgeTodayShiftType.setTextColor(ContextCompat.getColor(this, R.color.text_on_dark_secondary));
            binding.tvTodayTime.setText("Tap to add today's shift");
            binding.layoutTodayColleagues.setVisibility(View.GONE);
            binding.layoutTodayWard.setVisibility(View.GONE);
            binding.tvTodayNotes.setVisibility(View.GONE);
        } else {
            ShiftType type = duty.getShiftType();
            binding.badgeTodayShiftType.setText(duty.getShiftName());
            binding.badgeTodayShiftType.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, type.getBgRes())));
            binding.badgeTodayShiftType.setTextColor(ContextCompat.getColor(this, type.getTextRes()));

            binding.tvTodayTime.setText(duty.getFormattedDutyTime());

            if (duty.getColleagues() != null && !duty.getColleagues().isEmpty()) {
                binding.layoutTodayColleagues.setVisibility(View.VISIBLE);
                binding.tvTodayColleagues.setText("With: " + duty.getColleagues());
            } else {
                binding.layoutTodayColleagues.setVisibility(View.GONE);
            }

            if (duty.getWardOrDepartment() != null && !duty.getWardOrDepartment().isEmpty()) {
                binding.layoutTodayWard.setVisibility(View.VISIBLE);
                binding.tvTodayWard.setText("Ward: " + duty.getWardOrDepartment());
            } else {
                binding.layoutTodayWard.setVisibility(View.GONE);
            }

            if (duty.getNotes() != null && !duty.getNotes().isEmpty()) {
                binding.tvTodayNotes.setVisibility(View.VISIBLE);
                binding.tvTodayNotes.setText("Notes: " + duty.getNotes());
            } else {
                binding.tvTodayNotes.setVisibility(View.GONE);
            }
        }
    }

    private void renderTomorrowCard(Duty duty, String tomorrowIso) {
        binding.tvTomorrowDate.setText(DateTimeUtils.formatDisplayDate(tomorrowIso));

        if (duty == null) {
            binding.badgeTomorrowShiftType.setText("Unassigned");
            binding.badgeTomorrowShiftType.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.bg_input)));
            binding.badgeTomorrowShiftType.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            binding.tvTomorrowTime.setText("No shift recorded");
            binding.tvTomorrowDetails.setText("Tap to set your schedule for tomorrow");
        } else {
            ShiftType type = duty.getShiftType();
            binding.badgeTomorrowShiftType.setText(duty.getShiftName());
            binding.badgeTomorrowShiftType.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, type.getBgRes())));
            binding.badgeTomorrowShiftType.setTextColor(ContextCompat.getColor(this, type.getTextRes()));

            binding.tvTomorrowTime.setText(duty.getFormattedDutyTime());

            StringBuilder details = new StringBuilder();
            if (duty.getWardOrDepartment() != null && !duty.getWardOrDepartment().isEmpty()) {
                details.append("Ward: ").append(duty.getWardOrDepartment());
            }
            if (duty.getColleagues() != null && !duty.getColleagues().isEmpty()) {
                if (details.length() > 0) details.append(" · ");
                details.append("With: ").append(duty.getColleagues());
            }
            binding.tvTomorrowDetails.setText(details.toString());
        }
    }

    private void triggerSync() {
        binding.tvSyncStatus.setText("Syncing...");
        binding.viewSyncDot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_pending)));

        dutyRepository.syncWithCloud(currentUid, new DutyRepository.RepositoryCallback<Boolean>() {
            @Override
            public void onComplete(Boolean result) {
                binding.tvSyncStatus.setText("Cloud Synced");
                binding.viewSyncDot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(DashboardActivity.this, R.color.status_synced)));
                ShiftNotificationManager.updatePersistentNextDutyNotification(DashboardActivity.this);
            }

            @Override
            public void onError(Exception e) {
                binding.tvSyncStatus.setText("Offline Cache");
                binding.viewSyncDot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(DashboardActivity.this, R.color.status_pending)));
            }
        });
    }

    private void checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ShiftNotificationManager.updatePersistentNextDutyNotification(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        dutyRepository.stopListeningToCloudUpdates();
        accessControlRepository.stopMonitoring();
    }
}
