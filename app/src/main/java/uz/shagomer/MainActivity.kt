package uz.shagomer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import uz.shagomer.databinding.ActivityMainBinding
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var store: Store
    private val nf = NumberFormat.getIntegerInstance(Locale("ru"))

    private val updates = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = render()
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.ACTIVITY_RECOGNITION] == true ||
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
            ) {
                StepService.start(this)
                b.permissionHint.visibility = View.GONE
            } else {
                b.permissionHint.visibility = View.VISIBLE
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        store = Store(this)

        b.nameInput.setText(store.participantName)
        b.urlInput.setText(store.serverUrl)
        b.participantId.text = getString(R.string.participant_id, store.participantId)

        b.saveButton.setOnClickListener { saveSettings() }
        b.syncButton.setOnClickListener {
            if (store.serverUrl.isBlank() || store.participantName.isBlank()) {
                toast(R.string.err_setup)
            } else {
                b.syncStatus.text = getString(R.string.sync_running)
                Sync.run(this, force = true)
            }
        }
        b.permissionHint.setOnClickListener { ensurePermissions() }
        b.shareButton.setOnClickListener { shareApp() }

        // Переключатель языка RU / UZ
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        b.langGroup.check(if (current.startsWith("uz")) R.id.langUz else R.id.langRu)
        b.langGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val tag = if (checkedId == R.id.langUz) "uz" else "ru"
            if (!current.startsWith(tag)) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
        }

        ensurePermissions()
        render()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(StepService.ACTION_STEPS_UPDATED)
            addAction(Sync.ACTION_SYNCED)
        }
        ContextCompat.registerReceiver(this, updates, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        Sync.run(this)
        render()
    }

    override fun onPause() {
        unregisterReceiver(updates)
        super.onPause()
    }

    private fun ensurePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.ACTIVITY_RECOGNITION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS

        if (needed.isEmpty()) {
            StepService.start(this)
            b.permissionHint.visibility = View.GONE
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun render() {
        val t = store.totals()
        val rate = store.rate
        b.balance.text = nf.format(t.balance)
        b.money.text = getString(R.string.sum, nf.format(t.balance * rate))
        b.toNext.text = if (t.today >= Store.STEPS_PER_POINT)
            getString(R.string.today_done)
        else
            getString(R.string.to_next, nf.format(Store.STEPS_PER_POINT - t.today))
        b.ring.max = Store.STEPS_PER_POINT.toInt()
        b.ring.setProgressCompat(t.rest.toInt(), true)
        b.today.text = nf.format(t.today)
        b.total.text = nf.format(t.total)
        b.earned.text = nf.format(t.earned)
        b.paid.text = nf.format(t.paidAmount)
        b.rateLabel.text = getString(R.string.rate_label, nf.format(rate))

        val days = store.days().entries.reversed().take(14)
        b.daysContainer.removeAllViews()
        if (days.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.no_days)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
                setPadding(0, 24, 0, 24)
            }
            b.daysContainer.addView(empty)
        } else {
            val inflater = LayoutInflater.from(this)
            days.forEach { (d, s) ->
                val row = inflater.inflate(R.layout.row_day, b.daysContainer, false)
                row.findViewById<TextView>(R.id.rowDate).text = fmtDate(d).substring(0, 5)
                row.findViewById<ProgressBar>(R.id.rowBar).progress = minOf(s, Store.STEPS_PER_POINT).toInt()
                row.findViewById<TextView>(R.id.rowSteps).text = nf.format(s)
                row.findViewById<TextView>(R.id.rowBadge).visibility =
                    if (s >= Store.STEPS_PER_POINT) View.VISIBLE else View.INVISIBLE
                b.daysContainer.addView(row)
            }
        }

        b.setupHint.visibility =
            if (store.serverUrl.isBlank() || store.participantName.isBlank()) View.VISIBLE else View.GONE
        b.syncStatus.text = when {
            store.lastSync == 0L -> getString(R.string.sync_never)
            store.lastSyncOk -> getString(R.string.sync_ok, fmtTime(store.lastSync))
            else -> getString(R.string.sync_failed, fmtTime(store.lastSync))
        }
    }

    private fun saveSettings() {
        val name = b.nameInput.text.toString().trim()
        val url = b.urlInput.text.toString().trim()
        if (name.isEmpty()) { toast(R.string.err_name); return }
        if (url.isNotEmpty() && !url.startsWith("https://")) { toast(R.string.err_url); return }
        store.participantName = name
        store.serverUrl = url
        render()
        toast(R.string.saved)
        if (url.isNotEmpty()) Sync.run(this, force = true)
    }

    /** Открывает системное окно «Поделиться» со ссылкой на скачивание приложения. */
    private fun shareApp() {
        val text = getString(R.string.share_text, getString(R.string.download_url))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_title)))
    }

    private fun fmtDate(iso: String): String {
        val p = iso.split("-")
        return if (p.size == 3) "${p[2]}.${p[1]}.${p[0]}" else iso
    }

    private fun fmtTime(ms: Long): String =
        SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(ms))

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
}
