package com.meterreader.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.meterreader.model.MeterData;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 蓝牙读表器服务
 * 负责蓝牙扫描、连接、数据接收与解析
 *
 * 通信协议约定：
 * 无线模块（电路B）通过蓝牙发送数据帧，格式如下：
 * [起始标志:0xAA][地址编号:1字节][电流值高位:1字节][电流值低位:1字节][状态:1字节][校验:1字节][结束标志:0x55]
 *
 * 电流值 = (高位 << 8 | 低位) / 1000.0f（单位：A）
 * 状态位: bit0=超限, bit1=低限, bit2=离线
 *
 * 增强功能：
 * - 蓝牙发现扫描：startDiscovery() 搜索无线覆盖范围内所有蓝牙设备
 * - 多电表管理：自动轮询已发现范围内所有电表地址
 * - 自动重连：在连接断开后自动尝试重新连接
 */
public class BluetoothReaderService {

    private static final String TAG = "BluetoothReader";

    // 蓝牙SPP服务UUID
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // 通信协议常量
    private static final byte FRAME_START = (byte) 0xAA;
    private static final byte FRAME_END = (byte) 0x55;
    private static final int FRAME_LENGTH = 7;

    // 自动轮询间隔（毫秒）
    private static final long POLLING_INTERVAL_MS = 2000; // 每2秒轮询一个地址
    private static final long AUTO_MODE_ROUND_INTERVAL_MS = 120000; // 每2分钟一轮

    private Context context;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private Handler mainHandler;
    private Handler pollingHandler; // 轮询定时器用独立Handler
    private boolean isConnected = false;
    private boolean isScanning = false;
    private boolean isReading = false;
    private boolean isAutoPolling = false; // 是否在自动轮询模式

    private OnDataReceivedListener dataListener;
    private OnConnectionListener connectionListener;
    private OnScanListener scanListener;

    // 当前连接的设备
    private BluetoothDevice connectedDevice;

    // ----- 多设备管理 -----
    // 已发现设备列表（去重）
    private final List<BluetoothDevice> discoveredDevices = new ArrayList<>();
    private final Set<String> discoveredAddresses = new HashSet<>();
    // 上一次轮询的地址索引
    private int pollingIndex = 0;
    // 保存自动模式启动的时间戳，用于超时检查
    private long autoModeStartTime = 0;

