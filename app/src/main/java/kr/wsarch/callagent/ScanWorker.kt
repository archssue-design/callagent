package kr.wsarch.callagent

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters

class ScanWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result {
        val ctx = applicationContext
        return try {
            if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                Agent.log(ctx, "파일 접근 권한 없음 — 스캔 건너뜀")
                Notify.show(ctx, "통화분석 설정 필요", "'모든 파일 접근' 권한이 없어 감시 불가. 앱을 열어 1번 버튼을 누르세요.", 1)
                return Result.success()
            }
            val (_, remaining) = Agent.scan(ctx)
            Agent.maybeDaily(ctx)
            if (remaining > 0) WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<ScanWorker>().build())
            Result.success()
        } catch (e: Exception) {
            Log.e(Agent.TAG, "worker 오류", e); Agent.log(ctx, "worker 오류: ${e.message}"); Result.retry()
        }
    }
}
