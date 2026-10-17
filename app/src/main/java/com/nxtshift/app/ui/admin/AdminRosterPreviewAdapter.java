package com.nxtshift.app.ui.admin;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.nxtshift.app.R;
import com.nxtshift.app.data.model.MonthlyRoster;
import com.nxtshift.app.util.DateTimeUtils;

import java.util.ArrayList;
import java.util.List;

public class AdminRosterPreviewAdapter extends RecyclerView.Adapter<AdminRosterPreviewAdapter.DayViewHolder> {

    private final List<MonthlyRoster.DaySchedule> dayList = new ArrayList<>();

    public void setDays(List<MonthlyRoster.DaySchedule> days) {
        this.dayList.clear();
        if (days != null) {
            this.dayList.addAll(days);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public DayViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_admin_roster_day, parent, false);
        return new DayViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull DayViewHolder holder, int position) {
        MonthlyRoster.DaySchedule day = dayList.get(position);
        holder.bind(day);
    }

    @Override
    public int getItemCount() {
        return dayList.size();
    }

    static class DayViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvDayDate;
        private final TextView tvStaffCount;
        private final TextView tvDayShifts;
        private final TextView tvDayOffLeave;

        public DayViewHolder(@NonNull View itemView) {
            super(itemView);
            tvDayDate = itemView.findViewById(R.id.tvDayDate);
            tvStaffCount = itemView.findViewById(R.id.tvStaffCount);
            tvDayShifts = itemView.findViewById(R.id.tvDayShifts);
            tvDayOffLeave = itemView.findViewById(R.id.tvDayOffLeave);
        }

        public void bind(MonthlyRoster.DaySchedule day) {
            tvDayDate.setText(DateTimeUtils.formatDisplayDate(day.getDate()) + " (Day " + day.getDayNumber() + ")");

            int totalAssigned = 0;
            StringBuilder shiftsSb = new StringBuilder();

            for (MonthlyRoster.ShiftAssignment sa : day.getShiftAssignments()) {
                if (!sa.getStaff().isEmpty()) {
                    totalAssigned += sa.getStaff().size();
                    if (shiftsSb.length() > 0) shiftsSb.append("\n");
                    shiftsSb.append("• ").append(sa.getColumnName()).append(": ");
                    shiftsSb.append(String.join(", ", sa.getStaff()));
                }
            }

            if (shiftsSb.length() == 0) {
                tvDayShifts.setText("No working shifts scheduled");
            } else {
                tvDayShifts.setText(shiftsSb.toString());
            }

            StringBuilder offLeaveSb = new StringBuilder();
            if (!day.getOffStaff().isEmpty()) {
                offLeaveSb.append("Off: ").append(String.join(", ", day.getOffStaff()));
                totalAssigned += day.getOffStaff().size();
            }
            if (!day.getLeaveStaff().isEmpty()) {
                if (offLeaveSb.length() > 0) offLeaveSb.append(" · ");
                offLeaveSb.append("Leave: ").append(String.join(", ", day.getLeaveStaff()));
                totalAssigned += day.getLeaveStaff().size();
            }

            if (offLeaveSb.length() > 0) {
                tvDayOffLeave.setVisibility(View.VISIBLE);
                tvDayOffLeave.setText(offLeaveSb.toString());
            } else {
                tvDayOffLeave.setVisibility(View.GONE);
            }

            tvStaffCount.setText(totalAssigned + " staff");
        }
    }
}
