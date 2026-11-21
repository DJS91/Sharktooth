package com.sphy.airconcontroller.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.sphy.airconcontroller.ui.DashboardHistory
import org.json.JSONObject

internal class DashboardHistoryStorage(context: Context, databaseName: String = "dashboard_history.db") :
    SQLiteOpenHelper(context.applicationContext, databaseName, null, 1), DashboardHistory.Storage {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE samples (time INTEGER PRIMARY KEY, readings TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported dashboard history database upgrade: $oldVersion to $newVersion")
    }

    override fun load(start: Long, end: Long): List<DashboardHistory.Sample> {
        val result = ArrayList<DashboardHistory.Sample>()
        readableDatabase.rawQuery(
            "SELECT time, readings FROM samples WHERE time >= ? AND time < ? ORDER BY time",
            arrayOf(start.toString(), end.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val json = JSONObject(cursor.getString(1))
                val values = json.keys().asSequence().associateWith {
                    if (json.isNull(it)) null else json.getDouble(it)
                }
                result += DashboardHistory.Sample(cursor.getLong(0), values)
            }
        }
        return result
    }

    override fun save(start: Long, sample: DashboardHistory.Sample) {
        val json = JSONObject()
        sample.values.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM samples WHERE time < ? OR time > ?", arrayOf(start, sample.time))
            db.execSQL("INSERT INTO samples (time, readings) VALUES (?, ?)", arrayOf(sample.time, json.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
