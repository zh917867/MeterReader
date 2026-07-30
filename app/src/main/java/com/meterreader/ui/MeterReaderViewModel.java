package com.meterreader.ui;

import android.app.Application;
import android.bluetooth.BluetoothDevice;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.meterreader.bluetooth.BluetoothReaderService;
import com.meterreader.data.DatabaseHelper;
import com.meterreader.model.MeterData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 读表器ViewModel
 * 管理蓝牙扫描、连接、自动轮询、数据采集和存储
 */
public class MeterReaderViewModel extends AndroidViewModel {

    private BluetoothReaderService bluetoothService;
    private DatabaseHelper databaseHelper;

    // ----- 连接相关 -----
    private MutableLiveData<Boolean> connectionStatus = new MutableLiveData<>(false);
    private MutableLiveData<String> connectedDeviceName = new MutableLiveData<>("");
    private MutableLiveData<String> modeStatus = new MutableLiveData<>("待机");
    private MutableLiveData<String> alarmMessage = new MutableLiveData<>("");

    // ----- 设备发现与扫描 -----
    private MutableLiveData<List<BluetoothDevice>> discoveredDevices = new MutableLiveData<>(new ArrayList<>());
    private MutableLiveData<Boolean> isScanning = new MutableLiveData<>(false);
    private MutableLiveData<List<BluetoothDevice>> bondedDevices = new MutableLiveData<>(new ArrayList<>());

    // ----- 数据显示 -----
    // 当前显示的电表数据（单表模式/手动读取）
    private MutableLiveData<MeterData> currentData = new MutableLiveData<>();
    // 多表最新数据 key=地址, value=最新数据
    private MutableLiveData<Map<Integer, MeterData>> allMeterData = new MutableLiveData<>(new HashMap<>());
    // 选中查看的电表地址（-1表示所有）
    private MutableLiveData<Integer> selectedAddress = new MutableLiveData<>(-1);

    // ----- 自动轮询 -----
    private MutableLiveData<Boolean> isAutoMode = new MutableLiveData<>(false);

    // ----- 历史记录 -----
    private MutableLiveData<List<MeterData>> historyRecords = new MutableLiveData<>(new ArrayList<>());

    public MeterReaderViewModel(@NonNull Application application) {
        super(application);
        databaseHelper = DatabaseHelper.getInstance(application);
        bluetoothService = new BluetoothReaderService(application);
        setupListeners();
        loadBondedDevices();
    }

    // ========== 监听器设置 ==========

