package uz.shagomer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.text.NumberFormat
import java.util.Locale

/**
 * Фоновая служба: слушает системный шаговый датчик, записывает шаги на нужный день
 * и показывает в шторке текущее число шагов и баллов.
 * Сам датчик считает шаги и без этой службы; служба нужна, чтобы шаги попадали
 * на правильную дату и чтобы баланс обновлялся без открытия приложения.
 */
class StepService : Service(), SensorEventListener {

    companion object {
        const val CHANNEL = "steps"
        const val NOTIFICATION_ID = 1
        const val ACTION_STEPS_UPDATED = "uz.shagomer.STEPS_UPDATED"

        fun start(context: Context) {
            val intent = Intent(context, StepService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private lateinit var store: Store
    private lateinit var sensorManager: SensorManager

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        createChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Нет разрешения на активность (Android 14+) — служба не нужна, пока его не дадут.
            stopSelf()
            return
        }
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
            // Датчик сам отдаёт событие на каждый шаг; maxReportLatency позволяет
            // системе копить события и будить приложение реже — экономит батарею.
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, 60_000_000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
        val gained = store.reconcile(event.values[0].toLong())
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
        if (gained > 0) nm.notify(2, pointNotification(gained))
        sendBroadcast(Intent(ACTION_STEPS_UPDATED).setPackage(packageName))
        Sync.run(this, force = gained > 0)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(): Notification {
        val t = store.totals()
        val nf = NumberFormat.getIntegerInstance(Locale("ru"))
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title, nf.format(t.today)))
            .setContentText(getString(R.string.notif_text, nf.format(t.balance), nf.format(t.balance * store.rate)))
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun pointNotification(gained: Long): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_point_title, gained))
            .setContentText(getString(R.string.notif_point_text))
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .build()
}
