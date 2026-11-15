package com.pintrip.app.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinkUtilsTest {

    @Test
    fun buildInviteLink_formatsCorrectUri() {
        val link = DeepLinkUtils.buildInviteLink("k7p2qx")
        assertEquals("pintrip://join?code=K7P2QX", link)
    }

    @Test
    fun extractInviteCode_validUriString_returnsNormalizedCode() {
        val extracted = DeepLinkUtils.extractInviteCode("pintrip://join?code=k7p2qx")
        assertEquals("K7P2QX", extracted)
    }

    @Test
    fun extractInviteCode_validUriWithSlashPath_returnsCode() {
        val extracted = DeepLinkUtils.extractInviteCode("pintrip://join/?code=k7p2qx")
        assertEquals("K7P2QX", extracted)
    }

    @Test
    fun extractInviteCode_caseInsensitiveSchemeAndHost_returnsCode() {
        val extracted = DeepLinkUtils.extractInviteCode("PINTRIP://JOIN?code=B8R3SY")
        assertEquals("B8R3SY", extracted)
    }

    @Test
    fun extractInviteCode_invalidScheme_returnsNull() {
        assertNull(DeepLinkUtils.extractInviteCode("https://join?code=K7P2QX"))
    }

    @Test
    fun extractInviteCode_invalidHost_returnsNull() {
        assertNull(DeepLinkUtils.extractInviteCode("pintrip://invite?code=K7P2QX"))
    }

    @Test
    fun extractInviteCode_nonStandardPath_returnsNull() {
        assertNull(DeepLinkUtils.extractInviteCode("pintrip://join/extra/path?code=K7P2QX"))
    }

    @Test
    fun extractInviteCode_duplicateCodeParam_returnsNull() {
        assertNull(DeepLinkUtils.extractInviteCode("pintrip://join?code=K7P2QX&code=B8R3SY"))
    }

    @Test
    fun extractInviteCode_uriWithUserInfo_returnsNull() {
        assertNull(DeepLinkUtils.extractInviteCode("pintrip://user:pass@join?code=K7P2QX"))
    }

    @Test
    fun extractInviteCode_uriWithPort_returnsNull() {
        assertNull(DeepLinkUtils.extractInviteCode("pintrip://join:8080?code=K7P2QX"))
    }

    @Test
    fun extractInviteCode_uriWithFragment_returnsNull() {
        assertNull(DeepLinkUtils.extractInviteCode("pintrip://join?code=K7P2QX#section"))
    }

    @Test
    fun extractInviteCode_missingCodeParam_returnsNull() {
        assertNull(DeepLinkUtils.extractInviteCode("pintrip://join"))
    }

    @Test
    fun extractInviteCode_invalidCodeCharacters_returnsNull() {
        // '0', 'O', '1', 'I' are forbidden in invite code policy
        assertNull(DeepLinkUtils.extractInviteCode("pintrip://join?code=K7P2Q0"))
        assertNull(DeepLinkUtils.extractInviteCode("pintrip://join?code=K7P2QO"))
    }

    @Test
    fun extractInviteCode_invalidCodeLength_returnsNull() {
        assertNull(DeepLinkUtils.extractInviteCode("pintrip://join?code=ABC23"))
        assertNull(DeepLinkUtils.extractInviteCode("pintrip://join?code=ABC2345"))
    }
}