    private void setupListeners() {
        // 数据接收
        bluetoothService.setOnDataReceivedListener(data -> {
            currentData.postValue(data);

            // 存储到数据库
            databaseHelper.insertRecord(data);

            // 更新多表缓存
            Map<Integer, MeterData> currentMap = allMeterData.getValue();
            if (currentMap == null) {
                currentMap = new HashMap<>();
            }
            currentMap.put(data.getMeterAddress(), data);
            allMeterData.postValue(currentMap);

            // 限制数据库记录数（保留最近1000条）
            databaseHelper.trimRecords(1000);

            // 更新历史记录
            loadHistoryRecords();

            // 检查报警
            checkAlarm(data);
        });

        // 连接状态
        bluetoothService.setOnConnectionListener(new BluetoothReaderService.OnConnectionListener() {
            @Override
            public void onConnected(BluetoothDevice device) {
                connectionStatus.postValue(true);
                connectedDeviceName.postValue(device.getName() != null ? device.getName() : device.getAddress());
                modeStatus.postValue("已连接");
            }

            @Override
            public void onDisconnected() {
                connectionStatus.postValue(false);
                connectedDeviceName.postValue("");
                modeStatus.postValue("已断开");

                // 创建离线记录
                MeterData offlineData = new MeterData(
                    System.currentTimeMillis(), 0, 0f);
                offlineData.setOffline(true);
                currentData.postValue(offlineData);
                alarmMessage.postValue("⚠ 连接断开！");

                // 停止自动模式
                if (Boolean.TRUE.equals(isAutoMode.getValue())) {
                    isAutoMode.postValue(false);
                }
            }

            @Override
            public void onError(String message) {
                alarmMessage.postValue("错误: " + message);
            }

            @Override
            public void onAutoModeStarted() {
                isAutoMode.postValue(true);
                modeStatus.postValue("自动轮询 - 每2分钟一轮");
            }

            @Override
            public void onAutoModeStopped() {
                isAutoMode.postValue(false);
                modeStatus.postValue("手动模式");
            }
        });

        // 设备扫描
        bluetoothService.setOnScanListener(new BluetoothReaderService.OnScanListener() {
            @Override
            public void onDeviceFound(BluetoothDevice device) {
                // 实时添加到发现列表
                List<BluetoothDevice> currentList = discoveredDevices.getValue();
                if (currentList == null) {
                    currentList = new ArrayList<>();
                }
                // 去重检查
                boolean alreadyAdded = false;
                for (BluetoothDevice d : currentList) {
                    if (d.getAddress().equals(device.getAddress())) {
                        alreadyAdded = true;
                        break;
                    }
                }
                if (!alreadyAdded) {
                    currentList.add(device);
                    discoveredDevices.postValue(currentList);
                }
            }

            @Override
            public void onScanFinished(List<BluetoothDevice> devices) {
                isScanning.postValue(false);
                // 设备列表已在 onDeviceFound 中持续更新
                // 这里可以更新一次确保完整性
                discoveredDevices.postValue(new ArrayList<>(devices));
                alarmMessage.postValue("扫描完成，发现 " + devices.size() + " 个设备");
            }
        });
    }

    // ========== 设备发现与扫描 ==========

    /**
     * 开始蓝牙扫描
     */
    public void startScan() {
        if (!bluetoothService.isBluetoothAvailable()) {
            alarmMessage.postValue("请先开启蓝牙");
            return;
        }

        // 清空之前的发现列表
        discoveredDevices.postValue(new ArrayList<>());
        isScanning.postValue(true);
        alarmMessage.postValue("正在扫描设备...");

        boolean started = bluetoothService.startDiscovery();
        if (!started) {
            isScanning.postValue(false);
            alarmMessage.postValue("扫描启动失败");
        }
    }

    /**
     * 停止蓝牙扫描
     */
    public void stopScan() {
        bluetoothService.stopDiscovery();
        isScanning.postValue(false);
    }

    /**
     * 加载已配对设备
     */
    public void loadBondedDevices() {
        if (!bluetoothService.isBluetoothAvailable()) return;

        Set<BluetoothDevice> devices = bluetoothService.getBondedDevices();
        if (devices != null) {
            bondedDevices.postValue(new ArrayList<>(devices));
        }
    }

    // ========== 连接管理 ==========

    /**
     * 连接设备
     */
    public boolean connectToDevice(BluetoothDevice device) {
        modeStatus.postValue("正在连接...");
        boolean result = bluetoothService.connectToDevice(device);
        if (!result) {
            modeStatus.postValue("连接失败");
        }
        return result;
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        bluetoothService.disconnect();
        if (Boolean.TRUE.equals(isAutoMode.getValue())) {
            isAutoMode.postValue(false);
        }
    }

    // ========== 手动读取 ==========

    /**
     * 手动读取当前连接设备的数据
     */
    public void manualRead() {
        if (!Boolean.TRUE.equals(connectionStatus.getValue())) {
            alarmMessage.postValue("请先连接蓝牙设备");
            return;
        }
        modeStatus.postValue("手动读取中...");
        bluetoothService.sendCommand("READ");
    }

    /**
     * 手动读取指定地址的电表
     */
    public void manualReadAddress(int address) {
        if (!Boolean.TRUE.equals(connectionStatus.getValue())) {
            alarmMessage.postValue("请先连接蓝牙设备");
            return;
        }
        modeStatus.postValue("手动读取地址 #" + address);
        bluetoothService.sendCommand("READ_ADDR:" + address);
    }

    // ========== 自动轮询 ==========

