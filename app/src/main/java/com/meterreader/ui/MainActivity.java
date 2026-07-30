package com.meterreader.ui;

import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.meterreader.R;
import com.meterreader.model.MeterData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 无线读表器主界面
 * 作为电路C——安卓手机充当无线读表器
 * 支持：
 * - 蓝牙设备发现扫描
 * - 多电表数据管理
 * - 自动轮询模式
 */
public class MainActivity extends AppCompatActivity {

    private MeterReaderViewModel viewModel;

    // UI组件
    private Spinner spinnerDevices;
    private Button btnScan, btnConnect, btnDisconnect;
    private Button btnManualRead, btnAutoMode, btnHistory;
    private TextView tvConnectionStatus;
    private TextView tvCurrentValue, tvMeterAddress, tvUpdateTime, tvModeStatus, tvAlarmMessage;
    private RecyclerView rvRecentRecords;

    private MeterRecordAdapter adapter;
    // 设备列表（用于spinner索引）
    private List<BluetoothDevice> deviceList = new ArrayList<>();
    // 三种设备来源标记: "bonded" = 已配对的, "discovered" = 扫描发现的, 否则=地址字符串
    private List<String> deviceSources = new ArrayList<>();
    private ArrayAdapter<String> spinnerAdapter;

    // 多表数据显示的简单状态
    private boolean showingMeterList = false;

    // 运行时权限请求码
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 100;

    /**
     * Android 12+ 蓝牙运行时权限列表
     */
    private static final String[] BLUETOOTH_PERMISSIONS_API31 = {
        android.Manifest.permission.BLUETOOTH_SCAN,
        android.Manifest.permission.BLUETOOTH_CONNECT,
        android.Manifest.permission.ACCESS_FINE_LOCATION
    };

    /**
     * Android 10-11 蓝牙扫描需要定位权限
     */
    private static final String[] BLUETOOTH_PERMISSIONS_LEGACY = {
        android.Manifest.permission.ACCESS_FINE_LOCATION
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewModel = new ViewModelProvider(this).get(MeterReaderViewModel.class);

        initViews();
        initSpinner();
        observeData();
        setupClickListeners();

        // 检查并请求蓝牙运行时权限
        requestBluetoothPermissions();
    }

    /**
     * 检查并请求蓝牙相关运行时权限
     */
    private void requestBluetoothPermissions() {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ (API 31+)
            permissions = BLUETOOTH_PERMISSIONS_API31;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10-11 (API 29-30)
            permissions = BLUETOOTH_PERMISSIONS_LEGACY;
        } else {
            // Android 9及以下，无需运行时权限，直接初始化蓝牙
            viewModel.initializeBluetooth();
            return;
        }

