package kr.wsarch.callagent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

class Db(ctx: Context) : SQLiteOpenHelper(ctx, "ledger.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE ledger(sha TEXT PRIMARY KEY, path TEXT, name TEXT, call_time TEXT, counterpart TEXT, status TEXT, result TEXT, error TEXT, attempts INTEGER DEFAULT 0, updated INTEGER DEFAULT 0)")
        db.execSQL("CREATE INDEX idx_path ON ledger(path)")
        db.execSQL("CREATE TABLE log(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, msg TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        db.execSQL("DROP TABLE IF EXISTS ledger"); db.execSQL("DROP TABLE IF EXISTS log"); onCreate(db)
    }

    data class Row(val sha: String, val status: String, val attempts: Int, val updated: Long)

    fun has(sha: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM ledger WHERE sha=?", arrayOf(sha)).use { it.moveToFirst() }
    fun byPath(path: String): Row? = readableDatabase.rawQuery("SELECT sha,status,attempts,updated FROM ledger WHERE path=?", arrayOf(path)).use {
        if (it.moveToFirst()) Row(it.getString(0), it.getString(1), it.getInt(2), it.getLong(3)) else null
    }
    fun insert(sha: String, path: String, name: String, callTime: String, who: String, status: String) {
        val v = ContentValues().apply {
            put("sha", sha); put("path", path); put("name", name); put("call_time", callTime)
            put("counterpart", who); put("status", status); put("updated", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("ledger", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun update(sha: String, status: String, result: String? = null, error: String? = null, bumpAttempt: Boolean = false) {
        val v = ContentValues().apply { put("status", status); put("updated", System.currentTimeMillis()); if (result != null) put("result", result); if (error != null) put("error", error) }
        writableDatabase.update("ledger", v, "sha=?", arrayOf(sha))
        if (bumpAttempt) writableDatabase.execSQL("UPDATE ledger SET attempts=attempts+1 WHERE sha=?", arrayOf(sha))
    }
    fun count(status: String): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM ledger WHERE status=?", arrayOf(status)).use { it.moveToFirst(); it.getInt(0) }
    fun results(limit: Int): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        readableDatabase.rawQuery("SELECT result FROM ledger WHERE status='done' AND result IS NOT NULL ORDER BY call_time DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) try { out.add(JSONObject(c.getString(0))) } catch (e: Exception) { }
        }
        return out
    }
    fun retryErrors(): Int {
        val v = ContentValues().apply { put("status", "retry"); put("attempts", 0) }
        return writableDatabase.update("ledger", v, "status IN ('error','retry')", null)
    }
    fun log(msg: String) {
        writableDatabase.execSQL("INSERT INTO log(ts,msg) VALUES(?,?)", arrayOf(System.currentTimeMillis(), msg))
        writableDatabase.execSQL("DELETE FROM log WHERE id NOT IN (SELECT id FROM log ORDER BY id DESC LIMIT 200)")
    }
    fun logs(limit: Int): List<Pair<Long, String>> {
        val out = mutableListOf<Pair<Long, String>>()
        readableDatabase.rawQuery("SELECT ts,msg FROM log ORDER BY id DESC LIMIT ?", arrayOf(limit.toString())).use { c -> while (c.moveToNext()) out.add(c.getLong(0) to c.getString(1)) }
        return out
    }
}
