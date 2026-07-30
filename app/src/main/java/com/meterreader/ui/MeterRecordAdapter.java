package com.meterreader.ui;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.meterreader.R;
import com.meterreader.model.MeterData;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 历史记录列表适配器
 */
public class MeterRecordAdapter extends RecyclerView.Adapter<MeterRecordAdapter.ViewHolder> {

    private List<MeterData> records;
    private SimpleDateFormat sdf;

    public MeterRecordAdapter(List<MeterData> records) {
        this.records = records;
        this.sdf = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);
    }

    public void updateData(List<MeterData> newRecords) {
        this.records = newRecords;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_record, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        MeterData data = records.get(position);

        holder.tvTime.setText(sdf.format(new Date(data.getTimestamp())));
        holder.tvDevice.setText(String.format(Locale.CHINA, "电流表 #%d", data.getMeterAddress()));
        holder.tvCurrent.setText(String.format(Locale.CHINA, "%.3f A", data.getCurrentValue()));

        // 设置状态文本和颜色
        if (data.isOffline()) {
            holder.tvStatus.setText("离线");
            holder.tvStatus.setTextColor(Color.parseColor("#ff6b6b"));
            holder.tvCurrent.setTextColor(Color.parseColor("#ff6b6b"));
        } else if (data.isOverLimit()) {
            holder.tvStatus.setText("超限");
            holder.tvStatus.setTextColor(Color.parseColor("#ff6b6b"));
            holder.tvCurrent.setTextColor(Color.parseColor("#ff6b6b"));
        } else if (data.isUnderLimit()) {
            holder.tvStatus.setText("低限");
            holder.tvStatus.setTextColor(Color.parseColor("#ffd93d"));
            holder.tvCurrent.setTextColor(Color.parseColor("#ffd93d"));
        } else {
            holder.tvStatus.setText("正常");
            holder.tvStatus.setTextColor(Color.parseColor("#6bcb77"));
            holder.tvCurrent.setTextColor(Color.parseColor("#e0e0e0"));
        }
    }

    @Override
    public int getItemCount() {
        return records != null ? records.size() : 0;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvTime, tvDevice, tvCurrent, tvStatus;

        ViewHolder(View view) {
            super(view);
            tvTime = view.findViewById(R.id.tvItemTime);
            tvDevice = view.findViewById(R.id.tvItemDevice);
            tvCurrent = view.findViewById(R.id.tvItemCurrent);
            tvStatus = view.findViewById(R.id.tvItemStatus);
        }
    }
}
