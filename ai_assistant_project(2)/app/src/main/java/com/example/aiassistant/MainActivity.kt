package com.example.aiassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        val keyInput = findViewById<EditText>(R.id.apiKeyInput)
        keyInput.setText(prefs.getString("api_key", ""))

        findViewById<Button>(R.id.btnSaveKey).setOnClickListener {
            prefs.edit().putString("api_key", keyInput.text.toString().trim()).apply()
            Toast.makeText(this, "Ключ сохранён", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnOverlayPermission).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            } else {
                Toast.makeText(this, "Разрешение на окна уже есть", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Сначала дай разрешение «окна поверх других»", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startForegroundService(Intent(this, FloatingService::class.java))
            finish()
        }
    }
}