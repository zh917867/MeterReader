package com.meterreader.model;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 电流表数据模型
 * 包含读取时间、电流表编号、电流值、是否超限等信息
 */
public class MeterData {
    private long id;
    private long timestamp;       // 读取时间戳
    private int meterAddress;     // 电流表地址编号（0-15，4位编码开关）
    private float currentValue;   // 电流有效值（A）
    private boolean isOverLimit;  // 是否超限（>2A）
    private boolean isUnderLimit; // 是否低限（<0.2A）
    private boolean isOffline;    // 是否离线

    public MeterData() {}

    public MeterData(long timestamp, int meterAddress, float currentValue) {
        this.timestamp = timestamp;
        this.meterAddress = meterAddress;
        this.currentValue = currentValue;
        this.isOverLimit = currentValue > 2.0f;
        this.isUnderLimit = currentValue < 0.2f;
        this.isOffline = false;
    }

    // Getters and Setters
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public int getMeterAddress() { return meterAddress; }
    public void setMeterAddress(int meterAddress) { this.meterAddress = meterAddress; }

    public float getCurrentValue() { return currentValue; }
    public void setCurrentValue(float currentValue) {
        this.currentValue = currentValue;
        this.isOverLimit = currentValue > 2.0f;
        this.isUnderLimit = currentValue < 0.2f;
    }

    public boolean isOverLimit() { return isOverLimit; }
    public void setOverLimit(boolean overLimit) { isOverLimit = overLimit; }

    public boolean isUnderLimit() { return isUnderLimit; }
    public void setUnderLimit(boolean underLimit) { isUnderLimit = underLimit; }

    public boolean isOffline() { return isOffline; }
    public void setOffline(boolean offline) { isOffline = offline; }

    /**
     * 获取格式化的时间字符串
     */
    public String getFormattedTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
        return sdf.format(new Date(timestamp));
    }

    /**
     * 获取格式化的简短时间字符串
     */
    public String getShortTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
        return sdf.format(new Date(timestamp));
    }

    /**
     * 获取电流值显示字符串
     */
    public String getCurrentDisplay() {
        return String.format(Locale.CHINA, "%.3f A", currentValue);
    }

    /**
     * 获取状态描述
     */
    public String getStatusText() {
        if (isOffline) return "离线";
        if (isOverLimit) return "超限";
        if (isUnderLimit) return "低限";
        return "正常";
    }

    /**
     * 获取完整信息字符串，用于显示
     */
    public String getSummary() {
        return String.format(Locale.CHINA,
            "[#%d] %s | %s | %s",
            meterAddress, getShortTime(), getCurrentDisplay(), getStatusText());
    }
}
