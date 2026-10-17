package com.nxtshift.app.ui.importroster;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.nxtshift.app.R;
import com.nxtshift.app.ocr.FullMonthRosterParser;

import java.util.ArrayList;
import java.util.List;

public class StaffDirectoryReviewAdapter extends RecyclerView.Adapter<StaffDirectoryReviewAdapter.ViewHolder> {

    public interface StaffActionListener {
        void onStaffSelected(FullMonthRosterParser.DiscoveredStaffMember staff);
        void onEditStaff(FullMonthRosterParser.DiscoveredStaffMember staff, int position);
        void onSplitStaff(FullMonthRosterParser.DiscoveredStaffMember staff, int position);
        void onMergeStaff(FullMonthRosterParser.DiscoveredStaffMember staff, int position);
        void onDeleteStaff(FullMonthRosterParser.DiscoveredStaffMember staff, int position);
    }

    private final List<FullMonthRosterParser.DiscoveredStaffMember> originalList = new ArrayList<>();
    private final List<FullMonthRosterParser.DiscoveredStaffMember> displayList = new ArrayList<>();
    private String selectedStaffNormalized = "";
    private final String profileDisplayName;
    private final StaffActionListener listener;

    public StaffDirectoryReviewAdapter(List<FullMonthRosterParser.DiscoveredStaffMember> staffList,
                                       String profileDisplayName,
                                       StaffActionListener listener) {
        this.profileDisplayName = profileDisplayName;
        this.listener = listener;
        setItems(staffList);
    }

    public void setItems(List<FullMonthRosterParser.DiscoveredStaffMember> list) {
        originalList.clear();
        displayList.clear();
        if (list != null) {
            originalList.addAll(list);
            displayList.addAll(list);
        }

        // Auto-select match if profile exists, or first item
        if (selectedStaffNormalized.isEmpty()) {
            if (profileDisplayName != null && !profileDisplayName.trim().isEmpty()) {
                String normProf = FullMonthRosterParser.normalizeForComparison(profileDisplayName);
                for (FullMonthRosterParser.DiscoveredStaffMember s : displayList) {
                    if (s.normalizedName.equalsIgnoreCase(normProf)) {
                        selectedStaffNormalized = s.normalizedName;
                        break;
                    }
                }
            }
            if (selectedStaffNormalized.isEmpty() && !displayList.isEmpty()) {
                selectedStaffNormalized = displayList.get(0).normalizedName;
            }
        }
        notifyDataSetChanged();
    }

    public void filter(String query) {
        displayList.clear();
        if (query == null || query.trim().isEmpty()) {
            displayList.addAll(originalList);
        } else {
            String lower = query.toLowerCase().trim();
            for (FullMonthRosterParser.DiscoveredStaffMember s : originalList) {
                if (s.name.toLowerCase().contains(lower) || s.normalizedName.contains(lower)) {
                    displayList.add(s);
                }
            }
        }
        notifyDataSetChanged();
    }

    public List<FullMonthRosterParser.DiscoveredStaffMember> getAllStaff() {
        return originalList;
    }

    public FullMonthRosterParser.DiscoveredStaffMember getSelectedStaff() {
        for (FullMonthRosterParser.DiscoveredStaffMember s : originalList) {
            if (s.normalizedName.equalsIgnoreCase(selectedStaffNormalized)) {
                return s;
            }
        }
        return !originalList.isEmpty() ? originalList.get(0) : null;
    }

    public void setSelectedStaff(String normalizedName) {
        this.selectedStaffNormalized = normalizedName;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_staff_directory_review, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        FullMonthRosterParser.DiscoveredStaffMember item = displayList.get(position);
        holder.bind(item, position, selectedStaffNormalized, profileDisplayName, listener);
    }

    @Override
    public int getItemCount() {
        return displayList.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private final RadioButton rbSelectStaff;
        private final TextView tvStaffName;
        private final TextView tvStaffDutyCount;
        private final TextView tvProfileMatch;
        private final MaterialButton btnEditStaff;
        private final MaterialButton btnSplitStaff;
        private final MaterialButton btnMergeStaff;
        private final MaterialButton btnDeleteStaff;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            rbSelectStaff = itemView.findViewById(R.id.rbSelectStaff);
            tvStaffName = itemView.findViewById(R.id.tvStaffName);
            tvStaffDutyCount = itemView.findViewById(R.id.tvStaffDutyCount);
            tvProfileMatch = itemView.findViewById(R.id.tvProfileMatch);
            btnEditStaff = itemView.findViewById(R.id.btnEditStaff);
            btnSplitStaff = itemView.findViewById(R.id.btnSplitStaff);
            btnMergeStaff = itemView.findViewById(R.id.btnMergeStaff);
            btnDeleteStaff = itemView.findViewById(R.id.btnDeleteStaff);
        }

        public void bind(final FullMonthRosterParser.DiscoveredStaffMember item,
                         final int position,
                         String currentSelectedNorm,
                         String profileName,
                         final StaffActionListener listener) {

            tvStaffName.setText(item.name);
            tvStaffDutyCount.setText(item.dutyCount + (item.dutyCount == 1 ? " shift" : " shifts"));

            boolean isSelected = item.normalizedName.equalsIgnoreCase(currentSelectedNorm);
            rbSelectStaff.setChecked(isSelected);

            boolean isProfileMatch = false;
            if (profileName != null && !profileName.trim().isEmpty()) {
                String normP = FullMonthRosterParser.normalizeForComparison(profileName);
                if (item.normalizedName.equalsIgnoreCase(normP)) {
                    isProfileMatch = true;
                }
            }
            tvProfileMatch.setVisibility(isProfileMatch ? View.VISIBLE : View.GONE);

            // Selection click
            itemView.setOnClickListener(v -> {
                selectedStaffNormalized = item.normalizedName;
                notifyDataSetChanged();
                if (listener != null) listener.onStaffSelected(item);
            });

            rbSelectStaff.setOnClickListener(v -> {
                selectedStaffNormalized = item.normalizedName;
                notifyDataSetChanged();
                if (listener != null) listener.onStaffSelected(item);
            });

            btnEditStaff.setOnClickListener(v -> {
                if (listener != null) listener.onEditStaff(item, position);
            });

            btnSplitStaff.setOnClickListener(v -> {
                if (listener != null) listener.onSplitStaff(item, position);
            });

            btnMergeStaff.setOnClickListener(v -> {
                if (listener != null) listener.onMergeStaff(item, position);
            });

            btnDeleteStaff.setOnClickListener(v -> {
                if (listener != null) listener.onDeleteStaff(item, position);
            });
        }
    }
}
