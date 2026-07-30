package com.meterreader.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.meterreader.model.MeterData;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据库帮助类
 * 用于存储和读取历史数据，支持掉电保护
 */
public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "meter_reader.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE_NAME = "meter_records";

    private static final String COL_ID = "id";
    private static final String COL_TIMESTAMP = "timestamp";
    private static final String COL_METER_ADDR = "meter_address";
    private static final String COL_CURRENT = "current_value";
    private static final String COL_OVER_LIMIT = "is_over_limit";
    private static final String COL_UNDER_LIMIT = "is_under_limit";
    private static final String COL_OFFLINE = "is_offline";

    private static DatabaseHelper instance;

    public static synchronized DatabaseHelper getInstance(Context context) {
        if (instance == null) {
            instance = new DatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    private DatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createTable = "CREATE TABLE " + TABLE_NAME + " ("
                + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_TIMESTAMP + " INTEGER NOT NULL, "
                + COL_METER_ADDR + " INTEGER NOT NULL, "
                + COL_CURRENT + " REAL NOT NULL, "
                + COL_OVER_LIMIT + " INTEGER DEFAULT 0, "
                + COL_UNDER_LIMIT + " INTEGER DEFAULT 0, "
                + COL_OFFLINE + " INTEGER DEFAULT 0)";
        db.execSQL(createTable);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
        onCreate(db);
    }

    /**
     * 插入一条记录
     */
    public long insertRecord(MeterData data) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_TIMESTAMP, data.getTimestamp());
        values.put(COL_METER_ADDR, data.getMeterAddress());
        values.put(COL_CURRENT, data.getCurrentValue());
        values.put(COL_OVER_LIMIT, data.isOverLimit() ? 1 : 0);
        values.put(COL_UNDER_LIMIT, data.isUnderLimit() ? 1 : 0);
        values.put(COL_OFFLINE, data.isOffline() ? 1 : 0);
        return db.insert(TABLE_NAME, null, values);
    }

    /**
     * 获取所有记录（按时间倒序）
     */
    public List<MeterData> getAllRecords() {
        return getRecords(null, null, COL_TIMESTAMP + " DESC");
    }

    /**
     * 获取指定电流表的记录
     */
    public List<MeterData> getRecordsByAddress(int address) {
        return getRecords(COL_METER_ADDR + " = ?",
                new String[]{String.valueOf(address)},
                COL_TIMESTAMP + " DESC");
    }

    /**
     * 获取最近N条记录
     */
    public List<MeterData> getRecentRecords(int limit) {
        SQLiteDatabase db = getReadableDatabase();
        List<MeterData> records = new ArrayList<>();

        Cursor cursor = db.query(TABLE_NAME, null, null, null,
                null, null, COL_TIMESTAMP + " DESC", String.valueOf(limit));
        try {
            while (cursor.moveToNext()) {
                records.add(cursorToMeterData(cursor));
            }
        } finally {
            cursor.close();
        }
        return records;
    }

    /**
     * 获取记录总数
     */
    public int getRecordCount() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_NAME, null);
        try {
            cursor.moveToFirst();
            return cursor.getInt(0);
        } finally {
            cursor.close();
        }
    }

    /**
     * 删除旧记录，保留最新的N条
     */
    public void trimRecords(int keepCount) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM " + TABLE_NAME + " WHERE " + COL_ID + " NOT IN " +
                "(SELECT " + COL_ID + " FROM " + TABLE_NAME +
                " ORDER BY " + COL_TIMESTAMP + " DESC LIMIT " + keepCount + ")");
    }

    private List<MeterData> getRecords(String selection, String[] selectionArgs, String orderBy) {
        SQLiteDatabase db = getReadableDatabase();
        List<MeterData> records = new ArrayList<>();

        Cursor cursor = db.query(TABLE_NAME, null, selection, selectionArgs,
                null, null, orderBy);
        try {
            while (cursor.moveToNext()) {
                records.add(cursorToMeterData(cursor));
            }
        } finally {
            cursor.close();
        }
        return records;
    }

    private MeterData cursorToMeterData(Cursor cursor) {
        MeterData data = new MeterData();
        data.setId(cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)));
        data.setTimestamp(cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP)));
        data.setMeterAddress(cursor.getInt(cursor.getColumnIndexOrThrow(COL_METER_ADDR)));
        data.setCurrentValue(cursor.getFloat(cursor.getColumnIndexOrThrow(COL_CURRENT)));
        data.setOverLimit(cursor.getInt(cursor.getColumnIndexOrThrow(COL_OVER_LIMIT)) == 1);
        data.setUnderLimit(cursor.getInt(cursor.getColumnIndexOrThrow(COL_UNDER_LIMIT)) == 1);
        data.setOffline(cursor.getInt(cursor.getColumnIndexOrThrow(COL_OFFLINE)) == 1);
        return data;
    }

    /**
     * 清除所有数据
     */
    public void clearAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_NAME, null, null);
    }
}
