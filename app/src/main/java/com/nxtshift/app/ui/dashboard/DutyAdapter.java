package com.nxtshift.app.ui.dashboard;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.nxtshift.app.R;
import com.nxtshift.app.domain.Duty;
import com.nxtshift.app.domain.ShiftType;
import com.nxtshift.app.util.DateTimeUtils;

import java.util.ArrayList;
import java.util.List;

public class DutyAdapter extends RecyclerView.Adapter<DutyAdapter.DutyViewHolder> {

    public interface OnDutyClickListener {
        void onDutyClick(Duty duty);
    }

    private final List<Duty> dutyList = new ArrayList<>();
    private final OnDutyClickListener clickListener;

    public DutyAdapter(OnDutyClickListener clickListener) {
        this.clickListener = clickListener;
    }

    public void setDuties(List<Duty> newDuties) {
        DutyDiffCallback diffCallback = new DutyDiffCallback(this.dutyList, newDuties);
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(diffCallback);
        this.dutyList.clear();
        if (newDuties != null) {
            this.dutyList.addAll(newDuties);
        }
        diffResult.dispatchUpdatesTo(this);
    }

    @NonNull
    @Override
    public DutyViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_duty_card, parent, false);
        return new DutyViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull DutyViewHolder holder, int position) {
        Duty duty = dutyList.get(position);
        holder.bind(duty, clickListener);
    }

    @Override
    public int getItemCount() {
        return dutyList.size();
    }

    static class DutyViewHolder extends RecyclerView.ViewHolder {
        private final View viewShiftColorIndicator;
        private final TextView tvDutyDate;
        private final TextView badgeShiftType;
        private final TextView tvDutyTime;
        private final TextView tvDutyMeta;

        public DutyViewHolder(@NonNull View itemView) {
            super(itemView);
            viewShiftColorIndicator = itemView.findViewById(R.id.viewShiftColorIndicator);
            tvDutyDate = itemView.findViewById(R.id.tvDutyDate);
            badgeShiftType = itemView.findViewById(R.id.badgeShiftType);
            tvDutyTime = itemView.findViewById(R.id.tvDutyTime);
            tvDutyMeta = itemView.findViewById(R.id.tvDutyMeta);
        }

        public void bind(final Duty duty, final OnDutyClickListener listener) {
            Context context = itemView.getContext();
            ShiftType shiftType = duty.getShiftType();

            // Set color bar and badge
            int colorRes = ContextCompat.getColor(context, shiftType.getColorRes());
            int bgRes = ContextCompat.getColor(context, shiftType.getBgRes());
            int textRes = ContextCompat.getColor(context, shiftType.getTextRes());

            viewShiftColorIndicator.setBackgroundColor(colorRes);
            badgeShiftType.setBackgroundTintList(ColorStateList.valueOf(bgRes));
            badgeShiftType.setTextColor(textRes);
            badgeShiftType.setText(duty.getShiftName());

            // Date & Time
            tvDutyDate.setText(DateTimeUtils.formatDisplayDate(duty.getDutyDate()));
            tvDutyTime.setText(duty.getFormattedDutyTime());

            // Ward & Colleagues Snippet
            StringBuilder meta = new StringBuilder();
            if (duty.getWardOrDepartment() != null && !duty.getWardOrDepartment().isEmpty()) {
                meta.append("Ward: ").append(duty.getWardOrDepartment());
            }
            if (duty.getColleagues() != null && !duty.getColleagues().isEmpty()) {
                if (meta.length() > 0) meta.append(" · ");
                meta.append("With: ").append(duty.getColleagues());
            }
            if (meta.length() == 0) {
                meta.append(duty.getStatus().getLabel());
            }
            tvDutyMeta.setText(meta.toString());

            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDutyClick(duty);
                }
            });
        }
    }

    private static class DutyDiffCallback extends DiffUtil.Callback {
        private final List<Duty> oldList;
        private final List<Duty> newList;

        public DutyDiffCallback(List<Duty> oldList, List<Duty> newList) {
            this.oldList = oldList;
            this.newList = newList != null ? newList : new ArrayList<>();
        }

        @Override
        public int getOldListSize() {
            return oldList.size();
        }

        @Override
        public int getNewListSize() {
            return newList.size();
        }

        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            return oldList.get(oldItemPosition).getId().equals(newList.get(newItemPosition).getId());
        }

        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            Duty o = oldList.get(oldItemPosition);
            Duty n = newList.get(newItemPosition);
            return o.getDutyDate().equals(n.getDutyDate()) &&
                    o.getShiftType() == n.getShiftType() &&
                    o.getFormattedDutyTime().equals(n.getFormattedDutyTime()) &&
                    o.getUpdatedAt() == n.getUpdatedAt();
        }
    }
}
