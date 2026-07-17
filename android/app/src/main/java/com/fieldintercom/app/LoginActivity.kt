package com.fieldintercom.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class LoginActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private var service: IntercomService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as IntercomService.LocalBinder).getService()
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = SessionManager(this)

        // Already logged in within the last 8 hours -- skip straight to the main screen.
        if (sessionManager.isSessionValid()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_login)

        val serverInput = findViewById<EditText>(R.id.serverAddressInput)
        val passwordInput = findViewById<EditText>(R.id.passwordInput)
        val errorText = findViewById<TextView>(R.id.errorText)
        val loginButton = findViewById<Button>(R.id.loginButton)

        serverInput.setText(sessionManager.serverAddress)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }

        val serviceIntent = Intent(this, IntercomService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        loginButton.setOnClickListener {
            val address = serverInput.text.toString().trim()
            val password = passwordInput.text.toString()
            if (address.isEmpty() || password.isEmpty()) return@setOnClickListener

            errorText.visibility = View.INVISIBLE
            loginButton.isEnabled = false
            sessionManager.serverAddress = address

            service?.login(address, password) { success ->
                loginButton.isEnabled = true
                if (success) {
                    sessionManager.markLoggedIn()
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                } else {
                    errorText.visibility = View.VISIBLE
                }
            } ?: run {
                errorText.visibility = View.VISIBLE
            }
        }
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }
}
