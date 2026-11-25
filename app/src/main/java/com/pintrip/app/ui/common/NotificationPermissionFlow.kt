package com.pintrip.app.ui.common

enum class NotificationPermissionAction {
    SHOW_GRANTED_TOAST_AND_SYNC,
    REQUEST_RUNTIME_PERMISSION,
    OPEN_APP_SETTINGS,
    OPEN_CHANNEL_SETTINGS
}

object NotificationPermissionFlow {
    fun determineAction(
        isRuntimePermissionGranted: Boolean,
        hasRequestedBefore: Boolean,
        shouldShowRationale: Boolean,
        areAppNotificationsEnabled: Boolean,
        isChannelEnabled: Boolean
    ): NotificationPermissionAction {
        if (!isRuntimePermissionGranted) {
            return if (!hasRequestedBefore || shouldShowRationale) {
                NotificationPermissionAction.REQUEST_RUNTIME_PERMISSION
            } else {
                NotificationPermissionAction.OPEN_APP_SETTINGS
            }
        }
        if (!areAppNotificationsEnabled) {
            return NotificationPermissionAction.OPEN_APP_SETTINGS
        }
        if (!isChannelEnabled) {
            return NotificationPermissionAction.OPEN_CHANNEL_SETTINGS
        }
        return NotificationPermissionAction.SHOW_GRANTED_TOAST_AND_SYNC
    }
}
