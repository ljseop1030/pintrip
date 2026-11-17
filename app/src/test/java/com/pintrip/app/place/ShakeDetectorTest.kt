package com.pintrip.app.place

import android.hardware.SensorManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeDetectorTest {

    private val gravityEarth = SensorManager.GRAVITY_EARTH

    @Test
    fun processAcceleration_stationaryState_doesNotTriggerCallback() {
        var shakeCount = 0
        val detector = ShakeDetector(sensorManager = null, thresholdG = 2.7f, cooldownMs = 1000L) {
            shakeCount++
        }

        // 정지 상태 (1.0g)
        val result = detector.processAcceleration(0f, 0f, gravityEarth, timestampMs = 1000L)

        assertFalse("정지 상태에서는 흔들기가 감지되지 않아야 함", result)
        assertEquals(0, shakeCount)
    }

    @Test
    fun processAcceleration_aboveThreshold_triggersCallback() {
        var shakeCount = 0
        val detector = ShakeDetector(sensorManager = null, thresholdG = 2.7f, cooldownMs = 1000L) {
            shakeCount++
        }

        // 3.0g 가속도 입력 (3.0 * 9.80665)
        val result = detector.processAcceleration(0f, 0f, gravityEarth * 3.0f, timestampMs = 1000L)

        assertTrue("임계값(2.7g) 초과 시 흔들기가 감지되어야 함", result)
        assertEquals(1, shakeCount)
    }

    @Test
    fun processAcceleration_withinCooldownPeriod_suppressesCallback() {
        var shakeCount = 0
        val detector = ShakeDetector(sensorManager = null, thresholdG = 2.7f, cooldownMs = 1000L) {
            shakeCount++
        }

        // t=1000ms: 첫 번째 흔들기 감지
        val result1 = detector.processAcceleration(0f, 0f, gravityEarth * 3.0f, timestampMs = 1000L)
        assertTrue(result1)
        assertEquals(1, shakeCount)

        // t=1500ms (쿨다운 1000ms 미만 경과): 연속 입력 억제
        val result2 = detector.processAcceleration(0f, 0f, gravityEarth * 3.0f, timestampMs = 1500L)
        assertFalse("쿨다운 시간 이내 입력은 억제되어야 함", result2)
        assertEquals(1, shakeCount)
    }

    @Test
    fun processAcceleration_afterCooldownPeriod_triggersCallbackAgain() {
        var shakeCount = 0
        val detector = ShakeDetector(sensorManager = null, thresholdG = 2.7f, cooldownMs = 1000L) {
            shakeCount++
        }

        // t=1000ms: 첫 번째 흔들기 감지
        detector.processAcceleration(0f, 0f, gravityEarth * 3.0f, timestampMs = 1000L)
        assertEquals(1, shakeCount)

        // t=2100ms (1100ms > 쿨다운 1000ms 경과): 두 번째 흔들기 재감지
        val result2 = detector.processAcceleration(0f, 0f, gravityEarth * 3.0f, timestampMs = 2100L)
        assertTrue("쿨다운 경과 후 입력은 다시 감지되어야 함", result2)
        assertEquals(2, shakeCount)
    }

    @Test
    fun processAcceleration_firstTriggerAtTimeZero_succeedsAndRespectsCooldownForSubsequentTriggers() {
        var shakeCount = 0
        val detector = ShakeDetector(sensorManager = null, thresholdG = 2.7f, cooldownMs = 1000L) {
            shakeCount++
        }

        // t=0ms: 첫 번째 유효 입력 감지 (0ms 시점에서도 쿨다운으로 억제되지 않고 감지 성공해야 함)
        val result1 = detector.processAcceleration(0f, 0f, gravityEarth * 3.0f, timestampMs = 0L)
        assertTrue("시각 0ms에서의 첫 감지는 성공해야 함", result1)
        assertEquals(1, shakeCount)

        // t=500ms (쿨다운 1000ms 미만 경과): 연속 입력 억제
        val result2 = detector.processAcceleration(0f, 0f, gravityEarth * 3.0f, timestampMs = 500L)
        assertFalse("쿨다운 시간 이내 입력은 억제되어야 함", result2)
        assertEquals(1, shakeCount)

        // t=1000ms (1000ms 쿨다운 경과): 재감지 성공
        val result3 = detector.processAcceleration(0f, 0f, gravityEarth * 3.0f, timestampMs = 1000L)
        assertTrue("쿨다운 경과 후 입력은 다시 감지되어야 함", result3)
        assertEquals(2, shakeCount)
    }

    @Test
    fun startAndStop_nullSensorManager_handlesSafely() {
        val detector = ShakeDetector(sensorManager = null) { }

        assertFalse("SensorManager가 null일 때 start()는 false를 반환해야 함", detector.start())
        assertFalse(detector.isListening)

        // stop() 호출 시 예외 없이 안전하게 수행되어야 함
        detector.stop()
        assertFalse(detector.isListening)
    }
}
