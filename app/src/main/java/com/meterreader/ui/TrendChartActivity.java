package com.meterreader.ui;

import android.graphics.Color;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.meterreader.R;
import com.meterreader.model.MeterData;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 电流变化趋势图界面
 * 使用MPAndroidChart绘制电流随时间变化曲线
 * 红色虚线 = 2.0A超限阈值
 * 黄色虚线 = 0.2A低限阈值
 */
public class TrendChartActivity extends AppCompatActivity {

    private MeterReaderViewModel viewModel;
    private LineChart lineChart;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trend_chart);

        viewModel = new ViewModelProvider(this).get(MeterReaderViewModel.class);
        lineChart = findViewById(R.id.lineChart);

        setupChart();
        loadChartData();
    }

    private void setupChart() {
        // 图表基本配置
        lineChart.setBackgroundColor(Color.parseColor("#1a1a2e"));
        lineChart.setGridBackgroundColor(Color.parseColor("#16213e"));
        lineChart.setDrawGridBackground(true);
        lineChart.setDescription(null);
        lineChart.setNoDataText("暂无数据");
        lineChart.setNoDataTextColor(Color.parseColor("#9e9e9e"));

        // 启用触摸手势
        lineChart.setTouchEnabled(true);
        lineChart.setDragEnabled(true);
        lineChart.setScaleEnabled(true);
        lineChart.setPinchZoom(true);

        // X轴配置
        XAxis xAxis = lineChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setTextColor(Color.parseColor("#9e9e9e"));
        xAxis.setGridColor(Color.parseColor("#2a2a4e"));
        xAxis.setDrawGridLines(true);
        xAxis.setGranularity(1f);
        xAxis.setValueFormatter(new ValueFormatter() {
            private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.CHINA);

            @Override
            public String getFormattedValue(float value) {
                return sdf.format(new Date((long) value));
            }
        });

        // 左侧Y轴（电流值）
        YAxis leftAxis = lineChart.getAxisLeft();
        leftAxis.setTextColor(Color.parseColor("#9e9e9e"));
        leftAxis.setGridColor(Color.parseColor("#2a2a4e"));
        leftAxis.setAxisMinimum(0f);
        leftAxis.setAxisMaximum(2.5f);
        leftAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format(Locale.CHINA, "%.1fA", value);
            }
        });

        // 右侧Y轴隐藏
        lineChart.getAxisRight().setEnabled(false);

        // 图例配置
        Legend legend = lineChart.getLegend();
        legend.setTextColor(Color.parseColor("#e0e0e0"));
        legend.setForm(Legend.LegendForm.LINE);
        legend.setTextSize(12f);
    }

    private void loadChartData() {
        List<MeterData> records = viewModel.getHistoryRecords().getValue();
        if (records == null || records.isEmpty()) {
            return;
        }

        // 按时间正序排列
        List<MeterData> sortedRecords = new ArrayList<>(records);
        sortedRecords.sort((a, b) -> Long.compare(a.getTimestamp(), b.getTimestamp()));

        // 电流值数据点
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < sortedRecords.size(); i++) {
            MeterData data = sortedRecords.get(i);
            if (!data.isOffline()) {
                entries.add(new Entry(data.getTimestamp(), data.getCurrentValue()));
            }
        }

        if (entries.isEmpty()) return;

        // 电流值折线
        LineDataSet dataSet = new LineDataSet(entries, "电流值");
        dataSet.setColor(Color.parseColor("#4fc3f7"));
        dataSet.setLineWidth(2f);
        dataSet.setCircleColor(Color.parseColor("#4fc3f7"));
        dataSet.setCircleRadius(3f);
        dataSet.setDrawCircleHole(false);
        dataSet.setValueTextColor(Color.parseColor("#e0e0e0"));
        dataSet.setValueTextSize(8f);
        dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        dataSet.setDrawValues(false);

        LineData lineData = new LineData(dataSet);

        // 添加超限阈值线（2.0A - 红色虚线）
        LineDataSet limitHigh = createLimitLine(2.0f, "超限阈值 2.0A",
                Color.parseColor("#ff6b6b"));
        lineData.addDataSet(limitHigh);

        // 添加低限阈值线（0.2A - 蓝色虚线）
        LineDataSet limitLow = createLimitLine(0.2f, "低限阈值 0.2A",
                Color.parseColor("#ffd93d"));
        lineData.addDataSet(limitLow);

        lineChart.setData(lineData);
        lineChart.invalidate();
    }

    /**
     * 创建阈值指示线
     */
    private LineDataSet createLimitLine(float value, String label, int color) {
        List<Entry> limitEntries = new ArrayList<>();
        limitEntries.add(new Entry(0, value));
        limitEntries.add(new Entry(System.currentTimeMillis(), value));

        LineDataSet limitSet = new LineDataSet(limitEntries, label);
        limitSet.setColor(color);
        limitSet.setLineWidth(1.5f);
        limitSet.setDrawCircles(false);
        limitSet.setDrawValues(false);
        limitSet.enableDashedLine(10f, 10f, 0f);
        limitSet.setMode(LineDataSet.Mode.LINEAR);

        return limitSet;
    }
}
