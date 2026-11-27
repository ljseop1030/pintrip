package com.pintrip.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class KoreanJosaTest {

    @Test
    fun subjectParticle_withBatchim_returnsI() {
        assertEquals("이", KoreanJosa.subjectParticle("복게옥"))
        assertEquals("이", KoreanJosa.subjectParticle("신발원"))
    }

    @Test
    fun subjectParticle_withoutBatchim_returnsGa() {
        assertEquals("가", KoreanJosa.subjectParticle("태종대유원지"))
    }

    @Test
    fun subjectParticle_nonHangul_returnsFallback() {
        assertEquals("이(가)", KoreanJosa.subjectParticle("PinTrip"))
    }

    @Test
    fun subjectParticle_emptyString_returnsFallback() {
        assertEquals("이(가)", KoreanJosa.subjectParticle(""))
    }
}
