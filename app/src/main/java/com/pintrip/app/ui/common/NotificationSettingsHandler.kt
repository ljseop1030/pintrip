package com.pintrip.app.ui.common

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationManagerCompat
import com.pintrip.app.R
import com.pintrip.app.notification.FriendNotificationService

object NotificationSettingsHandler {

    private const val TAG = "NotificationSettingsHandler"

    fun isNotificationEnabled(context: Context): Boolean {
        FriendNotificationService.createNotificationChannel(context)

        val isRuntimeGranted = NotificationPermissionHelper.isNotificationPermissionGranted(context)
        val areAppNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()

        var isChannelEnabled = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = manager?.getNotificationChannel(FriendNotificationService.CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                isChannelEnabled = false
            }
        }

        return isRuntimeGranted && areAppNotificationsEnabled && isChannelEnabled
    }

    fun showNotificationSettingsDialog(context: Context, isChannel: Boolean) {
        val intent = if (isChannel && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, FriendNotificationService.CHANNEL_ID)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.notice_notification_blocked_title)
            .setMessage(R.string.notice_notification_blocked_message)
            .setPositiveButton(R.string.btn_open_settings) { _, _ ->
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to launch notification settings: ${e.message}")
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    fun executeNotificationPermissionFlow(
        activity: Activity,
        launcher: ActivityResultLauncher<String>,
        onGranted: () -> Unit,
        onStateChanged: (() -> Unit)? = null
    ) {
        val context = activity.applicationContext
        FriendNotificationService.createNotificationChannel(context)

        val isGranted = NotificationPermissionHelper.isNotificationPermissionGranted(activity)
        val prefs = activity.getSharedPreferences("pintrip_device_prefs", Context.MODE_PRIVATE)
        val hasRequestedBefore = prefs.getBoolean("requested_notif_perm", false)
        val shouldShowRationale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            false
        }

        val appNotificationsEnabled = NotificationManagerCompat.from(activity).areNotificationsEnabled()
        var isChannelEnabled = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = manager?.getNotificationChannel(FriendNotificationService.CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                isChannelEnabled = false
            }
        }

        val action = NotificationPermissionFlow.determineAction(
            isRuntimePermissionGranted = isGranted,
            hasRequestedBefore = hasRequestedBefore,
            shouldShowRationale = shouldShowRationale,
            areAppNotificationsEnabled = appNotificationsEnabled,
            isChannelEnabled = isChannelEnabled
        )

        when (action) {
            NotificationPermissionAction.REQUEST_RUNTIME_PERMISSION -> {
                // TODO: 21 알림 권한 다이얼로그 노출 후 권한 요청
                prefs.edit().putBoolean("requested_notif_perm", true).apply()
                NotificationPermissionHelper.requestNotificationPermission(activity, launcher) {
                    Toast.makeText(activity, R.string.notice_notification_enabled, Toast.LENGTH_SHORT).show()
                    onGranted()
                    onStateChanged?.invoke()
                }
            }
            NotificationPermissionAction.OPEN_APP_SETTINGS -> {
                showNotificationSettingsDialog(activity, isChannel = false)
                onStateChanged?.invoke()
            }
            NotificationPermissionAction.OPEN_CHANNEL_SETTINGS -> {
                showNotificationSettingsDialog(activity, isChannel = true)
                onStateChanged?.invoke()
            }
            NotificationPermissionAction.SHOW_GRANTED_TOAST_AND_SYNC -> {
                Toast.makeText(activity, R.string.notice_notification_enabled, Toast.LENGTH_SHORT).show()
                onGranted()
                onStateChanged?.invoke()
            }
        }
    }
}
