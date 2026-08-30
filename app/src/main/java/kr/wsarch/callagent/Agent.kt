package kr.wsarch.callagent

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

object Agent {
    const val TAG = "CallAgent"
    private val EXT = setOf("m4a", "mp3", "wav", "amr", "3gp", "aac", "ogg")
    val DEFAULT_DIRS = listOf("/storage/emulated/0/Recordings/Call", "/storage/emulated/0/Call", "/storage/emulated/0/TPhoneCallRecords")
    private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun isAudio(f: File) = f.extension.lowercase() in EXT
    fun dirs(ctx: Context): List<String> =
        Prefs.get(ctx, "dirs").lines().map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { DEFAULT_DIRS }

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<ScanWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("scan", ExistingPeriodicWorkPolicy.UPDATE, req)
        Prefs.set(ctx, "scheduled", "1")
    }

    fun candidates(ctx: Context): List<File> = dirs(ctx).map { File(it) }.filter { it.isDirectory }.flatMap { d ->
        d.walkTopDown().filter { it.isFile && isAudio(it) && it.length() >= 20_000 && System.currentTimeMillis() - it.lastModified() > 90_000 }.toList()
    }

    /** 설치 이전 파일은 분석 제외로 등록 (해시 없이 경로만) */
    fun baseline(ctx: Context): Int {
        val db = Db(ctx); var n = 0
        for (f in candidates(ctx)) if (!db.hasPath(f.absolutePath)) { db.insert("skip:" + f.absolutePath, f.absolutePath, f.name, "", "", "skipped"); n++ }
        return n
    }

    /** 반환: (처리 건수, 남은 건수) — WorkManager 10분 제한 때문에 1회 최대 maxFiles건 */
    fun scan(ctx: Context, maxFiles: Int = 2): Pair<Int, Int> {
        val db = Db(ctx); var done = 0; var remaining = 0
        for (f in candidates(ctx).sortedBy { it.lastModified() }) {
            if (db.hasPath(f.absolutePath)) continue
            if (done >= maxFiles) { remaining++; continue }
            val sha = sha256(f)
            if (db.has(sha)) { db.insert("dup:" + f.absolutePath, f.absolutePath, f.name, "", "", "dup"); continue }
            process(ctx, db, f, sha); done++
        }
        return done to remaining
    }

    private fun process(ctx: Context, db: Db, f: File, sha: String) {
        val (ct, who) = parseName(f)
        db.insert(sha, f.absolutePath, f.name, ct.format(FMT), who, "processing")
        Log.i(TAG, "신규: ${f.name} | $who | $ct")
        try {
            val text = Analyzer.stt(ctx, f)
            val res = Analyzer.analyze(ctx, text, ct, who)
            res.put("_transcript", text)
            val msg = Analyzer.format(res)
            db.update(sha, "done", result = res.toString())
            Notify.show(ctx, "통화분석: ${res.optString("통화상대방")}", msg, sha.hashCode())
            Notify.kakao(ctx, msg)
        } catch (e: Exception) {
            Log.e(TAG, "처리 오류", e)
            db.update(sha, "error", error = e.message?.take(500))
            Notify.show(ctx, "통화분석 오류", "${f.name}\n${e.message}".take(400), sha.hashCode())
        }
    }

    fun maybeDaily(ctx: Context) {
        val now = LocalDateTime.now(); val today = now.toLocalDate().toString()
        if (now.hour >= 8 && Prefs.get(ctx, "daily_sent") != today) { daily(ctx); Prefs.set(ctx, "daily_sent", today) }
    }

    fun daily(ctx: Context) {
        val order = mapOf("긴급" to 0, "중요" to 1, "일반" to 2)
        val todos = mutableListOf<Triple<Int, String, String>>()
        for (r in Db(ctx).results(60)) {
            val a = r.optJSONArray("내가할일") ?: continue
            for (i in 0 until a.length()) {
                val t = a.optJSONObject(i) ?: continue
                val p = t.optString("우선순위").ifBlank { "일반" }
                val d = t.optString("기한").ifBlank { "기한 미확인" }
                todos.add(Triple(order[p] ?: 3, d, "☐ [$p] ${t.optString("내용")} — ${r.optString("통화상대방")} (기한 $d)"))
            }
        }
        todos.sortWith(compareBy({ it.first }, { it.second }))
        val msg = if (todos.isEmpty()) "등록된 할 일 없음"
        else "📋 ${LocalDate.now().format(DateTimeFormatter.ofPattern("MM/dd"))} 통화 기반 업무목록\n" + todos.take(30).joinToString("\n") { it.third }
        Notify.show(ctx, "일일 업무목록", msg, 2)
        Notify.kakao(ctx, msg)
    }

    private fun parseName(f: File): Pair<LocalDateTime, String> {
        val stem = f.nameWithoutExtension
        var ct: LocalDateTime? = null
        Regex("(\\d{6})[_\\-\\s]?(\\d{4,6})").find(stem)?.let { m ->
            try { ct = LocalDateTime.parse(m.groupValues[1] + m.groupValues[2].padEnd(6, '0'), DateTimeFormatter.ofPattern("yyMMddHHmmss")) } catch (e: Exception) { }
        }
        val t = ct ?: LocalDateTime.ofInstant(Instant.ofEpochMilli(f.lastModified()), ZoneId.systemDefault())
        var who = stem.replace(Regex("^(통화\\s*녹음|Call\\s*recording|녹음)\\s*", RegexOption.IGNORE_CASE), "")
        who = who.split(Regex("[_\\-]\\d{6}"))[0].trim(' ', '_', '-')
        if (who.isBlank()) who = "미확인"
        return t to who
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 20); var n: Int
            while (ins.read(buf).also { n = it } > 0) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
