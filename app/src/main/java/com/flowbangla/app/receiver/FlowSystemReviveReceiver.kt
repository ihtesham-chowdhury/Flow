package com.flowbangla.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.flowbangla.app.service.FloatingBubbleService
import com.flowbangla.app.util.PermissionHelper

class FlowSystemReviveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_USER_PRESENT ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val perms = PermissionHelper.checkAllPermissions(context)
            if (perms.allCriticalGranted) {
                FloatingBubbleService.startIfPermitted(context)
            }
        }
    }
}
