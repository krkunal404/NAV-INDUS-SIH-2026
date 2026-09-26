package com.example.myapp.notifications

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Listens for incoming WhatsApp notifications so Nimo can read out the
 * latest message from a named contact on request.
 *
 * IMPORTANT LIMITATIONS (Android platform restrictions, not bugs):
 * - WhatsApp does not expose message history through any public API or
 *   content provider, so Nimo can only ever "read WhatsApp messages" by
 *   capturing notification text as it arrives — there is no way to fetch
 *   older messages sent before this listener was enabled and running.
 * - The user must manually grant "Notification access" for Nimo once in
 *   system Settings; this cannot be requested as a normal runtime
 *   permission dialog like microphone or contacts access.
 */
class WhatsAppNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "WaNotifListener"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
        private const val MAX_MESSAGES_PER_SENDER = 5

        // sender display name (lowercase) -> recent (timestamp, message text), newest last
        private val messagesBySender = ConcurrentHashMap<String, MutableList<Pair<Long, String>>>()

        /** Whether the user has granted Nimo notification access. */
        fun isEnabled(context: Context): Boolean {
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val myComponent = ComponentName(context, WhatsAppNotificationListener::class.java)
            return enabledListeners.contains(myComponent.flattenToString())
        }

        /** Opens the system screen where the user can grant notification access. */
        fun openAccessSettings(context: Context) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        /**
         * Returns (matchedSenderName, messageText) for the most recent
         * captured message whose sender name loosely matches [query].
         * If [query] is blank, returns the single most recent message
         * captured from any sender.
         */
        fun latestMessageFrom(query: String): Pair<String, String>? {
            val cleanQuery = query.lowercase().trim()

            if (cleanQuery.isBlank()) {
                return messagesBySender.entries
                    .mapNotNull { (sender, msgs) -> msgs.lastOrNull()?.let { Triple(sender, it.first, it.second) } }
                    .maxByOrNull { it.second }
                    ?.let { it.first to it.third }
            }

            val matchedSender = messagesBySender.keys.firstOrNull { it.contains(cleanQuery) }
                ?: return null
            val text = messagesBySender[matchedSender]?.lastOrNull()?.second ?: return null
            return matchedSender to text
        }

        private fun store(sender: String, text: String) {
            val key = sender.lowercase().trim()
            if (key.isBlank() || text.isBlank()) return
            val list = messagesBySender.getOrPut(key) { mutableListOf() }
            synchronized(list) {
                list.add(System.currentTimeMillis() to text)
                while (list.size > MAX_MESSAGES_PER_SENDER) {
                    list.removeAt(0)
                }
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        if (notification.packageName != WHATSAPP_PACKAGE &&
            notification.packageName != WHATSAPP_BUSINESS_PACKAGE
        ) {
            return
        }

        val extras = notification.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()

        if (title.isNullOrBlank() || text.isNullOrBlank()) return

        Log.d(TAG, "Captured WhatsApp notification from \"$title\"")
        store(title, text)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No-op: keep captured messages around until overwritten or restart.
    }
}