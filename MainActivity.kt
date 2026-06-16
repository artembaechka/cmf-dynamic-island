package com.maaz.dynamicisland

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }

        findViewById<MaterialButton>(R.id.btnOverlay).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        findViewById<MaterialButton>(R.id.btnNotifAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<MaterialButton>(R.id.btnStart).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                toast("Grant \"Display over other apps\" first.")
                return@setOnClickListener
            }
            if (!notificationAccessGranted()) {
                toast("Grant notification access for media + alerts.")
            }
            val i = Intent(this, IslandService::class.java)
                .setAction(IslandService.ACTION_START)
            ContextCompat.startForegroundService(this, i)
            toast("Island started")
            updateStatus()
        }

        findViewById<MaterialButton>(R.id.btnStop).setOnClickListener {
            val i = Intent(this, IslandService::class.java)
                .setAction(IslandService.ACTION_STOP)
            startService(i)
            toast("Island stopped")
            updateStatus()
        }

        setupSlider(R.id.sliderY, Prefs.KEY_Y, Prefs.yOffset(this))
        setupSlider(R.id.sliderCW, Prefs.KEY_CW, Prefs.collapsedW(this))
        setupSlider(R.id.sliderCH, Prefs.KEY_CH, Prefs.collapsedH(this))
        setupSlider(R.id.sliderEW, Prefs.KEY_EW, Prefs.expandedW(this))
        setupSlider(R.id.sliderCorner, Prefs.KEY_CORNER, Prefs.corner(this))
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun setupSlider(id: Int, key: String, initial: Float) {
        val s = findViewById<Slider>(id)
        s.value = initial.coerceIn(s.valueFrom, s.valueTo)
        s.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            Prefs.set(this, key, value)
            if (IslandService.running) {
                val i = Intent(this, IslandService::class.java)
                    .setAction(IslandService.ACTION_CALIBRATE)
                startService(i)
            }
        }
    }

    private fun notificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ) ?: return false
        val me = packageName
        if (TextUtils.isEmpty(flat)) return false
        return flat.split(":").any { it.contains(me) }
    }

    private fun updateStatus() {
        val overlay = Settings.canDrawOverlays(this)
        val notif = notificationAccessGranted()
        val svc = IslandService.running
        status.text = buildString {
            append("Display over apps: ").append(if (overlay) "GRANTED" else "needed").append("\n")
            append("Notification access: ").append(if (notif) "GRANTED" else "needed").append("\n")
            append("Island service: ").append(if (svc) "RUNNING" else "stopped")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
