package app.tally.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import app.tally.data.ExpenseStore
import app.tally.parser.WalletParser

/**
 * Watches for Google Wallet's "you paid X at Y" notifications after a tap-to-pay purchase.
 * Every other app's notifications are ignored without being read.
 */
class WalletListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in WalletParser.SOURCE_PACKAGES) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        // Play services posts plenty of unrelated stuff; only log it when it looks like money.
        val parsed = WalletParser.parse(title, text)
        if (sbn.packageName == "com.google.android.gms" && parsed == null) return

        ExpenseStore.init(applicationContext)
        ExpenseStore.onWalletNotification(sbn.packageName, sbn.key, sbn.postTime, title, text, parsed)
    }

    companion object {
        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun openSettings(context: Context) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        fun openAppInfo(context: Context) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        fun requestRebind(context: Context) {
            if (isEnabled(context)) {
                NotificationListenerService.requestRebind(ComponentName(context, WalletListenerService::class.java))
            }
        }
    }
}
