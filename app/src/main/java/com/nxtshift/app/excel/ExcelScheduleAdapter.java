package com.nxtshift.app.excel;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.nxtshift.app.R;
import com.nxtshift.app.domain.ShiftType;
import com.nxtshift.app.util.DateTimeUtils;

import java.util.ArrayList;
import java.util.List;

public class ExcelScheduleAdapter extends RecyclerView.Adapter<ExcelScheduleAdapter.ViewHolder> {

    public interface OnItemClickListener {
        void onItemClick(ExcelStaffScheduleItem item, int position);
    }

    private final List<ExcelStaffScheduleItem> items = new ArrayList<>();
    private final OnItemClickListener resolveListener;

    public ExcelScheduleAdapter(OnItemClickListener resolveListener) {
        this.resolveListener = resolveListener;
    }

    public void setItems(List<ExcelStaffScheduleItem> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    public List<ExcelStaffScheduleItem> getItems() {
        return items;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_excel_schedule_preview, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ExcelStaffScheduleItem item = items.get(position);
        holder.bind(item, position, resolveListener);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvDate;
        private final TextView badgeShift;
        private final TextView tvDutyTime;
        private final View layoutColleagues;
        private final TextView tvColleagues;
        private final View layoutWarningBanner;
        private final TextView tvWarningText;
        private final TextView btnResolveConflict;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvDate = itemView.findViewById(R.id.tvDate);
            badgeShift = itemView.findViewById(R.id.badgeShift);
            tvDutyTime = itemView.findViewById(R.id.tvDutyTime);
            layoutColleagues = itemView.findViewById(R.id.layoutColleagues);
            tvColleagues = itemView.findViewById(R.id.tvColleagues);
            layoutWarningBanner = itemView.findViewById(R.id.layoutWarningBanner);
            tvWarningText = itemView.findViewById(R.id.tvWarningText);
            btnResolveConflict = itemView.findViewById(R.id.btnResolveConflict);
        }

        public void bind(final ExcelStaffScheduleItem item, final int position, final OnItemClickListener resolveListener) {
            Context context = itemView.getContext();
            tvDate.setText(DateTimeUtils.formatDisplayDate(item.getDate()));

            // Determine badge colors and labels
            if (item.getStatus() == ExcelStaffScheduleItem.StatusType.CONFLICT) {
                badgeShift.setText("CONFLICT");
                badgeShift.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.shift_off_bg)));
                badgeShift.setTextColor(ContextCompat.getColor(context, R.color.shift_off_text));
            } else if (item.getStatus() == ExcelStaffScheduleItem.StatusType.OFF) {
                badgeShift.setText("OFF");
                badgeShift.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.shift_off_bg)));
                badgeShift.setTextColor(ContextCompat.getColor(context, R.color.shift_off_text));
            } else if (item.getStatus() == ExcelStaffScheduleItem.StatusType.LEAVE) {
                badgeShift.setText("LEAVE");
                badgeShift.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.shift_off_bg)));
                badgeShift.setTextColor(ContextCompat.getColor(context, R.color.shift_off_text));
            } else if (item.getStatus() == ExcelStaffScheduleItem.StatusType.UNASSIGNED) {
                badgeShift.setText("UNASSIGNED");
                badgeShift.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.border_light)));
                badgeShift.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
            } else {
                ShiftType type = item.getShiftType() != null ? item.getShiftType() : ShiftType.GENERAL;
                badgeShift.setText(type.getDisplayName());
                badgeShift.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context, type.getBgRes())));
                badgeShift.setTextColor(ContextCompat.getColor(context, type.getTextRes()));
            }

            // Duty Text / Timing
            tvDutyTime.setText(item.getFormattedDutyText());

            // Colleagues
            if (item.getColleagues() != null && !item.getColleagues().trim().isEmpty()) {
                layoutColleagues.setVisibility(View.VISIBLE);
                tvColleagues.setText("With: " + item.getColleagues());
            } else {
                layoutColleagues.setVisibility(View.GONE);
            }

            // Warnings / Conflict Banner
            if (!item.getValidationWarnings().isEmpty() || item.hasConflict()) {
                layoutWarningBanner.setVisibility(View.VISIBLE);
                tvWarningText.setText(item.getCombinedWarnings());
                btnResolveConflict.setVisibility(item.hasConflict() ? View.VISIBLE : View.GONE);
            } else {
                layoutWarningBanner.setVisibility(View.GONE);
            }

            // Resolve click
            View.OnClickListener clickHandler = v -> {
                if (resolveListener != null) {
                    resolveListener.onItemClick(item, position);
                }
            };
            btnResolveConflict.setOnClickListener(clickHandler);
            itemView.setOnClickListener(clickHandler);
        }
    }
}