        // 检查是否所有权限都已授予
        boolean allGranted = true;
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm)
                    != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            // 权限已授予，初始化蓝牙
            viewModel.initializeBluetooth();
        } else {
            // 请求权限
            ActivityCompat.requestPermissions(this, permissions, REQUEST_BLUETOOTH_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                // 权限已授予，初始化蓝牙
                viewModel.initializeBluetooth();
            } else {
                // 权限被拒绝，提示用户
                Toast.makeText(this, "需要蓝牙权限才能使用读表器功能", Toast.LENGTH_LONG).show();
                checkIfPermissionPermanentlyDenied(permissions, grantResults);
            }
        }
    }

    /**
     * 检查是否有权限被"不再询问"且未授权，引导用户去设置
     */
    private void checkIfPermissionPermanentlyDenied(String[] permissions, int[] grantResults) {
        boolean showRationale = false;
        boolean permanentlyDenied = false;

        for (int i = 0; i < permissions.length; i++) {
            if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, permissions[i])) {
                    showRationale = true;
                } else {
                    permanentlyDenied = true;
                }
            }
        }

        if (permanentlyDenied) {
            // 权限被永久拒绝，引导用户去系统设置中手动开启
            new AlertDialog.Builder(this)
                    .setTitle("需要蓝牙权限")
                    .setMessage("蓝牙权限已被永久拒绝，请在系统设置中手动开启蓝牙相关权限以正常使用读表器功能。")
                    .setPositiveButton("去设置", (dialog, which) -> {
                        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }
    }

    private void initViews() {
        spinnerDevices = findViewById(R.id.spinnerDevices);
        btnScan = findViewById(R.id.btnScan);
        btnConnect = findViewById(R.id.btnConnect);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        btnManualRead = findViewById(R.id.btnManualRead);
        btnAutoMode = findViewById(R.id.btnAutoMode);
        btnHistory = findViewById(R.id.btnHistory);
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);
        tvCurrentValue = findViewById(R.id.tvCurrentValue);
        tvMeterAddress = findViewById(R.id.tvMeterAddress);
        tvUpdateTime = findViewById(R.id.tvUpdateTime);
        tvModeStatus = findViewById(R.id.tvModeStatus);
        tvAlarmMessage = findViewById(R.id.tvAlarmMessage);
        rvRecentRecords = findViewById(R.id.rvRecentRecords);

        // 初始化近期记录列表
        rvRecentRecords.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MeterRecordAdapter(new ArrayList<>());
        rvRecentRecords.setAdapter(adapter);
    }

    private void initSpinner() {
        spinnerAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new ArrayList<>());
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDevices.setAdapter(spinnerAdapter);

        // spinner选择监听：选中的设备用于连接
        spinnerDevices.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // 不做自动操作，让用户点击"连接"按钮
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void observeData() {
        // ----- 已配对设备列表（初始加载） -----
        viewModel.getBondedDevices().observe(this, devices -> {
            // 仅在未发现设备时填充默认列表
            List<BluetoothDevice> discovered = viewModel.getDiscoveredDevices().getValue();
            if (discovered == null || discovered.isEmpty()) {
                buildSpinnerFromBonded(devices);
            }
        });

        // ----- 发现设备列表（扫描结果） -----
        viewModel.getDiscoveredDevices().observe(this, devices -> {
            if (devices != null && !devices.isEmpty()) {
                updateSpinnerWithDiscovered(devices);
            }
        });

        // ----- 扫描状态 -----
        viewModel.getIsScanning().observe(this, scanning -> {
            if (Boolean.TRUE.equals(scanning)) {
                btnScan.setText("扫描中...");
                btnScan.setEnabled(false);
            } else {
                btnScan.setText("搜索设备");
                btnScan.setEnabled(true);
            }
        });

        // ----- 当前电流数据 -----
        viewModel.getCurrentData().observe(this, data -> {
            if (data != null) {
                tvCurrentValue.setText(String.format(java.util.Locale.CHINA, "%.3f", data.getCurrentValue()));
                tvMeterAddress.setText("#" + data.getMeterAddress());
                tvUpdateTime.setText(data.getShortTime());
            }
        });

        // ----- 连接状态 -----
        viewModel.getConnectionStatus().observe(this, connected -> {
            if (connected) {
                tvConnectionStatus.setText("已连接");
                tvConnectionStatus.setTextColor(0xFF6BCB77);
                btnConnect.setEnabled(false);
                btnDisconnect.setEnabled(true);
            } else {
                tvConnectionStatus.setText("未连接");
                tvConnectionStatus.setTextColor(0xFFFF6B6B);
                btnConnect.setEnabled(true);
                btnDisconnect.setEnabled(false);
            }
        });

        // ----- 已连接设备名 -----
        viewModel.getConnectedDeviceName().observe(this, name -> {
            if (!name.isEmpty()) {
                Toast.makeText(this, "已连接到: " + name, Toast.LENGTH_SHORT).show();
            }
        });

        // ----- 工作模式 -----
        viewModel.getModeStatus().observe(this, status -> {
            tvModeStatus.setText(status);
        });

        // ----- 报警消息 -----
        viewModel.getAlarmMessage().observe(this, alarm -> {
            if (alarm != null && !alarm.isEmpty()) {
                tvAlarmMessage.setText(alarm);
                tvAlarmMessage.setVisibility(View.VISIBLE);
            } else {
                tvAlarmMessage.setVisibility(View.GONE);
            }
        });

        // ----- 自动模式状态 -----
        viewModel.getIsAutoMode().observe(this, auto -> {
            if (Boolean.TRUE.equals(auto)) {
                btnAutoMode.setText("停止轮询");
                btnAutoMode.setBackgroundTintList(
                        getResources().getColorStateList(android.R.color.holo_red_dark, getTheme()));
            } else {
                btnAutoMode.setText("自动轮询");
                btnAutoMode.setBackgroundTintList(
                        getResources().getColorStateList(com.google.android.material.R.attr.colorPrimary, getTheme()));
            }
        });

        // ----- 全部电表数据（多表监控） -----
        viewModel.getAllMeterData().observe(this, meterMap -> {
            updateMeterListDisplay(meterMap);
        });

        // ----- 历史记录 -----
        viewModel.getHistoryRecords().observe(this, records -> {
            adapter.updateData(records);
        });
    }

    // ========== Spinner 更新逻辑 ==========

    /**
     * 用已配对设备填充 Spinner
     */
    private void buildSpinnerFromBonded(List<BluetoothDevice> devices) {
        deviceList.clear();
        deviceSources.clear();
        List<String> names = new ArrayList<>();

        if (devices == null || devices.isEmpty()) {
            names.add("未发现已配对设备 - 点'搜索'扫描");
            deviceSources.add("none");
        } else {
            for (BluetoothDevice d : devices) {
                deviceList.add(d);
                deviceSources.add("bonded");
                String name = d.getName() != null ? d.getName() : "未知设备";
                names.add("📱 " + name + " [" + d.getAddress() + "]");
            }
            // 添加扫描提示项
            names.add("--- 点'搜索设备'发现新设备 ---");
            deviceSources.add("hint");
        }

        spinnerAdapter.clear();
        spinnerAdapter.addAll(names);
        spinnerAdapter.notifyDataSetChanged();
    }

    /**
     * 用发现设备更新 Spinner
     */
    private void updateSpinnerWithDiscovered(List<BluetoothDevice> discovered) {
        // 合并已配对和发现设备，去重
        deviceList.clear();
        deviceSources.clear();

        // 先去重集合
        java.util.Set<String> addedAddresses = new java.util.HashSet<>();
        List<String> names = new ArrayList<>();

        // 已配对设备优先显示
        List<BluetoothDevice> bonded = viewModel.getBondedDevices().getValue();
        if (bonded != null) {
            for (BluetoothDevice d : bonded) {
                if (!addedAddresses.contains(d.getAddress())) {
                    addedAddresses.add(d.getAddress());
                    deviceList.add(d);
                    deviceSources.add("bonded");
                    String name = d.getName() != null ? d.getName() : "未知设备";
                    names.add("📱 " + name + " (已配对) [" + d.getAddress() + "]");
                }
            }
        }

        // 新发现的设备
        for (BluetoothDevice d : discovered) {
            if (!addedAddresses.contains(d.getAddress())) {
                addedAddresses.add(d.getAddress());
                deviceList.add(d);
                deviceSources.add("discovered");
                String name = d.getName() != null ? d.getName() : "未知设备";
                names.add("📡 " + name + " [" + d.getAddress() + "]");
            }
        }

        if (names.isEmpty()) {
            names.add("未发现蓝牙设备");
            deviceSources.add("none");
        }

        spinnerAdapter.clear();
        spinnerAdapter.addAll(names);
        spinnerAdapter.notifyDataSetChanged();

        // 扫描完成时的提示
        String alarm = viewModel.getAlarmMessage().getValue();
        if (alarm != null && alarm.contains("扫描完成")) {
            Toast.makeText(this, "扫描完成，发现 " + discovered.size() + " 个设备", Toast.LENGTH_SHORT).show();
        }
    }

    // ========== 多表数据显示 ==========

    /**
     * 在电流显示区域更新多表信息
     * 如果有多个电表数据，显示汇总信息
     */
    private void updateMeterListDisplay(Map<Integer, MeterData> meterMap) {
        if (meterMap == null || meterMap.isEmpty()) {
            return;
        }

        // 如果有多个表有数据，在状态区显示摘要
        if (meterMap.size() > 1) {
            StringBuilder sb = new StringBuilder();
            sb.append("已监测 ").append(meterMap.size()).append(" 个电表");
            tvMeterAddress.setText(sb.toString());
        }
    }

    // ========== 点击事件 ==========

    private void setupClickListeners() {
        // 搜索设备
        btnScan.setOnClickListener(v -> {
            viewModel.startScan();
            Toast.makeText(this, "正在扫描蓝牙设备...", Toast.LENGTH_SHORT).show();
        });

        // 连接按钮
        btnConnect.setOnClickListener(v -> {
            int position = spinnerDevices.getSelectedItemPosition();
            if (position >= 0 && position < deviceList.size()) {
                BluetoothDevice device = deviceList.get(position);
                boolean success = viewModel.connectToDevice(device);
                if (!success) {
                    Toast.makeText(this, "连接失败，请检查蓝牙是否开启", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "请先选择要连接的设备", Toast.LENGTH_SHORT).show();
            }
        });

        // 断开连接
        btnDisconnect.setOnClickListener(v -> {
            viewModel.disconnect();
            Toast.makeText(this, "已断开连接", Toast.LENGTH_SHORT).show();
        });

        // 手动读取
        btnManualRead.setOnClickListener(v -> {
            viewModel.manualRead();
        });

        // 自动轮询模式
        btnAutoMode.setOnClickListener(v -> {
            Boolean isAuto = viewModel.getIsAutoMode().getValue();
            if (Boolean.TRUE.equals(isAuto)) {
                viewModel.stopAutoMode();
                Toast.makeText(this, "已停止自动轮询", Toast.LENGTH_SHORT).show();
            } else {
                viewModel.startAutoMode();
                Toast.makeText(this, "自动轮询已启动，每2分钟一轮", Toast.LENGTH_SHORT).show();
            }
        });

        // 历史数据
        btnHistory.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, HistoryActivity.class);
            startActivity(intent);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 每次回到前台时重新检查权限并刷新数据
        if (viewModel.isBluetoothInitialized()) {
            viewModel.loadBondedDevices();
            viewModel.loadHistoryRecords();
        } else {
            // 如果蓝牙尚未初始化（可能刚从设置页面返回），重新检查权限
            requestBluetoothPermissions();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        viewModel.disconnect();
    }
}