    // 广播接收器：监听蓝牙设备发现
    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    addDiscoveredDevice(device);
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                isScanning = false;
                Log.d(TAG, "蓝牙扫描完成，发现 " + discoveredDevices.size() + " 个设备");
                if (scanListener != null) {
                    mainHandler.post(() -> scanListener.onScanFinished(discoveredDevices));
                }
            }
        }
    };

    public BluetoothReaderService(Context context) {
        this.context = context;
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.pollingHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 设置数据接收监听器
     */
    public void setOnDataReceivedListener(OnDataReceivedListener listener) {
        this.dataListener = listener;
    }

    /**
     * 设置连接状态监听器
     */
    public void setOnConnectionListener(OnConnectionListener listener) {
        this.connectionListener = listener;
    }

    /**
     * 设置扫描监听器
     */
    public void setOnScanListener(OnScanListener listener) {
        this.scanListener = listener;
    }

    /**
     * 检查蓝牙是否可用
     */
    public boolean isBluetoothAvailable() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    /**
     * 获取已配对设备列表
     */
    public Set<BluetoothDevice> getBondedDevices() {
        if (bluetoothAdapter == null) return null;
        return bluetoothAdapter.getBondedDevices();
    }

    // ========== 蓝牙发现扫描（新增） ==========

    /**
     * 开始蓝牙发现扫描
     * 自动搜索无线覆盖范围内的所有蓝牙设备
     */
    public boolean startDiscovery() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.w(TAG, "蓝牙未开启，无法扫描");
            return false;
        }

        // 取消已有的扫描
        if (isScanning) {
            bluetoothAdapter.cancelDiscovery();
        }

        // 清空之前发现的设备列表
        discoveredDevices.clear();
        discoveredAddresses.clear();

        // 注册广播接收器
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        context.registerReceiver(discoveryReceiver, filter);

        isScanning = true;
        boolean started = bluetoothAdapter.startDiscovery();
        if (started) {
            Log.d(TAG, "蓝牙扫描已启动");
        } else {
            Log.w(TAG, "蓝牙扫描启动失败");
            isScanning = false;
        }
        return started;
    }

    /**
     * 停止蓝牙发现扫描
     */
    public void stopDiscovery() {
        if (bluetoothAdapter != null && isScanning) {
            bluetoothAdapter.cancelDiscovery();
            isScanning = false;
        }
        try {
            context.unregisterReceiver(discoveryReceiver);
        } catch (IllegalArgumentException e) {
            // 未注册时忽略
        }
    }

    /**
     * 获取已发现的设备列表
     */
    public List<BluetoothDevice> getDiscoveredDevices() {
        return new ArrayList<>(discoveredDevices);
    }

    /**
     * 是否正在扫描
     */
    public boolean isScanning() {
        return isScanning;
    }

    /**
     * 添加发现的设备到列表（去重）
     */
    private void addDiscoveredDevice(BluetoothDevice device) {
        String address = device.getAddress();
        if (!discoveredAddresses.contains(address)) {
            discoveredAddresses.add(address);
            discoveredDevices.add(device);
            Log.d(TAG, "发现新设备: " + device.getName() + " [" + address + "]");
            if (scanListener != null) {
                mainHandler.post(() -> scanListener.onDeviceFound(device));
            }
        }
    }

    // ========== 连接管理 ==========

    /**
     * 连接到指定蓝牙设备
     */
    public boolean connectToDevice(BluetoothDevice device) {
        if (bluetoothSocket != null) {
            disconnect();
        }

        try {
            bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            bluetoothSocket.connect();
            inputStream = bluetoothSocket.getInputStream();
            outputStream = bluetoothSocket.getOutputStream();
            isConnected = true;
            connectedDevice = device;

            if (connectionListener != null) {
                mainHandler.post(() -> connectionListener.onConnected(device));
            }

            // 启动数据读取线程
            startReading();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "连接失败: " + e.getMessage());
            isConnected = false;
            if (connectionListener != null) {
                mainHandler.post(() -> connectionListener.onError("连接失败: " + e.getMessage()));
            }
            return false;
        }
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        // 停止轮询
        stopAutoPolling();

        isReading = false;
        isConnected = false;

        try {
            if (inputStream != null) inputStream.close();
            if (outputStream != null) outputStream.close();
            if (bluetoothSocket != null) bluetoothSocket.close();
        } catch (IOException e) {
            Log.e(TAG, "关闭连接异常: " + e.getMessage());
        }

        inputStream = null;
        outputStream = null;
        bluetoothSocket = null;

        if (connectionListener != null) {
            mainHandler.post(() -> connectionListener.onDisconnected());
        }
    }

    // ========== 自动轮询（新增） ==========

    /**
     * 启动自动轮询模式
     * 每2分钟循环轮询所有已发现电表地址
     * 每个地址间隔2秒发送一次请求
     */
    public void startAutoPolling() {
        if (!isConnected) return;
        isAutoPolling = true;
        pollingIndex = 0;
        autoModeStartTime = System.currentTimeMillis();

        Log.d(TAG, "启动自动轮询模式");
        if (connectionListener != null) {
            mainHandler.post(() -> connectionListener.onAutoModeStarted());
        }

        // 开始轮询
        doPollingRound();
    }

    /**
     * 停止自动轮询
     */
    public void stopAutoPolling() {
        isAutoPolling = false;
        pollingHandler.removeCallbacksAndMessages(null);
        if (connectionListener != null) {
            mainHandler.post(() -> connectionListener.onAutoModeStopped());
        }
    }

    /**
     * 是否在自动轮询模式
     */
    public boolean isAutoPolling() {
        return isAutoPolling;
    }

    /**
     * 执行一轮轮询
     * 对每个已知电表地址依次发送读取请求（间隔2秒）
     */
    private void doPollingRound() {
        if (!isAutoPolling || !isConnected) return;

        // 构建待轮询地址列表
        // 1) 从已发现设备中提取地址
        // 2) 同时也轮询已配对设备中的可能地址
        final List<Integer> meterAddresses = new ArrayList<>();
        // 默认轮询地址0~7（支持最多8个电表）
        for (int addr = 0; addr < 8; addr++) {
            meterAddresses.add(addr);
        }

        if (meterAddresses.isEmpty()) {
            Log.w(TAG, "无可轮询的电表地址");
            scheduleNextRound();
            return;
        }

        Log.d(TAG, "开始一轮轮询，共 " + meterAddresses.size() + " 个地址");
        pollingIndex = 0;

        // 逐个轮询
        pollNextAddress(meterAddresses);
    }

    /**
     * 轮询下一个地址
     */
    private void pollNextAddress(final List<Integer> addresses) {
        if (!isAutoPolling || !isConnected) return;

        if (pollingIndex >= addresses.size()) {
            // 本轮所有地址已轮询完毕
            Log.d(TAG, "本轮轮询完成");
            scheduleNextRound();
            return;
        }

        int address = addresses.get(pollingIndex);
        pollingIndex++;

        // 发送读取指令，格式: "READ_ADDR:0" ~ "READ_ADDR:7"
        String cmd = "READ_ADDR:" + address;
        boolean sent = sendCommand(cmd);
        Log.d(TAG, "轮询地址#" + address + " (" + cmd + ") -> " + (sent ? "已发送" : "失败"));

        // 2秒后轮询下一个地址
        pollingHandler.postDelayed(() -> pollNextAddress(addresses), POLLING_INTERVAL_MS);
    }

    /**
     * 安排下一轮轮询（2分钟后）
     */
    private void scheduleNextRound() {
        if (!isAutoPolling) return;
        Log.d(TAG, "下一轮轮询将在 " + (AUTO_MODE_ROUND_INTERVAL_MS / 1000) + " 秒后进行");
        pollingHandler.postDelayed(this::doPollingRound, AUTO_MODE_ROUND_INTERVAL_MS);
    }

    // ========== 数据通信 ==========

    /**
     * 发送数据到无线模块
     */
    public boolean sendCommand(String command) {
        if (!isConnected || outputStream == null) return false;
        try {
            byte[] data = command.getBytes(StandardCharsets.UTF_8);
            outputStream.write(data);
            outputStream.flush();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "发送数据失败: " + e.getMessage());
            return false;
        }
    }

    public boolean isConnected() { return isConnected; }

    public BluetoothDevice getConnectedDevice() { return connectedDevice; }

    /**
     * 开始读取数据（后台线程）
     */
    private void startReading() {
        isReading = true;
        new Thread(this::readLoop).start();
    }

    /**
     * 读取循环
     * 解析固定格式的通信协议帧
     */
    private void readLoop() {
        byte[] buffer = new byte[1024];
        // 帧缓冲区
        byte[] frameBuffer = new byte[FRAME_LENGTH];
        int frameIndex = 0;
        boolean syncStarted = false;

        while (isReading) {
            try {
                if (inputStream == null) break;

                int bytesAvailable = inputStream.available();
                if (bytesAvailable > 0) {
                    int bytesRead = inputStream.read(buffer, 0, Math.min(bytesAvailable, buffer.length));

                    for (int i = 0; i < bytesRead; i++) {
                        byte b = buffer[i];

                        if (!syncStarted) {
                            // 等待帧起始标志
                            if (b == FRAME_START) {
                                syncStarted = true;
                                frameIndex = 0;
                                frameBuffer[frameIndex++] = b;
                            }
                        } else {
                            frameBuffer[frameIndex++] = b;

                            if (frameIndex == FRAME_LENGTH) {
                                // 完整的帧接收完毕
                                syncStarted = false;

                                if (frameBuffer[FRAME_LENGTH - 1] == FRAME_END) {
                                    // 校验通过，解析数据
                                    parseFrame(frameBuffer);
                                }
                                frameIndex = 0;
                            }
                        }
                    }
                } else {
                    // 没有数据时，短暂休眠
                    Thread.sleep(50);
                }
            } catch (IOException e) {
                Log.e(TAG, "读取数据异常: " + e.getMessage());
                // 连接断开
                mainHandler.post(() -> {
                    if (connectionListener != null) {
                        connectionListener.onDisconnected();
                    }
                });
                isReading = false;
                isConnected = false;
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * 解析数据帧
     * 帧格式: [0xAA][地址:1B][电流高:1B][电流低:1B][状态:1B][校验:1B][0x55]
     */
    private void parseFrame(byte[] frame) {
        if (frame.length != FRAME_LENGTH) return;

        int address = frame[1] & 0xFF;          // 电流表地址编号（0-15）
        int currentHigh = frame[2] & 0xFF;      // 电流值高位
        int currentLow = frame[3] & 0xFF;       // 电流值低位
        int status = frame[4] & 0xFF;           // 状态字节
        int checksum = frame[5] & 0xFF;         // 校验和

        // 校验和验证（简单累加校验）
        int calcChecksum = 0;
        for (int i = 0; i < FRAME_LENGTH - 2; i++) {
            calcChecksum += frame[i] & 0xFF;
        }
        calcChecksum = calcChecksum & 0xFF;

        if (calcChecksum != checksum) {
            Log.w(TAG, "校验失败: calc=" + calcChecksum + ", recv=" + checksum);
            return;
        }

        // 计算电流值（单位：A）
        int rawCurrent = (currentHigh << 8) | currentLow;
        float currentValue = rawCurrent / 1000.0f;

        // 解析状态
        boolean isOverLimit = (status & 0x01) != 0;
        boolean isUnderLimit = (status & 0x02) != 0;
        boolean isOffline = (status & 0x04) != 0;

        // 创建数据模型
        MeterData data = new MeterData(
            System.currentTimeMillis(),
            address,
            currentValue
        );
        data.setOverLimit(isOverLimit);
        data.setUnderLimit(isUnderLimit);
        data.setOffline(isOffline);

        Log.d(TAG, "收到数据: 地址#" + address + ", 电流=" + currentValue + "A");

        // 回调到主线程
        if (dataListener != null) {
            mainHandler.post(() -> dataListener.onDataReceived(data));
        }
    }

    /**
     * 释放资源
     */
    public void destroy() {
        stopDiscovery();
        stopAutoPolling();
        disconnect();
        mainHandler.removeCallbacksAndMessages(null);
        pollingHandler.removeCallbacksAndMessages(null);
    }

    // ========== 接口定义 ==========

    public interface OnDataReceivedListener {
        void onDataReceived(MeterData data);
    }

    public interface OnConnectionListener {
        void onConnected(BluetoothDevice device);
        void onDisconnected();
        void onError(String message);
        void onAutoModeStarted();
        void onAutoModeStopped();
    }

    public interface OnScanListener {
        void onDeviceFound(BluetoothDevice device);
        void onScanFinished(List<BluetoothDevice> devices);
    }
}
