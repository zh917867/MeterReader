package com.meterreader.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.meterreader.R;
import com.meterreader.model.MeterData;

import java.util.ArrayList;

/**
 * 历史数据查看界面
 * 支持查看、清除历史数据，以及查看电流变化趋势
 */
public class HistoryActivity extends AppCompatActivity {

    private MeterReaderViewModel viewModel;
    private RecyclerView rvHistory;
    private Button btnShowChart, btnClearData;
    private MeterRecordAdapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        viewModel = new ViewModelProvider(this).get(MeterReaderViewModel.class);

        rvHistory = findViewById(R.id.rvHistory);
        btnShowChart = findViewById(R.id.btnShowChart);
        btnClearData = findViewById(R.id.btnClearData);

        rvHistory.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MeterRecordAdapter(new ArrayList<>());
        rvHistory.setAdapter(adapter);

        // 加载历史数据
        viewModel.getHistoryRecords().observe(this, records -> {
            adapter.updateData(records);
        });

        // 查看趋势图
        btnShowChart.setOnClickListener(v -> {
            Intent intent = new Intent(HistoryActivity.this, TrendChartActivity.class);
            startActivity(intent);
        });

        // 清除数据
        btnClearData.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("确认清除")
                    .setMessage("确定要清除所有历史数据吗？此操作不可恢复！")
                    .setPositiveButton("确定", (dialog, which) -> {
                        viewModel.clearAllData();
                        Toast.makeText(this, "数据已清除", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.loadHistoryRecords();
    }
}
