package kr.wsarch.callagent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

object Notify {
    private const val CH = "call_agent"

    fun show(ctx: Context, title: String, body: String, id: Int) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "통화분석", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH).build()
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (e: SecurityException) { Log.w(Agent.TAG, "알림 권한 없음") }
    }

    fun kakao(ctx: Context, text: String) {
        if (Prefs.get(ctx, "kakao_on") != "1") return
        val rest = Prefs.get(ctx, "kakao_rest"); val refresh = Prefs.get(ctx, "kakao_refresh")
        if (rest.isBlank() || refresh.isBlank()) return
        try {
            val http = OkHttpClient()
            val form = FormBody.Builder().add("grant_type", "refresh_token").add("client_id", rest).add("refresh_token", refresh).build()
            val tok = http.newCall(Request.Builder().url("https://kauth.kakao.com/oauth/token").post(form).build()).execute()
                .use { JSONObject(it.body?.string() ?: "{}") }
            val access = tok.optString("access_token")
            if (access.isBlank()) { Log.w(Agent.TAG, "카카오 토큰 갱신 실패: $tok"); return }
            tok.optString("refresh_token").takeIf { it.isNotBlank() }?.let { Prefs.set(ctx, "kakao_refresh", it) }
            val tpl = JSONObject().put("object_type", "text").put("text", text.take(1000))
                .put("link", JSONObject().put("web_url", "https://wsarch.kr"))
            val f2 = FormBody.Builder().add("template_object", tpl.toString()).build()
            http.newCall(Request.Builder().url("https://kapi.kakao.com/v2/api/talk/memo/default/send")
                .header("Authorization", "Bearer $access").post(f2).build()).execute().close()
        } catch (e: Exception) { Log.w(Agent.TAG, "카카오 전송 실패", e) }
    }
}
