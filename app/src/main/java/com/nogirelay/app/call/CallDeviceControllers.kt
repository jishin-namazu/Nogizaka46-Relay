package com.nogirelay.app.call

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.PowerManager
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

/**
 * 接近熄屏：只在距离传感器确实“贴近”时才持有 `PROXIMITY_SCREEN_OFF_WAKE_LOCK`。
 *
 * 一开始播放就抢锁会让显示子系统立刻请求 `useProximitySensor=true`，在传感器读数到达前
 * 屏幕会被瞬间熄灭再恢复（黑屏闪烁）。改为监听传感器：贴近时才持锁熄屏，
 * 远离时释放亮屏，放到耳边听语音的行为不变。
 */
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

    override fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        if (!enabled) {
            stopListening()
            releaseWaitingForFarState()
            return
        }
        if (proximitySensor == null) {
            // 没有距离传感器时持锁没有意义，反而会触发上面的显示状态请求。
            return
        }
        startListening()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!enabled) return
        val maxRange = event.sensor.maximumRange
        val near = event.values.firstOrNull()?.let { it < maxRange } ?: false
        if (near) {
            if (!wakeLock.isHeld) wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        } else {
            releaseWaitingForFarState()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun close() {
        enabled = false
        stopListening()
        releaseWaitingForFarState()
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

    private fun releaseWaitingForFarState() {
        if (wakeLock.isHeld) wakeLock.release(RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
    }

    private companion object {
        const val WAKE_LOCK_TAG = "com.sonydna.messages.app:VoicePlayerWakeLock"
        const val RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY = 1
        const val WAKE_LOCK_TIMEOUT_MS = 30L * 60L * 1000L
    }
}
