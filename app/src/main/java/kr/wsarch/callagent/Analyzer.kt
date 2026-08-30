package kr.wsarch.callagent

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

object Analyzer {
    private val http = OkHttpClient.Builder().connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES).writeTimeout(10, TimeUnit.MINUTES).build()
    private val WEEK = arrayOf("월", "화", "수", "목", "금", "토", "일")

    private val PROMPT = """당신은 건축사사무소(WANSEUNG ARCHITECTS, 대표 건축사 {me})의 업무비서다. 아래 통화 텍스트를 분석해 JSON만 출력하라(설명 금지, 코드블록 금지).
통화일시: {when} ({dow}요일). 파일명상 상대방: {who}. "내일·다음주·월요일까지" 같은 상대 날짜는 이 통화일 기준 실제 날짜(YYYY-MM-DD)로 계산하라. 기한 언급이 없으면 "기한 미확인". 단순 대화·아이디어와 {me}가 실제로 하기로 한 업무를 구분하고, 불확실하면 할일에 넣지 말고 "확인필요"에 넣어라. 없는 내용은 만들지 마라. 없는 항목은 빈 배열 또는 "해당 없음".
특히 인식할 업무: 설계·도면수정 / 건축인허가 / 해체계획서·해체감리 / 공사감리·현장확인 / 견적서·계약서·공문 / 발주처·건축주·시공사·공무원 연락 / 용역비·미수금·세금계산서 / 직원 업무지시·채용 / 민원·법적대응 / 회의·현장조사·제출일정.
화자 구분: 텍스트에 화자 표시가 없으면 문맥으로 {me}(건축사) 발언과 상대방 발언을 추정하되, 확신 없으면 "화자불명"으로 다뤄라.

JSON 형식:
{"통화일시":"", "통화상대방":"", "관련프로젝트":"", "핵심요약":"", "확정된결정사항":[], "상대방요청자료":[],
"내가할일":[{"내용":"","기한":"","우선순위":"긴급|중요|일반","근거문장":""}],
"담당자_재연락대상":[], "후속연락문안":"", "견적계약입금세금계산서":[], "공문인허가설계감리현장":[],
"위험사항":[], "확인필요":[], "근거":[{"판단":"","문장":""}]}

통화 텍스트:
{text}"""

    fun keysOk(ctx: Context): Boolean {
        val sttOk = if (Prefs.get(ctx, "stt_provider", "groq") == "openai") Prefs.get(ctx, "openai_key").isNotBlank() else Prefs.get(ctx, "groq_key").isNotBlank()
        val llmOk = if (Prefs.get(ctx, "llm_provider", "groq") == "claude") Prefs.get(ctx, "anthropic_key").isNotBlank() else Prefs.get(ctx, "groq_key").isNotBlank()
        return sttOk && llmOk
    }

    /** 429(무료 한도) 시 대기 후 재시도 */
    private fun call(req: Request, label: String): String {
        var last = ""
        for (i in 0 until 3) {
            http.newCall(req).execute().use { r ->
                val s = r.body?.string() ?: ""
                if (r.isSuccessful) return s
                last = "$label 실패 ${r.code}: ${s.take(300)}"
                if (r.code != 429 && r.code < 500) throw RuntimeException(last)
                val wait = (r.header("retry-after")?.toLongOrNull() ?: 20L).coerceIn(5L, 90L)
                Thread.sleep(wait * 1000)
            }
        }
        throw RuntimeException(last)
    }

    fun stt(ctx: Context, f: File): String {
        val groq = Prefs.get(ctx, "stt_provider", "groq") != "openai"
        val key = Prefs.get(ctx, if (groq) "groq_key" else "openai_key")
        require(key.isNotBlank()) { if (groq) "Groq API 키 없음" else "OpenAI API 키 없음" }
        require(f.length() < 25L * 1024 * 1024) { "파일 25MB 초과(STT 제한)" }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", f.name, f.asRequestBody("audio/*".toMediaType()))
            .addFormDataPart("model", if (groq) "whisper-large-v3" else "whisper-1")
            .addFormDataPart("language", "ko")
            .addFormDataPart("response_format", "json")
            .addFormDataPart("prompt", "건축사사무소 업무 통화. 해체계획서, 인허가, 감리, 견적서, 세금계산서, 착공신고, 사용승인.")
            .build()
        val url = if (groq) "https://api.groq.com/openai/v1/audio/transcriptions" else "https://api.openai.com/v1/audio/transcriptions"
        val req = Request.Builder().url(url).header("Authorization", "Bearer $key").post(body).build()
        return JSONObject(call(req, "STT")).getString("text")
    }

    private fun parseJson(raw0: String): JSONObject {
        var raw = raw0.trim()
        val a = raw.indexOf('{'); val b = raw.lastIndexOf('}')
        if (a >= 0 && b > a) raw = raw.substring(a, b + 1)
        return JSONObject(raw)
    }

    private fun askLlm(ctx: Context, prompt: String): JSONObject {
        if (Prefs.get(ctx, "llm_provider", "groq") == "claude") {
            val key = Prefs.get(ctx, "anthropic_key"); require(key.isNotBlank()) { "Anthropic API 키 없음" }
            val body = JSONObject().put("model", Prefs.get(ctx, "model", "claude-sonnet-4-6")).put("max_tokens", 3000)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            val req = Request.Builder().url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", key).header("anthropic-version", "2023-06-01").header("content-type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            val arr = JSONObject(call(req, "Claude")).getJSONArray("content")
            val sb = StringBuilder(); for (i in 0 until arr.length()) sb.append(arr.getJSONObject(i).optString("text"))
            return parseJson(sb.toString())
        }
        val key = Prefs.get(ctx, "groq_key"); require(key.isNotBlank()) { "Groq API 키 없음" }
        val body = JSONObject().put("model", Prefs.get(ctx, "groq_model", "llama-3.3-70b-versatile"))
            .put("temperature", 0.1).put("max_tokens", 3000)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", "You are a Korean business assistant. Respond only with valid JSON in Korean."))
                .put(JSONObject().put("role", "user").put("content", prompt)))
        val req = Request.Builder().url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $key").header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val txt = JSONObject(call(req, "Groq")).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        return parseJson(txt)
    }

    fun analyze(ctx: Context, text: String, callTime: LocalDateTime, who: String): JSONObject {
        val me = Prefs.get(ctx, "my_name", "이하정")
        val head = PROMPT.replace("{me}", me)
            .replace("{when}", callTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
            .replace("{dow}", WEEK[callTime.dayOfWeek.value - 1]).replace("{who}", who)
        val limit = if (Prefs.get(ctx, "llm_provider", "groq") == "claude") 60_000 else 4_500
        val chunks = text.chunked(limit)
        if (chunks.size == 1) return finish(ctx, head.replace("{text}", text), who)
        // 긴 통화: 구간별 분석 후 병합 (무료 한도 TPM 대응)
        var merged = finish(ctx, head.replace("{text}", "[통화 1/${chunks.size} 구간]\n" + chunks[0]), who)
        for (i in 1 until chunks.size)
            merged = mergeInto(merged, finish(ctx, head.replace("{text}", "[통화 ${i + 1}/${chunks.size} 구간]\n" + chunks[i]), who))
        return merged
    }

    private fun finish(ctx: Context, prompt: String, who: String): JSONObject {
        val res = askLlm(ctx, prompt)
        if (res.optString("통화상대방").isBlank()) res.put("통화상대방", who)
        return res
    }

    private fun mergeInto(a: JSONObject, b: JSONObject): JSONObject {
        val keys = b.keys()
        while (keys.hasNext()) {
            val k = keys.next(); val v = b.opt(k) ?: continue
            when (v) {
                is JSONArray -> { val arr = a.optJSONArray(k) ?: JSONArray(); for (i in 0 until v.length()) arr.put(v.get(i)); a.put(k, arr) }
                is String -> if (k == "핵심요약" || k == "후속연락문안") { val s = a.optString(k); if (v.isNotBlank() && v != "해당 없음") a.put(k, if (s.isBlank() || s == "해당 없음") v else "$s / $v") }
                           else if (a.optString(k).isBlank()) a.put(k, v)
                else -> if (!a.has(k)) a.put(k, v)
            }
        }
        return a
    }

    private fun join(o: JSONObject, k: String): String {
        val a = o.optJSONArray(k) ?: return ""
        return (0 until a.length()).joinToString(" / ") { a.optString(it) }
    }

    fun format(r: JSONObject): String {
        val L = mutableListOf(
            "📞 ${r.optString("통화일시")} ${r.optString("통화상대방")} | ${r.optString("관련프로젝트")}",
            "요약: ${r.optString("핵심요약")}")
        join(r, "확정된결정사항").takeIf { it.isNotBlank() }?.let { L.add("결정: $it") }
        join(r, "상대방요청자료").takeIf { it.isNotBlank() }?.let { L.add("요청자료: $it") }
        r.optJSONArray("내가할일")?.let { a ->
            for (i in 0 until a.length()) {
                val t = a.optJSONObject(i) ?: continue
                val due = t.optString("기한").ifBlank { "기한 미확인" }
                L.add("☐ [${t.optString("우선순위").ifBlank { "일반" }}] ${t.optString("내용")} (기한: $due)")
            }
        }
        join(r, "견적계약입금세금계산서").takeIf { it.isNotBlank() }?.let { L.add("💰 $it") }
        join(r, "위험사항").takeIf { it.isNotBlank() }?.let { L.add("⚠ $it") }
        join(r, "확인필요").takeIf { it.isNotBlank() }?.let { L.add("? 확인필요: $it") }
        r.optString("후속연락문안").takeIf { it.isNotBlank() && it != "해당 없음" }?.let { L.add("후속: $it") }
        return L.joinToString("\n")
    }
}
