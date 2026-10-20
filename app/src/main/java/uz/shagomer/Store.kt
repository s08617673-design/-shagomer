package uz.shagomer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Хранилище: шаги по дням, курс, данные участника, последнее показание датчика
 * и сведения о выплатах, полученные от спонсора (из Google-таблицы).
 */
class Store(context: Context) {

    companion object {
        const val STEPS_PER_POINT = 10_000L
        const val MAX_POINTS_PER_DAY = 1L           // лимит спонсора: 1 балл в день
        const val DEFAULT_RATE = 5_000L             // сум за 1 балл (перекрывается значением из таблицы)

        private const val PREFS = "shagomer"
        private const val KEY_LAST_COUNTER = "last_counter"
        private const val KEY_DAYS = "days"
        private const val KEY_RATE = "rate"
        private const val KEY_NAME = "name"
        private const val KEY_URL = "url"
        private const val KEY_ID = "id"
        private const val KEY_PAID_POINTS = "paid_points"
        private const val KEY_PAID_AMOUNT = "paid_amount"
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_LAST_SYNC_OK = "last_sync_ok"
    }

    data class Totals(
        val total: Long,
        val today: Long,
        val earned: Long,       // баллов заработано (дней с 10 000+ шагов)
        val paidPoints: Long,   // баллов уже оплачено спонсором
        val paidAmount: Long,
        val balance: Long,      // ожидает выплаты
        val rest: Long          // шагов к сегодняшнему баллу (0..10000)
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun today(): String = dayFormat.format(Date())

    // ---------- участник и сервер ----------

    /** Постоянный идентификатор телефона в таблице спонсора. */
    val participantId: String
        get() = prefs.getString(KEY_ID, null) ?: UUID.randomUUID().toString().take(8).also {
            prefs.edit().putString(KEY_ID, it).apply()
        }

    var participantName: String
        get() = prefs.getString(KEY_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NAME, value.trim()).apply()

    var serverUrl: String
        get() = prefs.getString(KEY_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_URL, value.trim()).apply()

    var rate: Long
        get() = prefs.getLong(KEY_RATE, DEFAULT_RATE)
        set(value) = prefs.edit().putLong(KEY_RATE, value).apply()

    val lastSync: Long get() = prefs.getLong(KEY_LAST_SYNC, 0L)
    val lastSyncOk: Boolean get() = prefs.getBoolean(KEY_LAST_SYNC_OK, false)

    fun recordSync(ok: Boolean) {
        prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).putBoolean(KEY_LAST_SYNC_OK, ok).apply()
    }

    /** Ответ таблицы спонсора: сколько уже выплачено и актуальный курс. */
    fun applyServerReply(paidPoints: Long, paidAmount: Long, rate: Long?) {
        val e = prefs.edit().putLong(KEY_PAID_POINTS, paidPoints).putLong(KEY_PAID_AMOUNT, paidAmount)
        if (rate != null && rate > 0) e.putLong(KEY_RATE, rate)
        e.apply()
    }

    // ---------- шаги ----------

    fun days(): Map<String, Long> {
        val obj = JSONObject(prefs.getString(KEY_DAYS, "{}") ?: "{}")
        val map = sortedMapOf<String, Long>()
        for (k in obj.keys()) map[k] = obj.getLong(k)
        return map
    }

    private fun saveDays(map: Map<String, Long>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(KEY_DAYS, obj.toString()).apply()
    }

    /**
     * Принимает показание системного шагомера (накопительное с момента загрузки
     * телефона) и добавляет разницу к сегодняшнему дню.
     * Возвращает число новых баллов.
     */
    @Synchronized
    fun reconcile(counter: Long): Long {
        val last = prefs.getLong(KEY_LAST_COUNTER, -1L)
        prefs.edit().putLong(KEY_LAST_COUNTER, counter).apply()
        if (last < 0) return 0
        val delta = if (counter < last) counter else counter - last
        if (delta <= 0) return 0

        val before = totals().earned
        val map = days().toMutableMap()
        val t = today()
        map[t] = (map[t] ?: 0L) + delta
        saveDays(map)
        return totals().earned - before
    }

    fun pointsFor(steps: Long): Long = minOf(steps / STEPS_PER_POINT, MAX_POINTS_PER_DAY)

    // ---------- итоги ----------

    fun totals(): Totals {
        val d = days()
        val total = d.values.sum()
        val today = d[today()] ?: 0L
        val earned = d.values.sumOf { pointsFor(it) }
        val paidPoints = prefs.getLong(KEY_PAID_POINTS, 0L)
        val paidAmount = prefs.getLong(KEY_PAID_AMOUNT, 0L)
        return Totals(
            total = total,
            today = today,
            earned = earned,
            paidPoints = paidPoints,
            paidAmount = paidAmount,
            balance = earned - paidPoints,
            rest = minOf(today, STEPS_PER_POINT)
        )
    }
}
