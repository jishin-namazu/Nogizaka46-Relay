package com.nogirelay.app.call

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator

internal interface IncomingCallVibrationControl {
    fun start()
    fun stop()
}

internal class OfficialIncomingCallVibrationControl(context: Context) : IncomingCallVibrationControl {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)

    override fun start() {
        if (audioManager.ringerMode !in setOf(AudioManager.RINGER_MODE_NORMAL, AudioManager.RINGER_MODE_VIBRATE)) return
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(1_000L, 1_000L), 0))
    }

    override fun stop() {
        vibrator.cancel()
    }
}

internal interface ProximityScreenControl {
    fun setEnabled(enabled: Boolean)
    fun close()
}

internal class OfficialProximityScreenControl(context: Context) :
    ProximityScreenControl,
    SensorEventListener {
    private val appContext = context.applicationContext
    private val wakeLock = appContext.getSystemService(PowerManager::class.java).newWakeLock(
        PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
        WAKE_LOCK_TAG,
    )
    private val sensorManager = appContext.getSystemService(SensorManager::class.java)
    private val proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private var enabled = false
    private var listening = false
    private val handler = Handler(Looper.getMainLooper())
    private val stability = ProximityStabilityGate()
    private val confirmNear = Runnable {
        if (enabled && stability.remainingMillis(SystemClock.elapsedRealtime()) == 0L && !wakeLock.isHeld) {
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    override fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        handler.removeCallbacks(confirmNear)
        if (!enabled) {
            stability.disable()
            stopListening()
            releaseScreenLock()
            return
        }
        if (proximitySensor == null) {

            return
        }
        stability.enable(SystemClock.elapsedRealtime())
        startListening()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!enabled) return
        val maxRange = event.sensor.maximumRange
        val near = event.values.firstOrNull()?.let { it.isFinite() && it >= 0f && it < minOf(5f, maxRange) } ?: false
        stability.sample(near, event.timestamp / 1_000_000L)
        handler.removeCallbacks(confirmNear)
        val remaining = stability.remainingMillis(SystemClock.elapsedRealtime())
        if (remaining != null) handler.postDelayed(confirmNear, remaining)
        else releaseScreenLock()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun close() {
        enabled = false
        stability.disable()
        handler.removeCallbacks(confirmNear)
        stopListening()
        releaseScreenLock()
    }

    private fun startListening() {
        val sensor = proximitySensor ?: return
        if (listening) return
        listening = sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL) == true
    }

    private fun stopListening() {
        if (!listening) return
        sensorManager?.unregisterListener(this)
        listening = false
    }

    private fun releaseScreenLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    private companion object {
        const val WAKE_LOCK_TAG = "com.sonydna.messages.app:VoicePlayerWakeLock"
        const val WAKE_LOCK_TIMEOUT_MS = 30L * 60L * 1000L
    }
}