    /**
     * 启动自动轮询模式
     */
    public void startAutoMode() {
        if (!Boolean.TRUE.equals(connectionStatus.getValue())) {
            alarmMessage.postValue("请先连接蓝牙设备");
            return;
        }
        bluetoothService.startAutoPolling();
    }

    /**
     * 停止自动轮询模式
     */
    public void stopAutoMode() {
        bluetoothService.stopAutoPolling();
    }

    // ========== 报警检查 ==========

    /**
     * 检查报警条件
     */
    private void checkAlarm(MeterData data) {
        if (data.isOffline()) {
            alarmMessage.postValue("⚠ 离线报警：地址#" + data.getMeterAddress() + " 负载已断开");
        } else if (data.isOverLimit()) {
            alarmMessage.postValue("⚠ 超限报警：地址#" + data.getMeterAddress() + " 电流 > 2.0A！");
        } else if (data.isUnderLimit()) {
            alarmMessage.postValue("⚠ 低限报警：地址#" + data.getMeterAddress() + " 电流 < 0.2A");
        } else {
            // 如果当前有其他报警消息，仅在非报警状态下清除
            String currentAlarm = alarmMessage.getValue();
            if (currentAlarm != null && currentAlarm.startsWith("⚠")) {
                // 有新正常数据到达，尝试清除报警
                // 但如果有其他地址还在报警，不清除
                boolean anyAlarm = false;
                Map<Integer, MeterData> allData = allMeterData.getValue();
                if (allData != null) {
                    for (MeterData md : allData.values()) {
                        if (md.isOverLimit() || md.isUnderLimit() || md.isOffline()) {
                            anyAlarm = true;
                            break;
                        }
                    }
                }
                if (!anyAlarm) {
                    alarmMessage.postValue("");
                }
            } else {
                alarmMessage.postValue("");
            }
        }
    }

    // ========== 历史记录 ==========

    /**
     * 加载历史记录
     */
    public void loadHistoryRecords() {
        List<MeterData> records;
        Integer addr = selectedAddress.getValue();
        if (addr != null && addr >= 0) {
            records = databaseHelper.getRecordsByAddress(addr);
        } else {
            records = databaseHelper.getRecentRecords(100);
        }
        historyRecords.postValue(records);
    }

    /**
     * 获取指定地址的历史记录
     */
    public List<MeterData> getRecordsByAddress(int address) {
        return databaseHelper.getRecordsByAddress(address);
    }

    /**
     * 设置选中的电表地址
     */
    public void setSelectedAddress(int address) {
        selectedAddress.postValue(address);
        loadHistoryRecords();
    }

    // ========== 数据操作 ==========

    /**
     * 清除所有数据
     */
    public void clearAllData() {
        databaseHelper.clearAll();
        loadHistoryRecords();
        currentData.postValue(null);
        allMeterData.postValue(new HashMap<>());
    }

    // ========== LiveData Getters ==========

    public LiveData<MeterData> getCurrentData() { return currentData; }
    public LiveData<Boolean> getConnectionStatus() { return connectionStatus; }
    public LiveData<String> getConnectedDeviceName() { return connectedDeviceName; }
    public LiveData<List<BluetoothDevice>> getDiscoveredDevices() { return discoveredDevices; }
    public LiveData<List<BluetoothDevice>> getBondedDevices() { return bondedDevices; }
    public LiveData<List<MeterData>> getHistoryRecords() { return historyRecords; }
    public LiveData<Boolean> getIsAutoMode() { return isAutoMode; }
    public LiveData<Boolean> getIsScanning() { return isScanning; }
    public LiveData<String> getAlarmMessage() { return alarmMessage; }
    public LiveData<String> getModeStatus() { return modeStatus; }
    public LiveData<Map<Integer, MeterData>> getAllMeterData() { return allMeterData; }
    public LiveData<Integer> getSelectedAddress() { return selectedAddress; }

    @Override
    protected void onCleared() {
        super.onCleared();
        bluetoothService.destroy();
    }
}
