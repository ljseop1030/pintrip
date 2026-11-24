package com.pintrip.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthScrollUtilsTest {

    @Test
    fun `counter example from prompt where target is obscured above scrollY 400 returns scroll up target 134`() {
        // Parent top 100, height 500, paddingBottom 200, scrollY 400, target 150..250
        val targetScrollY = computeTargetScrollY(
            scrollY = 400,
            scrollViewHeight = 500,
            paddingTop = 0,
            paddingBottom = 200,
            maxScrollY = 1000,
            targetTop = 150,
            targetBottom = 250,
            marginPx = 16,
        )

        // Target top is at 150, minus margin 16 = 134, which is less than visibleTop (400).
        // Minimal scroll up Y is 134.
        assertEquals(134, targetScrollY)
    }

    @Test
    fun `target obscured at bottom returns minimal scroll down Y`() {
        val targetScrollY = computeTargetScrollY(
            scrollY = 0,
            scrollViewHeight = 500,
            paddingTop = 0,
            paddingBottom = 0,
            maxScrollY = 1000,
            targetTop = 450,
            targetBottom = 550,
            marginPx = 16,
        )

        // Target bottom 550 + margin 16 = 566. Visible bottom is 500.
        // Scroll down Y = 566 - 500 = 66.
        assertEquals(66, targetScrollY)
    }

    @Test
    fun `target completely visible returns null`() {
        val targetScrollY = computeTargetScrollY(
            scrollY = 100,
            scrollViewHeight = 500,
            paddingTop = 0,
            paddingBottom = 0,
            maxScrollY = 1000,
            targetTop = 200,
            targetBottom = 300,
            marginPx = 16,
        )

        assertNull(targetScrollY)
    }

    @Test
    fun `top padding and IME bottom padding are correctly accounted for`() {
        // height 600, paddingTop 50, paddingBottom 250 (IME keyboard), scrollY 0
        // visible viewport is 50..350
        val targetScrollY = computeTargetScrollY(
            scrollY = 0,
            scrollViewHeight = 600,
            paddingTop = 50,
            paddingBottom = 250,
            maxScrollY = 1000,
            targetTop = 320,
            targetBottom = 380,
            marginPx = 16,
        )

        // Target bottom 380 + margin 16 = 396. Visible bottom is 600 - 250 = 350.
        // Target scroll Y = 396 + 250 - 600 = 46.
        assertEquals(46, targetScrollY)
    }

    @Test
    fun `target larger than visible viewport aligns to target top with margin`() {
        // height 500, visibleHeight = 400 (paddingBottom 100). targetHeight = 600 > 400.
        val targetScrollY = computeTargetScrollY(
            scrollY = 0,
            scrollViewHeight = 500,
            paddingTop = 0,
            paddingBottom = 100,
            maxScrollY = 1000,
            targetTop = 200,
            targetBottom = 800,
            marginPx = 16,
        )

        // Target top 200 - margin 16 = 184.
        assertEquals(184, targetScrollY)
    }

    @Test
    fun `clamping at top boundary 0 and bottom boundary maxScrollY`() {
        // Near top boundary
        val topClamped = computeTargetScrollY(
            scrollY = 200,
            scrollViewHeight = 500,
            paddingTop = 0,
            paddingBottom = 0,
            maxScrollY = 1000,
            targetTop = 5,
            targetBottom = 20,
            marginPx = 16,
        )
        // 5 - 16 = -11, clamped to 0
        assertEquals(0, topClamped)

        // Near bottom boundary
        val bottomClamped = computeTargetScrollY(
            scrollY = 0,
            scrollViewHeight = 500,
            paddingTop = 0,
            paddingBottom = 0,
            maxScrollY = 400,
            targetTop = 800,
            targetBottom = 900,
            marginPx = 16,
        )
        // Requested 900 + 16 - 500 = 416, clamped to maxScrollY 400
        assertEquals(400, bottomClamped)
    }

    @Test
    fun `viewport height less than or equal to zero returns null`() {
        val zeroViewport = computeTargetScrollY(
            scrollY = 0,
            scrollViewHeight = 500,
            paddingTop = 300,
            paddingBottom = 200,
            maxScrollY = 1000,
            targetTop = 100,
            targetBottom = 200,
            marginPx = 16,
        )

        assertNull(zeroViewport)
    }
}
