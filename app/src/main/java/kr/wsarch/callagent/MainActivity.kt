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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var tvStatus: TextView
    private lateinit var tvResults: TextView
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
        tvStatus = findViewById(R.id.tvStatus); tvResults = findViewById(R.id.tvResults)
        etOpenai = findViewById(R.id.etOpenai); etAnthropic = findViewById(R.id.etAnthropic)
        etName = findViewById(R.id.etName); etDirs = findViewById(R.id.etDirs)
        etKakaoRest = findViewById(R.id.etKakaoRest); etKakaoRefresh = findViewById(R.id.etKakaoRefresh)
        cbKakao = findViewById(R.id.cbKakao); cbOld = findViewById(R.id.cbOld)
        if (Build.VERSION.SDK_INT >= 33) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        load()
        findViewById<Button>(R.id.btnPerm).setOnClickListener { requestAllFiles() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestBattery() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnScan).setOnClickListener { runNow() }
        findViewById<Button>(R.id.btnDaily).setOnClickListener { Thread { Agent.daily(this) }.start(); toast("일일 목록 전송") }
        findViewById<Button>(R.id.btnRetry).setOnClickListener { val n = Db(this).retryErrors(); toast("오류 ${n}건 재시도"); runNow() }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun load() {
        etOpenai.setText(Prefs.get(this, "openai_key")); etAnthropic.setText(Prefs.get(this, "anthropic_key"))
        etName.setText(Prefs.get(this, "my_name", "이하정"))
        val d = Prefs.get(this, "dirs").ifBlank { Agent.DEFAULT_DIRS.filter { File(it).isDirectory }.ifEmpty { Agent.DEFAULT_DIRS }.joinToString("\n") }
        etDirs.setText(d)
        etKakaoRest.setText(Prefs.get(this, "kakao_rest")); etKakaoRefresh.setText(Prefs.get(this, "kakao_refresh"))
        cbKakao.isChecked = Prefs.get(this, "kakao_on") == "1"
    }

    private fun save() {
        Prefs.set(this, "openai_key", etOpenai.text.toString().trim())
        Prefs.set(this, "anthropic_key", etAnthropic.text.toString().trim())
        Prefs.set(this, "my_name", etName.text.toString().trim().ifBlank { "이하정" })
        Prefs.set(this, "dirs", etDirs.text.toString())
        Prefs.set(this, "kakao_rest", etKakaoRest.text.toString().trim())
        Prefs.set(this, "kakao_refresh", etKakaoRefresh.text.toString().trim())
        Prefs.set(this, "kakao_on", if (cbKakao.isChecked) "1" else "0")
        if (Prefs.get(this, "baseline") != "1") {
            if (!cbOld.isChecked) { val n = Agent.baseline(this); toast("기존 ${n}건 분석 제외 등록") }
            Prefs.set(this, "baseline", "1")
        }
        Agent.schedule(this)
        toast("저장 완료. 15분 간격 자동감시 시작")
        refresh()
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
        val nFiles = found.sumOf { d -> File(d).walkTopDown().count { it.isFile && Agent.isAudio(it) } }
        val db = Db(this)
        tvStatus.text = listOf(
            "파일접근 권한 : ${if (allFiles) "OK" else "없음 → 1번 버튼"}",
            "배터리 제외   : ${if (pm.isIgnoringBatteryOptimizations(packageName)) "OK" else "미설정 → 2번 버튼"}",
            "API 키        : ${if (Prefs.get(this, "openai_key").isNotBlank() && Prefs.get(this, "anthropic_key").isNotBlank()) "OK" else "미입력"}",
            "녹음 폴더     : ${found.size}개 발견, 오디오 ${nFiles}개",
            "분석 완료 ${db.count("done")}건 / 오류 ${db.count("error")}건 / 제외 ${db.count("skipped")}건",
            "자동감시      : ${if (Prefs.get(this, "scheduled") == "1") "실행 중 (15분 간격)" else "미시작 → 3번 버튼"}"
        ).joinToString("\n")
        tvResults.text = db.results(20).joinToString("\n\n") { Analyzer.format(it) }.ifBlank { "분석 결과 없음" }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
