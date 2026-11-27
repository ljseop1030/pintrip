package com.pintrip.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPermissionFlowTest {

    @Test
    fun determineAction_newInstall_requestsPermission() {
        val action = NotificationPermissionFlow.determineAction(
            isRuntimePermissionGranted = false,
            hasRequestedBefore = false,
            shouldShowRationale = false,
            areAppNotificationsEnabled = true,
            isChannelEnabled = true
        )
        assertEquals(NotificationPermissionAction.REQUEST_RUNTIME_PERMISSION, action)
    }

    @Test
    fun determineAction_deniedOnceWithRationale_requestsPermission() {
        val action = NotificationPermissionFlow.determineAction(
            isRuntimePermissionGranted = false,
            hasRequestedBefore = true,
            shouldShowRationale = true,
            areAppNotificationsEnabled = true,
            isChannelEnabled = true
        )
        assertEquals(NotificationPermissionAction.REQUEST_RUNTIME_PERMISSION, action)
    }

    @Test
    fun determineAction_permanentlyDenied_opensAppSettings() {
        val action = NotificationPermissionFlow.determineAction(
            isRuntimePermissionGranted = false,
            hasRequestedBefore = true,
            shouldShowRationale = false,
            areAppNotificationsEnabled = true,
            isChannelEnabled = true
        )
        assertEquals(NotificationPermissionAction.OPEN_APP_SETTINGS, action)
    }

    @Test
    fun determineAction_appNotificationsBlocked_opensAppSettings() {
        val action = NotificationPermissionFlow.determineAction(
            isRuntimePermissionGranted = true,
            hasRequestedBefore = true,
            shouldShowRationale = false,
            areAppNotificationsEnabled = false,
            isChannelEnabled = true
        )
        assertEquals(NotificationPermissionAction.OPEN_APP_SETTINGS, action)
    }

    @Test
    fun determineAction_channelBlocked_opensChannelSettings() {
        val action = NotificationPermissionFlow.determineAction(
            isRuntimePermissionGranted = true,
            hasRequestedBefore = true,
            shouldShowRationale = false,
            areAppNotificationsEnabled = true,
            isChannelEnabled = false
        )
        assertEquals(NotificationPermissionAction.OPEN_CHANNEL_SETTINGS, action)
    }

    @Test
    fun determineAction_fullyEnabled_showsGrantedToastAndSyncs() {
        val action = NotificationPermissionFlow.determineAction(
            isRuntimePermissionGranted = true,
            hasRequestedBefore = true,
            shouldShowRationale = false,
            areAppNotificationsEnabled = true,
            isChannelEnabled = true
        )
        assertEquals(NotificationPermissionAction.SHOW_GRANTED_TOAST_AND_SYNC, action)
    }
}
