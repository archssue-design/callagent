package kr.wsarch.callagent

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var tvStatus: TextView
    private lateinit var tvResults: TextView
    private lateinit var tvLog: TextView
    private lateinit var etGroq: EditText
    private lateinit var etOpenai: EditText
    private lateinit var etAnthropic: EditText
    private lateinit var etName: EditText
    private lateinit var etDirs: EditText
    private lateinit var etKakaoRest: EditText
    private lateinit var etKakaoRefresh: EditText
    private lateinit var cbKakao: CheckBox
    private lateinit var cbOld: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvStatus = findViewById(R.id.tvStatus); tvResults = findViewById(R.id.tvResults); tvLog = findViewById(R.id.tvLog)
        etGroq = findViewById(R.id.etGroq); etOpenai = findViewById(R.id.etOpenai); etAnthropic = findViewById(R.id.etAnthropic)
        etName = findViewById(R.id.etName); etDirs = findViewById(R.id.etDirs)
        etKakaoRest = findViewById(R.id.etKakaoRest); etKakaoRefresh = findViewById(R.id.etKakaoRefresh)
        cbKakao = findViewById(R.id.cbKakao); cbOld = findViewById(R.id.cbOld)
        Agent.cutoff(this)  // 설치 시각 기록 (이전 파일 제외 기준)
        if (Build.VERSION.SDK_INT >= 33) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        load()
        findViewById<Button>(R.id.btnPerm).setOnClickListener { requestAllFiles() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestBattery() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { testAndStart() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save(); toast("저장 완료") }
        findViewById<Button>(R.id.btnScan).setOnClickListener { save(); runNow() }
        findViewById<Button>(R.id.btnDaily).setOnClickListener { save(); Thread { Agent.daily(this) }.start(); toast("일일 목록 전송") }
        findViewById<Button>(R.id.btnRetry).setOnClickListener { save(); val n = Db(this).retryErrors(); toast("${n}건 재시도 등록"); runNow() }
        cbOld.setOnCheckedChangeListener { _, checked -> Prefs.set(this, "analyze_old", if (checked) "1" else "0"); refresh() }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun load() {
        etGroq.setText(Prefs.get(this, "groq_key")); etOpenai.setText(Prefs.get(this, "openai_key")); etAnthropic.setText(Prefs.get(this, "anthropic_key"))
        etName.setText(Prefs.get(this, "my_name", "이하정"))
        etDirs.setText(Prefs.get(this, "dirs").ifBlank { Agent.DEFAULT_DIRS.filter { File(it).isDirectory }.ifEmpty { Agent.DEFAULT_DIRS }.joinToString("\n") })
        etKakaoRest.setText(Prefs.get(this, "kakao_rest")); etKakaoRefresh.setText(Prefs.get(this, "kakao_refresh"))
        cbKakao.isChecked = Prefs.get(this, "kakao_on") == "1"
        cbOld.isChecked = Prefs.get(this, "analyze_old") == "1"
        findViewById<RadioButton>(if (Prefs.get(this, "stt_provider", "groq") == "openai") R.id.rbSttOpenai else R.id.rbSttGroq).isChecked = true
        findViewById<RadioButton>(if (Prefs.get(this, "llm_provider", "groq") == "claude") R.id.rbLlmClaude else R.id.rbLlmGroq).isChecked = true
    }

    private fun save() {
        Prefs.set(this, "groq_key", etGroq.text.toString().trim())
        Prefs.set(this, "openai_key", etOpenai.text.toString().trim())
        Prefs.set(this, "anthropic_key", etAnthropic.text.toString().trim())
        Prefs.set(this, "stt_provider", if (findViewById<RadioButton>(R.id.rbSttOpenai).isChecked) "openai" else "groq")
        Prefs.set(this, "llm_provider", if (findViewById<RadioButton>(R.id.rbLlmClaude).isChecked) "claude" else "groq")
        Prefs.set(this, "my_name", etName.text.toString().trim().ifBlank { "이하정" })
        Prefs.set(this, "dirs", etDirs.text.toString())
        Prefs.set(this, "kakao_rest", etKakaoRest.text.toString().trim())
        Prefs.set(this, "kakao_refresh", etKakaoRefresh.text.toString().trim())
        Prefs.set(this, "kakao_on", if (cbKakao.isChecked && etKakaoRest.text.isNotBlank()) "1" else "0")
        Prefs.set(this, "analyze_old", if (cbOld.isChecked) "1" else "0")
        refresh()
    }

    private fun testAndStart() {
        save()
        val key = Prefs.get(this, "groq_key")
        if (Prefs.get(this, "stt_provider") == "openai" && Prefs.get(this, "llm_provider") == "claude") { Agent.schedule(this); toast("자동감시 시작"); refresh(); return }
        if (key.isBlank()) { toast("Groq 키를 먼저 붙여넣으세요"); return }
        toast("키 확인 중…")
        Thread {
            val err = Analyzer.testGroqKey(key)
            runOnUiThread {
                if (err == null) { Agent.schedule(this); Agent.log(this, "Groq 키 확인 OK, 자동감시 시작"); toast("키 정상. 자동감시 시작됨") }
                else { Agent.log(this, "Groq 키 확인 실패: $err"); toast("키 오류: $err") }
                refresh()
            }
        }.start()
    }

    private fun runNow() {
        val req = OneTimeWorkRequestBuilder<ScanWorker>().build()
        val wm = WorkManager.getInstance(this)
        wm.enqueue(req)
        wm.getWorkInfoByIdLiveData(req.id).observe(this) { if (it != null && it.state.isFinished) refresh() }
        toast("스캔 시작 (백그라운드, 1~3분)")
    }

    private fun requestAllFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (e: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 2)
    }

    private fun requestBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) toast("이미 제외됨")
        else startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun refresh() {
        val allFiles = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
        val pm = getSystemService(PowerManager::class.java)
        val found = Agent.dirs(this).filter { File(it).isDirectory }
        val nAll = found.sumOf { d -> File(d).walkTopDown().count { it.isFile && Agent.isAudio(it) } }
        val nNew = Agent.candidates(this).size
        val db = Db(this)
        val lastErr = Prefs.get(this, "last_error")
        tvStatus.text = listOf(
            "① 파일접근 권한 : ${if (allFiles) "OK" else "없음 → 1번 버튼"}",
            "② 배터리 제외   : ${if (pm.isIgnoringBatteryOptimizations(packageName)) "OK" else "미설정 → 2번 버튼"}",
            "③ API 키        : ${if (Analyzer.keysOk(this)) "OK (${Prefs.get(this, "stt_provider", "groq")}/${Prefs.get(this, "llm_provider", "groq")})" else "미입력 → 3·4번"}",
            "④ 자동감시      : ${if (Prefs.get(this, "scheduled") == "1") "실행 중 (15분 간격)" else "미시작 → 4번 버튼"}",
            "녹음 폴더 ${found.size}개 / 전체 ${nAll}개 / 분석 대상 ${nNew}개",
            "완료 ${db.count("done")} · 재시도대기 ${db.count("retry")} · 오류 ${db.count("error")}",
            "마지막 스캔: ${Prefs.get(this, "last_scan").ifBlank { "없음" }}",
            if (lastErr.isNotBlank()) "최근 오류: $lastErr" else "최근 오류: 없음"
        ).joinToString("\n")
        tvResults.text = db.results(20).joinToString("\n\n") { Analyzer.format(it) }.ifBlank { "분석 결과 없음 (통화 후 90초 뒤 '지금 스캔')" }
        val f = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)
        tvLog.text = "── 로그 ──\n" + db.logs(40).joinToString("\n") { "${f.format(Date(it.first))} ${it.second}" }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
