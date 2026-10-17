package com.nxtshift.app.ui.importroster;

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
import com.nxtshift.app.ocr.RosterTextParser;
import com.nxtshift.app.util.DateTimeUtils;

import java.util.ArrayList;
import java.util.List;

public class ExtractedShiftAdapter extends RecyclerView.Adapter<ExtractedShiftAdapter.ViewHolder> {

    public interface OnItemClickListener {
        void onItemClick(RosterTextParser.ExtractedShiftItem item, int position);
    }

    private final List<RosterTextParser.ExtractedShiftItem> items = new ArrayList<>();
    private final OnItemClickListener listener;

    public ExtractedShiftAdapter(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<RosterTextParser.ExtractedShiftItem> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    public List<RosterTextParser.ExtractedShiftItem> getItems() {
        return items;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_extracted_shift, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        RosterTextParser.ExtractedShiftItem item = items.get(position);
        holder.bind(item, position, listener);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvExtractedDate;
        private final TextView tvExtractedShiftBadge;
        private final TextView tvExtractedTime;
        private final TextView tvExtractedWard;
        private final TextView tvExtractedColleagues;
        private final TextView tvWarning;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvExtractedDate = itemView.findViewById(R.id.tvExtractedDate);
            tvExtractedShiftBadge = itemView.findViewById(R.id.tvExtractedShiftBadge);
            tvExtractedTime = itemView.findViewById(R.id.tvExtractedTime);
            tvExtractedWard = itemView.findViewById(R.id.tvExtractedWard);
            tvExtractedColleagues = itemView.findViewById(R.id.tvExtractedColleagues);
            tvWarning = itemView.findViewById(R.id.tvWarning);
        }

        public void bind(final RosterTextParser.ExtractedShiftItem item, final int position, final OnItemClickListener listener) {
            Context context = itemView.getContext();
            tvExtractedDate.setText(DateTimeUtils.formatDisplayDate(item.date));

            ShiftType shiftType = item.shiftType != null ? item.shiftType : ShiftType.MORNING;
            int bgRes = ContextCompat.getColor(context, shiftType.getBgRes());
            int textRes = ContextCompat.getColor(context, shiftType.getTextRes());

            tvExtractedShiftBadge.setBackgroundTintList(ColorStateList.valueOf(bgRes));
            tvExtractedShiftBadge.setTextColor(textRes);
            tvExtractedShiftBadge.setText(shiftType.getDisplayName());

            String timeStr = item.startTime + (item.endTime != null && !item.endTime.isEmpty() ? " – " + item.endTime : "");
            if (item.isOvernight) timeStr += " (+1d)";
            tvExtractedTime.setText(timeStr);

            if (item.ward != null && !item.ward.isEmpty()) {
                tvExtractedWard.setText("Ward: " + item.ward);
            } else {
                tvExtractedWard.setText("No Ward");
            }

            if (item.colleagues != null && !item.colleagues.trim().isEmpty()) {
                tvExtractedColleagues.setVisibility(View.VISIBLE);
                tvExtractedColleagues.setText("Colleagues: " + item.colleagues);
            } else {
                tvExtractedColleagues.setVisibility(View.GONE);
            }

            if (item.isAmbiguous) {
                tvWarning.setVisibility(View.VISIBLE);
            } else {
                tvWarning.setVisibility(View.GONE);
            }

            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onItemClick(item, position);
                }
            });
        }
    }
}
