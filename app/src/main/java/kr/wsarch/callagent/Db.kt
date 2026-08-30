package kr.wsarch.callagent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.time.LocalDateTime

class Db(ctx: Context) : SQLiteOpenHelper(ctx, "ledger.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE ledger(sha TEXT PRIMARY KEY, path TEXT, name TEXT, call_time TEXT, counterpart TEXT, status TEXT, result TEXT, detected TEXT, error TEXT)")
        db.execSQL("CREATE INDEX idx_path ON ledger(path)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {}

    fun has(sha: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM ledger WHERE sha=?", arrayOf(sha)).use { it.moveToFirst() }
    fun hasPath(path: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM ledger WHERE path=?", arrayOf(path)).use { it.moveToFirst() }

    fun insert(sha: String, path: String, name: String, callTime: String, who: String, status: String) {
        val v = ContentValues().apply {
            put("sha", sha); put("path", path); put("name", name); put("call_time", callTime)
            put("counterpart", who); put("status", status); put("detected", LocalDateTime.now().toString())
        }
        writableDatabase.insertWithOnConflict("ledger", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun update(sha: String, status: String, result: String? = null, error: String? = null) {
        val v = ContentValues().apply { put("status", status); if (result != null) put("result", result); if (error != null) put("error", error) }
        writableDatabase.update("ledger", v, "sha=?", arrayOf(sha))
    }
    fun count(status: String): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM ledger WHERE status=?", arrayOf(status)).use { it.moveToFirst(); it.getInt(0) }
    fun results(limit: Int): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        readableDatabase.rawQuery("SELECT result FROM ledger WHERE status='done' AND result IS NOT NULL ORDER BY call_time DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) try { out.add(JSONObject(c.getString(0))) } catch (e: Exception) { }
        }
        return out
    }
    fun retryErrors(): Int = writableDatabase.delete("ledger", "status='error'", null)
}
