package com.nxtshift.app.excel;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.nxtshift.app.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ExcelStaffPickerAdapter extends RecyclerView.Adapter<ExcelStaffPickerAdapter.ViewHolder> {

    public interface OnStaffSelectedListener {
        void onStaffSelected(String staffName);
    }

    private final List<String> originalList = new ArrayList<>();
    private final List<String> filteredList = new ArrayList<>();
    private final OnStaffSelectedListener listener;
    private String userProfileName = "";

    public ExcelStaffPickerAdapter(OnStaffSelectedListener listener) {
        this.listener = listener;
    }

    public void setStaffList(List<String> staffList, String userProfileName) {
        this.originalList.clear();
        this.filteredList.clear();
        this.userProfileName = userProfileName != null ? userProfileName.trim() : "";
        if (staffList != null) {
            this.originalList.addAll(staffList);
            this.filteredList.addAll(staffList);
        }
        notifyDataSetChanged();
    }

    public void filter(String query) {
        filteredList.clear();
        if (query == null || query.trim().isEmpty()) {
            filteredList.addAll(originalList);
        } else {
            String lower = query.trim().toLowerCase(Locale.US);
            for (String s : originalList) {
                if (s.toLowerCase(Locale.US).contains(lower)) {
                    filteredList.add(s);
                }
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_excel_staff_picker, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String staffName = filteredList.get(position);
        holder.bind(staffName, userProfileName, listener);
    }

    @Override
    public int getItemCount() {
        return filteredList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvStaffInitial;
        private final TextView tvStaffName;
        private final TextView tvStaffDetails;
        private final TextView tvSelectArrow;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvStaffInitial = itemView.findViewById(R.id.tvStaffInitial);
            tvStaffName = itemView.findViewById(R.id.tvStaffName);
            tvStaffDetails = itemView.findViewById(R.id.tvStaffDetails);
            tvSelectArrow = itemView.findViewById(R.id.tvSelectArrow);
        }

        public void bind(final String staffName, String userProfileName, final OnStaffSelectedListener listener) {
            tvStaffName.setText(staffName);

            String initial = staffName.length() > 0 ? staffName.substring(0, 1).toUpperCase(Locale.US) : "N";
            tvStaffInitial.setText(initial);

            boolean isUserProfileMatch = !userProfileName.isEmpty() && staffName.equalsIgnoreCase(userProfileName);
            if (isUserProfileMatch) {
                tvStaffDetails.setText("★ Matches your current signed-in profile");
                tvStaffDetails.setTextColor(itemView.getContext().getColor(R.color.brand_primary));
            } else {
                tvStaffDetails.setText("Extracted from workbook duties, off & leave");
                tvStaffDetails.setTextColor(itemView.getContext().getColor(R.color.text_secondary));
            }

            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onStaffSelected(staffName);
                }
            });
        }
    }
}
