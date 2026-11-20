package com.pintrip.app.place

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * 기기 가속도 센서(TYPE_ACCELEROMETER)를 사용하여 흔들기 동작을 감지하는 도우미 클래스.
 *
 * 사용 방법 (화면 생명주기 onResume / onPause 연결):
 * ```kotlin
 * private lateinit var shakeDetector: ShakeDetector
 *
 * override fun onCreate(savedInstanceState: Bundle?) {
 *     super.onCreate(savedInstanceState)
 *     val sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
 *     shakeDetector = ShakeDetector(sensorManager) {
 *         viewModel.pickRandomPlace()
 *     }
 * }
 *
 * override fun onResume() {
 *     super.onResume()
 *     val isAvailable = shakeDetector.start()
 *     if (!isAvailable) {
 *         // 센서 미지원 또는 등록 실패 시 처리 (예: 대체 버튼 활성화 안내)
 *     }
 * }
 *
 * override fun onPause() {
 *     super.onPause()
 *     shakeDetector.stop()
 * }
 * ```
 *
 * @property sensorManager Android SensorManager 시스템 서비스 (Nullable)
 * @property thresholdG 흔들기 감지 g-force 임계값 (기본값: 2.7g)
 * @property cooldownMs 감지 후 재감지 방지 쿨다운 시간(ms) (기본값: 1000ms)
 * @property timeProvider 단조 증가 시각(ms) 제공 함수 (기본값: SystemClock.elapsedRealtime)
 * @property onShakeDetected 흔들기 감지 시 실행할 콜백
 */
class ShakeDetector(
    private val sensorManager: SensorManager?,
    private val thresholdG: Float = DEFAULT_THRESHOLD_G,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val timeProvider: () -> Long = { SystemClock.elapsedRealtime() },
    private val onShakeDetected: () -> Unit,
) : SensorEventListener {

    var isListening: Boolean = false
        private set

    private var lastShakeTimestampMs: Long = NO_PREVIOUS_SHAKE

    /**
     * 흔들기 감지를 시작합니다.
     *
     * @return 센서가 존재하고 성공적으로 등록되었으면 true, 그렇지 않으면 false
     */
    fun start(): Boolean {
        if (sensorManager == null) return false
        if (isListening) return true

        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: return false

        val registered = sensorManager.registerListener(
            this,
            accelerometer,
            SensorManager.SENSOR_DELAY_UI,
        )
        isListening = registered
        return registered
    }

    /**
     * 흔들기 감지를 중지하고 센서 리스너 등록을 해제합니다.
     */
    fun stop() {
        if (!isListening) return
        sensorManager?.unregisterListener(this)
        isListening = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values.getOrNull(0) ?: return
        val y = event.values.getOrNull(1) ?: return
        val z = event.values.getOrNull(2) ?: return

        val currentTimeMs = timeProvider()
        processAcceleration(x, y, z, currentTimeMs)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    /**
     * 순수 가속도 벡터 값(x, y, z)과 시각(ms)을 입력받아 g-force 정규화 및 쿨다운 조건을 검사합니다.
     *
     * Android 공식 가속도 벡터 정규화 공식 적용:
     * gForce = sqrt(x^2 + y^2 + z^2) / SensorManager.GRAVITY_EARTH
     *
     * @return 흔들기가 감지되어 콜백이 호출되었으면 true, 아니면 false
     */
    fun processAcceleration(x: Float, y: Float, z: Float, timestampMs: Long): Boolean {
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        val gForce = magnitude / SensorManager.GRAVITY_EARTH

        if (gForce >= thresholdG) {
            if (lastShakeTimestampMs == NO_PREVIOUS_SHAKE || timestampMs - lastShakeTimestampMs >= cooldownMs) {
                lastShakeTimestampMs = timestampMs
                onShakeDetected()
                return true
            }
        }
        return false
    }

    companion object {
        /** 흔들기 감지 g-force 임계값 (SensorManager.GRAVITY_EARTH 배수) */
        const val DEFAULT_THRESHOLD_G: Float = 2.7f
        /** 감지 후 연속 입력 억제를 위한 쿨다운 타임 (ms) */
        const val DEFAULT_COOLDOWN_MS: Long = 1000L
        private const val NO_PREVIOUS_SHAKE: Long = -1L
    }
}
