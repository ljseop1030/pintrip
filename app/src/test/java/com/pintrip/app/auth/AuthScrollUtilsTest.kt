package com.pintrip.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthScrollUtilsTest {

    @Test
    fun `keyboard padding reduces visible viewport height and increases max scroll range`() {
        // Keyboard padding (paddingBottom 800px) reduces visible viewport height from 1500px to 700px,
        // which increases maxScrollY from 0px to 300px.
        val maxScrollY = computeMaxScrollY(
            contentHeight = 1000,
            scrollViewHeight = 1500,
            paddingTop = 0,
            paddingBottom = 800,
        )

        assertEquals(300, maxScrollY)
    }

    @Test
    fun `counter example where target at 900 to 1000px scrolls to maxScrollY 300px when keyboard padding is 800px`() {
        // contentHeight 1000, scrollViewHeight 1500, paddingBottom 800 (keyboard padding) -> visibleViewport 700, maxScrollY 300.
        // scrollY 0, targetTop 900, targetBottom 1000, marginPx 16.
        val targetScrollY = computeTargetScrollY(
            scrollY = 0,
            scrollViewHeight = 1500,
            paddingTop = 0,
            paddingBottom = 800,
            contentHeight = 1000,
            targetTop = 900,
            targetBottom = 1000,
            marginPx = 16,
        )

        // Target bottom 1000 + margin 16 = 1016. Desired scroll Y = 1016 - 700 = 316.
        // Clamped to maxScrollY (300).
        assertEquals(300, targetScrollY)
    }

    @Test
    fun `same content and scrollview height with padding zero returns maxScrollY 0 and no scroll`() {
        // contentHeight 1000, scrollViewHeight 1500, padding 0 -> maxScrollY 0
        val maxScrollY = computeMaxScrollY(
            contentHeight = 1000,
            scrollViewHeight = 1500,
            paddingTop = 0,
            paddingBottom = 0,
        )
        assertEquals(0, maxScrollY)

        val targetScrollY = computeTargetScrollY(
            scrollY = 0,
            scrollViewHeight = 1500,
            paddingTop = 0,
            paddingBottom = 0,
            contentHeight = 1000,
            targetTop = 900,
            targetBottom = 1000,
            marginPx = 16,
        )

        // Target is completely inside visible viewport 0..1500 -> no scroll needed
        assertNull(targetScrollY)
    }

    @Test
    fun `top padding and bottom padding are simultaneously accounted for in maxScrollY`() {
        // contentHeight 1200, scrollViewHeight 800, paddingTop 100, paddingBottom 300
        // visibleViewportHeight = 800 - 100 - 300 = 400
        // maxScrollY = 1200 - 400 = 800
        val maxScrollY = computeMaxScrollY(
            contentHeight = 1200,
            scrollViewHeight = 800,
            paddingTop = 100,
            paddingBottom = 300,
        )

        assertEquals(800, maxScrollY)
    }

    @Test
    fun `content smaller than visible viewport returns maxScrollY zero`() {
        val maxScrollY = computeMaxScrollY(
            contentHeight = 400,
            scrollViewHeight = 600,
            paddingTop = 0,
            paddingBottom = 0,
        )

        assertEquals(0, maxScrollY)
    }

    @Test
    fun `target obscured above scrollY 400 returns scroll up target 134`() {
        // contentHeight 2000, scrollViewHeight 500, paddingBottom 200, scrollY 400
        // visibleViewport 300 (400..700), target 150..250
        val targetScrollY = computeTargetScrollY(
            scrollY = 400,
            scrollViewHeight = 500,
            paddingTop = 0,
            paddingBottom = 200,
            contentHeight = 2000,
            targetTop = 150,
            targetBottom = 250,
            marginPx = 16,
        )

        assertEquals(134, targetScrollY)
    }

    @Test
    fun `target obscured at bottom returns minimal scroll down Y`() {
        // contentHeight 2000, scrollViewHeight 500, scrollY 0
        // visibleViewport 500, target 450..550
        val targetScrollY = computeTargetScrollY(
            scrollY = 0,
            scrollViewHeight = 500,
            paddingTop = 0,
            paddingBottom = 0,
            contentHeight = 2000,
            targetTop = 450,
            targetBottom = 550,
            marginPx = 16,
        )

        assertEquals(66, targetScrollY)
    }

    @Test
    fun `target completely visible returns null`() {
        val targetScrollY = computeTargetScrollY(
            scrollY = 100,
            scrollViewHeight = 500,
            paddingTop = 0,
            paddingBottom = 0,
            contentHeight = 2000,
            targetTop = 200,
            targetBottom = 300,
            marginPx = 16,
        )

        assertNull(targetScrollY)
    }

    @Test
    fun `target larger than visible viewport aligns to target top with margin`() {
        // height 500, visibleHeight = 400 (paddingBottom 100). targetHeight = 600 > 400.
        val targetScrollY = computeTargetScrollY(
            scrollY = 0,
            scrollViewHeight = 500,
            paddingTop = 0,
            paddingBottom = 100,
            contentHeight = 2000,
            targetTop = 200,
            targetBottom = 800,
            marginPx = 16,
        )

        assertEquals(184, targetScrollY)
    }

    @Test
    fun `viewport height less than or equal to zero returns null`() {
        val zeroViewport = computeTargetScrollY(
            scrollY = 0,
            scrollViewHeight = 500,
            paddingTop = 300,
            paddingBottom = 200,
            contentHeight = 2000,
            targetTop = 100,
            targetBottom = 200,
            marginPx = 16,
        )

        assertNull(zeroViewport)
    }
}
