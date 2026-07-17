package com.fieldintercom.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class IntercomService : Service() {

    interface StatusListener {
        fun onStatusChanged(status: NetworkClient.ConnectionStatus)
    }

    /** Optional listener the latency test screen attaches while it's open. */
    interface DiagnosticsListener {
        /** Called whenever an audio frame arrives carrying a non-zero origin timestamp --
         *  i.e. during an active loopback test, this is a frame of the phone's own voice
         *  coming back through the full pipeline. */
        fun onLoopbackFrame(originTimestampMs: Long)
    }

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): IntercomService = this@IntercomService
    }

    var statusListener: StatusListener? = null
    var diagnosticsListener: DiagnosticsListener? = null

    private lateinit var networkClient: NetworkClient
    private lateinit var audioEngine: AudioEngine
    private val mainHandler = Handler(Looper.getMainLooper())

    var isTransmitting = false
        private set

    companion object {
        private const val CHANNEL_ID = "intercom_channel"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()

        audioEngine = AudioEngine { pcm -> networkClient.sendAudioFrame(pcm) }

        networkClient = NetworkClient(object : NetworkClient.Listener {
            override fun onStatusChanged(status: NetworkClient.ConnectionStatus) {
                mainHandler.post { statusListener?.onStatusChanged(status) }
            }

            override fun onAudioReceived(pcm: ByteArray, originTimestampMs: Long) {
                audioEngine.playFrame(pcm)
                if (originTimestampMs != 0L) {
                    mainHandler.post { diagnosticsListener?.onLoopbackFrame(originTimestampMs) }
                }
            }
        })

        audioEngine.startPlayback()
        startForegroundNotification()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun login(serverAddress: String, password: String, callback: (Boolean) -> Unit) {
        networkClient.login(serverAddress, password) { success ->
            mainHandler.post { callback(success) }
        }
    }

    fun setVolume(volume: Float) {
        audioEngine.setVolume(volume)
    }

    fun setSidetoneVolume(volume: Float) {
        audioEngine.setSidetoneVolume(volume)
    }

    /** callback(roundTripMs) is invoked on the main thread when the reply arrives. */
    fun pingTest(callback: (Long) -> Unit) {
        networkClient.sendPing { rtt -> mainHandler.post { callback(rtt) } }
    }

    fun startLoopbackTest() = networkClient.startLoopbackTest()
    fun stopLoopbackTest() = networkClient.stopLoopbackTest()

    fun startTalking() {
        if (isTransmitting) return
        isTransmitting = true
        audioEngine.startCapture()
    }

    fun stopTalking() {
        if (!isTransmitting) return
        isTransmitting = false
        audioEngine.stopCapture()
    }

    fun logout() {
        stopTalking()
        networkClient.logout()
    }

    override fun onDestroy() {
        stopTalking()
        networkClient.logout()
        audioEngine.release()
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }
}
