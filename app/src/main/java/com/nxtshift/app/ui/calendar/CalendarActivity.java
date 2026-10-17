package com.nxtshift.app.ui.calendar;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.nxtshift.app.data.repository.AuthRepository;
import com.nxtshift.app.data.repository.DutyRepository;
import com.nxtshift.app.databinding.ActivityCalendarBinding;
import com.nxtshift.app.domain.Duty;
import com.nxtshift.app.ui.dashboard.DutyAdapter;
import com.nxtshift.app.ui.duty.EditDutyActivity;
import com.nxtshift.app.util.DateTimeUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CalendarActivity extends AppCompatActivity {

    private ActivityCalendarBinding binding;
    private DutyRepository dutyRepository;
    private String currentUid;

    private YearMonth currentYearMonth;
    private LocalDate selectedDate;

    private CalendarDayAdapter calendarDayAdapter;
    private DutyAdapter selectedDayDutyAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCalendarBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        dutyRepository = DutyRepository.getInstance(this);
        currentUid = AuthRepository.getInstance(this).getCurrentUid();

        currentYearMonth = YearMonth.now();
        selectedDate = LocalDate.now();

        setupUI();
        loadMonthData();
    }

    private void setupUI() {
        binding.btnBack.setOnClickListener(v -> finish());

        binding.btnAddShift.setOnClickListener(v -> {
            Intent intent = new Intent(this, EditDutyActivity.class);
            intent.putExtra(EditDutyActivity.EXTRA_INITIAL_DATE, DateTimeUtils.toIsoDate(selectedDate));
            startActivity(intent);
        });

        binding.btnPrevMonth.setOnClickListener(v -> {
            currentYearMonth = currentYearMonth.minusMonths(1);
            loadMonthData();
        });

        binding.btnNextMonth.setOnClickListener(v -> {
            currentYearMonth = currentYearMonth.plusMonths(1);
            loadMonthData();
        });

        // Setup Month Grid
        calendarDayAdapter = new CalendarDayAdapter(date -> {
            this.selectedDate = date;
            calendarDayAdapter.setSelectedDate(date);
            loadSelectedDayDuties();
        });
        binding.rvCalendarGrid.setLayoutManager(new GridLayoutManager(this, 7));
        binding.rvCalendarGrid.setAdapter(calendarDayAdapter);

        // Setup Day Duties list
        selectedDayDutyAdapter = new DutyAdapter(duty -> {
            Intent intent = new Intent(this, EditDutyActivity.class);
            intent.putExtra(EditDutyActivity.EXTRA_DUTY, duty);
            startActivity(intent);
        });
        binding.rvSelectedDateDuties.setLayoutManager(new LinearLayoutManager(this));
        binding.rvSelectedDateDuties.setAdapter(selectedDayDutyAdapter);
    }

    private void loadMonthData() {
        binding.tvCurrentMonth.setText(DateTimeUtils.formatMonthYear(currentYearMonth.atDay(1)));

        // Generate 7-column calendar grid items
        List<CalendarDayAdapter.DayItem> dayItems = new ArrayList<>();
        LocalDate firstOfMonth = currentYearMonth.atDay(1);
        int daysInMonth = currentYearMonth.lengthOfMonth();

        // Determine offset for day of week (Sunday = 0)
        DayOfWeek firstDow = firstOfMonth.getDayOfWeek();
        int leadingEmptyDays = firstDow.getValue() % 7; // Sunday=7->0, Monday=1->1

        for (int i = 0; i < leadingEmptyDays; i++) {
            dayItems.add(new CalendarDayAdapter.DayItem(null, false, false, false));
        }

        LocalDate today = LocalDate.now();
        for (int day = 1; day <= daysInMonth; day++) {
            LocalDate date = currentYearMonth.atDay(day);
            boolean isToday = date.isEqual(today);
            boolean isSelected = date.isEqual(selectedDate);
            dayItems.add(new CalendarDayAdapter.DayItem(date, true, isToday, isSelected));
        }

        calendarDayAdapter.setDays(dayItems, selectedDate);

        // Fetch duties for entire month to display shift dots
        String startIso = DateTimeUtils.toIsoDate(firstOfMonth);
        String endIso = DateTimeUtils.toIsoDate(currentYearMonth.atEndOfMonth());

        dutyRepository.getDutiesBetweenDates(currentUid, startIso, endIso).observe(this, duties -> {
            calendarDayAdapter.setDuties(duties);
        });

        loadSelectedDayDuties();
    }

    private void loadSelectedDayDuties() {
        String isoDate = DateTimeUtils.toIsoDate(selectedDate);
        binding.tvSelectedDateHeading.setText("DUTIES FOR " + DateTimeUtils.formatDisplayDate(isoDate).toUpperCase());

        dutyRepository.getDutiesBetweenDates(currentUid, isoDate, isoDate).observe(this, duties -> {
            if (duties == null || duties.isEmpty()) {
                binding.tvEmptySelectedDay.setVisibility(View.VISIBLE);
                binding.rvSelectedDateDuties.setVisibility(View.GONE);
                selectedDayDutyAdapter.setDuties(Collections.emptyList());
                android.util.Log.d("CalendarActivity", "[RosterDiagnostic] Calendar UI loaded 0 duties for " + isoDate);
            } else {
                binding.tvEmptySelectedDay.setVisibility(View.GONE);
                binding.rvSelectedDateDuties.setVisibility(View.VISIBLE);
                selectedDayDutyAdapter.setDuties(duties);
                for (Duty d : duties) {
                    android.util.Log.i("CalendarActivity", "[RosterDiagnostic] Calendar UI rendering duty: Date=" + d.getDutyDate() + ", Shift=" + d.getShiftName() + ", Colleagues=" + d.getColleagues());
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadMonthData();
    }
}
