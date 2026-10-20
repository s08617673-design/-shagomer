package uz.shagomer

import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Отправляет шаги за последние дни в Google-таблицу спонсора
 * (веб-приложение Apps Script) и получает назад сведения о выплатах.
 */
object Sync {

    const val ACTION_SYNCED = "uz.shagomer.SYNCED"
    private const val MIN_INTERVAL_MS = 10 * 60 * 1000L   // не чаще раза в 10 минут

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var lastAttempt = 0L

    /** Запускает синхронизацию в фоне. force = true игнорирует интервал. */
    fun run(context: Context, force: Boolean = false) {
        val store = Store(context)
        val url = store.serverUrl
        if (url.isBlank() || store.participantName.isBlank()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastAttempt < MIN_INTERVAL_MS) return
        lastAttempt = now

        executor.execute {
            val ok = try {
                val reply = post(url, buildPayload(store))
                store.applyServerReply(
                    paidPoints = reply.optLong("paidPoints", 0L),
                    paidAmount = reply.optLong("paidAmount", 0L),
                    rate = if (reply.has("rate")) reply.optLong("rate") else null
                )
                true
            } catch (e: Exception) {
                false
            }
            store.recordSync(ok)
            context.sendBroadcast(Intent(ACTION_SYNCED).setPackage(context.packageName))
        }
    }

    private fun buildPayload(store: Store): String {
        val days = JSONObject()
        // последние 14 дней — этого достаточно, чтобы догнать пропуски без интернета
        store.days().entries.toList().takeLast(14).forEach { (d, s) -> days.put(d, s) }
        return JSONObject()
            .put("id", store.participantId)
            .put("name", store.participantName)
            .put("days", days)
            .toString()
    }

    private fun post(url: String, body: String): JSONObject {
        var conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.doOutput = true
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        // Apps Script отвечает редиректом 302 на страницу с результатом — идём по нему.
        var code = conn.responseCode
        var hops = 0
        while (code in 300..399 && hops < 3) {
            val location = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            conn = URL(location).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            code = conn.responseCode
            hops++
        }
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        conn.disconnect()
        return JSONObject(text)
    }
}
