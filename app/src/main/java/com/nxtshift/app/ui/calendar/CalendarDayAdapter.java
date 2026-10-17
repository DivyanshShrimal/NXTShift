package com.nxtshift.app.ui.calendar;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.nxtshift.app.R;
import com.nxtshift.app.domain.Duty;
import com.nxtshift.app.domain.DutyStatus;
import com.nxtshift.app.domain.ShiftType;
import com.nxtshift.app.util.DateTimeUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CalendarDayAdapter extends RecyclerView.Adapter<CalendarDayAdapter.DayViewHolder> {

    public interface OnDayClickListener {
        void onDayClick(LocalDate date);
    }

    public enum DayDutyVisualState {
        NONE,
        OFF,
        LEAVE,
        WORKING
    }

    public static class DayVisualResolution {
        public final DayDutyVisualState state;
        public final Duty primaryDuty;

        public DayVisualResolution(DayDutyVisualState state, Duty primaryDuty) {
            this.state = state;
            this.primaryDuty = primaryDuty;
        }
    }

    public static class DayItem {
        public LocalDate date;
        public boolean isCurrentMonth;
        public boolean isToday;
        public boolean isSelected;

        public DayItem(LocalDate date, boolean isCurrentMonth, boolean isToday, boolean isSelected) {
            this.date = date;
            this.isCurrentMonth = isCurrentMonth;
            this.isToday = isToday;
            this.isSelected = isSelected;
        }
    }

    private final List<DayItem> days = new ArrayList<>();
    private final Map<String, List<Duty>> dutyMapByDate = new HashMap<>();
    private final OnDayClickListener listener;
    private LocalDate selectedDate = LocalDate.now();

    public CalendarDayAdapter(OnDayClickListener listener) {
        this.listener = listener;
    }

    public void setDays(List<DayItem> newDays, LocalDate currentSelected) {
        this.days.clear();
        if (newDays != null) {
            this.days.addAll(newDays);
        }
        this.selectedDate = currentSelected;
        notifyDataSetChanged();
    }

    public void setDuties(List<Duty> duties) {
        this.dutyMapByDate.clear();
        if (duties != null) {
            for (Duty d : duties) {
                if (d.getDutyDate() != null) {
                    List<Duty> list = this.dutyMapByDate.computeIfAbsent(d.getDutyDate(), k -> new ArrayList<>());
                    list.add(d);
                }
            }
        }
        notifyDataSetChanged();
    }

    public void setSelectedDate(LocalDate date) {
        this.selectedDate = date;
        for (DayItem item : days) {
            item.isSelected = (item.date != null && item.date.isEqual(date));
        }
        notifyDataSetChanged();
    }

    public static boolean isLeaveDuty(Duty duty) {
        if (duty == null) return false;
        return duty.getShiftType() == ShiftType.LEAVE || duty.getStatus() == DutyStatus.LEAVE;
    }

    public static boolean isOffDuty(Duty duty) {
        if (duty == null) return false;
        return duty.getShiftType() == ShiftType.OFF || duty.getStatus() == DutyStatus.OFF;
    }

    public static DayVisualResolution resolveDayDuties(List<Duty> duties) {
        if (duties == null || duties.isEmpty()) {
            return new DayVisualResolution(DayDutyVisualState.NONE, null);
        }

        Duty workingDuty = null;
        Duty leaveDuty = null;
        Duty offDuty = null;

        for (Duty d : duties) {
            if (isLeaveDuty(d)) {
                if (leaveDuty == null) leaveDuty = d;
            } else if (isOffDuty(d)) {
                if (offDuty == null) offDuty = d;
            } else {
                if (workingDuty == null) workingDuty = d;
            }
        }

        if (workingDuty != null) {
            return new DayVisualResolution(DayDutyVisualState.WORKING, workingDuty);
        } else if (leaveDuty != null) {
            return new DayVisualResolution(DayDutyVisualState.LEAVE, leaveDuty);
        } else if (offDuty != null) {
            return new DayVisualResolution(DayDutyVisualState.OFF, offDuty);
        } else {
            return new DayVisualResolution(DayDutyVisualState.NONE, null);
        }
    }

    @NonNull
    @Override
    public DayViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_calendar_day, parent, false);
        return new DayViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull DayViewHolder holder, int position) {
        DayItem item = days.get(position);
        holder.bind(item, dutyMapByDate, selectedDate, listener);
    }

    @Override
    public int getItemCount() {
        return days.size();
    }

    static class DayViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvDayNumber;
        private final View viewShiftDot;
        private final TextView tvLeaveIndicator;

        public DayViewHolder(@NonNull View itemView) {
            super(itemView);
            tvDayNumber = itemView.findViewById(R.id.tvDayNumber);
            viewShiftDot = itemView.findViewById(R.id.viewShiftDot);
            tvLeaveIndicator = itemView.findViewById(R.id.tvLeaveIndicator);
        }

        public void bind(final DayItem item,
                         final Map<String, List<Duty>> dutyMap,
                         final LocalDate selectedDate,
                         final OnDayClickListener listener) {
            if (item.date == null) {
                tvDayNumber.setText("");
                viewShiftDot.setVisibility(View.INVISIBLE);
                if (tvLeaveIndicator != null) {
                    tvLeaveIndicator.setVisibility(View.GONE);
                }
                itemView.setOnClickListener(null);
                return;
            }

            Context context = itemView.getContext();
            tvDayNumber.setText(String.valueOf(item.date.getDayOfMonth()));

            boolean isSelected = item.date.isEqual(selectedDate);
            if (isSelected) {
                tvDayNumber.setBackgroundResource(R.drawable.bg_badge_rounded);
                tvDayNumber.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.brand_primary)));
                tvDayNumber.setTextColor(Color.WHITE);
                tvDayNumber.setTypeface(null, Typeface.BOLD);
            } else if (item.isToday) {
                tvDayNumber.setBackgroundResource(R.drawable.bg_badge_rounded);
                tvDayNumber.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.brand_accent_light)));
                tvDayNumber.setTextColor(ContextCompat.getColor(context, R.color.brand_primary));
                tvDayNumber.setTypeface(null, Typeface.BOLD);
            } else {
                tvDayNumber.setBackground(null);
                if (item.isCurrentMonth) {
                    tvDayNumber.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
                } else {
                    tvDayNumber.setTextColor(ContextCompat.getColor(context, R.color.text_muted));
                }
                tvDayNumber.setTypeface(null, Typeface.NORMAL);
            }

            // Check duties on this date and resolve visual state
            String isoDate = DateTimeUtils.toIsoDate(item.date);
            List<Duty> duties = dutyMap != null ? dutyMap.get(isoDate) : null;
            DayVisualResolution resolution = resolveDayDuties(duties);

            switch (resolution.state) {
                case WORKING:
                    viewShiftDot.setVisibility(View.VISIBLE);
                    if (tvLeaveIndicator != null) {
                        tvLeaveIndicator.setVisibility(View.GONE);
                    }
                    int dotColor = ContextCompat.getColor(context, resolution.primaryDuty.getShiftType().getColorRes());
                    viewShiftDot.setBackgroundTintList(ColorStateList.valueOf(dotColor));
                    break;
                case LEAVE:
                    viewShiftDot.setVisibility(View.GONE);
                    if (tvLeaveIndicator != null) {
                        tvLeaveIndicator.setVisibility(View.VISIBLE);
                        tvLeaveIndicator.setText("L");
                        tvLeaveIndicator.setTextColor(ContextCompat.getColor(context, R.color.shift_off));
                    }
                    break;
                case OFF:
                    // Requirement 1: Off date displayed with white background, no red dot
                    viewShiftDot.setVisibility(View.INVISIBLE);
                    if (tvLeaveIndicator != null) {
                        tvLeaveIndicator.setVisibility(View.GONE);
                    }
                    break;
                case NONE:
                default:
                    // Unscheduled / no entry date: clean cell, no dot, no leave indicator
                    viewShiftDot.setVisibility(View.INVISIBLE);
                    if (tvLeaveIndicator != null) {
                        tvLeaveIndicator.setVisibility(View.GONE);
                    }
                    break;
            }

            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDayClick(item.date);
                }
            });
        }
    }
}

