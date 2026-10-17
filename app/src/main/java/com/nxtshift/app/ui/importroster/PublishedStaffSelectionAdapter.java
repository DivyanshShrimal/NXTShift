package com.nxtshift.app.ui.importroster;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.nxtshift.app.R;
import com.nxtshift.app.data.model.MonthlyRoster;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Adapter for selecting an approved hospital staff member from the centrally published roster.
 */
public class PublishedStaffSelectionAdapter extends RecyclerView.Adapter<PublishedStaffSelectionAdapter.ViewHolder> {

    public interface OnStaffSelectedListener {
        void onStaffSelected(String staffName);
    }

    public static class StaffItem {
        public final String name;
        public final int dutyCount;
        public final boolean isProfileMatch;

        public StaffItem(String name, int dutyCount, boolean isProfileMatch) {
            this.name = name;
            this.dutyCount = dutyCount;
            this.isProfileMatch = isProfileMatch;
        }
    }

    private final List<StaffItem> originalList = new ArrayList<>();
    private final List<StaffItem> filteredList = new ArrayList<>();
    private final OnStaffSelectedListener listener;

    public PublishedStaffSelectionAdapter(MonthlyRoster roster, String profileDisplayName, OnStaffSelectedListener listener) {
        this.listener = listener;
        setRoster(roster, profileDisplayName);
    }

    public void setRoster(MonthlyRoster roster, String profileDisplayName) {
        originalList.clear();
        filteredList.clear();

        if (roster != null && roster.getStaffList() != null) {
            String profileNorm = profileDisplayName != null ? profileDisplayName.trim().toLowerCase(Locale.US) : "";

            for (String staffName : roster.getStaffList()) {
                int duties = roster.extractDutiesForStaff(staffName).size();
                boolean isMatch = !profileNorm.isEmpty() && staffName.toLowerCase(Locale.US).equals(profileNorm);
                StaffItem item = new StaffItem(staffName, duties, isMatch);
                originalList.add(item);
                filteredList.add(item);
            }
        }
        notifyDataSetChanged();
    }

    public void filter(String query) {
        filteredList.clear();
        if (query == null || query.trim().isEmpty()) {
            filteredList.addAll(originalList);
        } else {
            String lower = query.toLowerCase(Locale.US).trim();
            for (StaffItem item : originalList) {
                if (item.name.toLowerCase(Locale.US).contains(lower)) {
                    filteredList.add(item);
                }
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_staff_selection, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        StaffItem item = filteredList.get(position);
        holder.bind(item, listener);
    }

    @Override
    public int getItemCount() {
        return filteredList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvStaffName;
        private final TextView tvProfileMatch;
        private final TextView tvStaffDutyCount;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvStaffName = itemView.findViewById(R.id.tvStaffName);
            tvProfileMatch = itemView.findViewById(R.id.tvProfileMatch);
            tvStaffDutyCount = itemView.findViewById(R.id.tvStaffDutyCount);
        }

        public void bind(StaffItem item, OnStaffSelectedListener listener) {
            tvStaffName.setText(item.name);
            tvStaffDutyCount.setText(item.dutyCount + " duties");
            tvProfileMatch.setVisibility(item.isProfileMatch ? View.VISIBLE : View.GONE);

            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onStaffSelected(item.name);
                }
            });
        }
    }
}
