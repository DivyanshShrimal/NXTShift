package com.nxtshift.app.ui.importroster;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.nxtshift.app.R;
import com.nxtshift.app.ocr.FullMonthRosterParser;

import java.util.ArrayList;
import java.util.List;

public class StaffSelectionAdapter extends RecyclerView.Adapter<StaffSelectionAdapter.ViewHolder> {

    public interface OnStaffSelectedListener {
        void onStaffSelected(FullMonthRosterParser.DiscoveredStaffMember staff);
    }

    private final List<FullMonthRosterParser.DiscoveredStaffMember> originalList = new ArrayList<>();
    private final List<FullMonthRosterParser.DiscoveredStaffMember> filteredList = new ArrayList<>();
    private final String profileDisplayName;
    private final OnStaffSelectedListener listener;

    public StaffSelectionAdapter(List<FullMonthRosterParser.DiscoveredStaffMember> staffList,
                                 String profileDisplayName,
                                 OnStaffSelectedListener listener) {
        this.profileDisplayName = profileDisplayName;
        this.listener = listener;
        if (staffList != null) {
            this.originalList.addAll(staffList);
            this.filteredList.addAll(staffList);
        }
    }

    public void filter(String query) {
        filteredList.clear();
        if (query == null || query.trim().isEmpty()) {
            filteredList.addAll(originalList);
        } else {
            String lower = query.toLowerCase().trim();
            for (FullMonthRosterParser.DiscoveredStaffMember item : originalList) {
                if (item.name.toLowerCase().contains(lower) ||
                        item.normalizedName.contains(lower)) {
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
        FullMonthRosterParser.DiscoveredStaffMember member = filteredList.get(position);
        holder.bind(member, profileDisplayName, listener);
    }

    @Override
    public int getItemCount() {
        return filteredList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvStaffName;
        private final TextView tvStaffDutyCount;
        private final TextView tvProfileMatch;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvStaffName = itemView.findViewById(R.id.tvStaffName);
            tvStaffDutyCount = itemView.findViewById(R.id.tvStaffDutyCount);
            tvProfileMatch = itemView.findViewById(R.id.tvProfileMatch);
        }

        public void bind(final FullMonthRosterParser.DiscoveredStaffMember member,
                         String profileDisplayName,
                         final OnStaffSelectedListener listener) {
            tvStaffName.setText(member.name);
            tvStaffDutyCount.setText(member.dutyCount + (member.dutyCount == 1 ? " shift" : " shifts"));

            boolean isMatch = false;
            if (profileDisplayName != null && !profileDisplayName.trim().isEmpty()) {
                String normProfile = FullMonthRosterParser.normalizeForComparison(profileDisplayName);
                if (member.normalizedName.equalsIgnoreCase(normProfile) ||
                        member.normalizedName.contains(normProfile) ||
                        normProfile.contains(member.normalizedName)) {
                    isMatch = true;
                }
            }

            tvProfileMatch.setVisibility(isMatch ? View.VISIBLE : View.GONE);

            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onStaffSelected(member);
                }
            });
        }
    }
}
