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
    private final Map<String, Duty> dutyMapByDate = new HashMap<>();
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
                this.dutyMapByDate.put(d.getDutyDate(), d);
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

        public DayViewHolder(@NonNull View itemView) {
            super(itemView);
            tvDayNumber = itemView.findViewById(R.id.tvDayNumber);
            viewShiftDot = itemView.findViewById(R.id.viewShiftDot);
        }

        public void bind(final DayItem item,
                         final Map<String, Duty> dutyMap,
                         final LocalDate selectedDate,
                         final OnDayClickListener listener) {
            if (item.date == null) {
                tvDayNumber.setText("");
                viewShiftDot.setVisibility(View.INVISIBLE);
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

            // Check if there is a duty on this date
            String isoDate = DateTimeUtils.toIsoDate(item.date);
            Duty duty = dutyMap.get(isoDate);

            if (duty != null) {
                viewShiftDot.setVisibility(View.VISIBLE);
                int dotColor = ContextCompat.getColor(context, duty.getShiftType().getColorRes());
                viewShiftDot.setBackgroundTintList(ColorStateList.valueOf(dotColor));
            } else {
                viewShiftDot.setVisibility(View.INVISIBLE);
            }

            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDayClick(item.date);
                }
            });
        }
    }
}
