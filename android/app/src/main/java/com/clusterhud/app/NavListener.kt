package com.clusterhud.app

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Passes the latest turn info from the listener service to the screen. */
object Hud { @Volatile var visible = false }

object NavBus {
    var listener: ((JSONObject) -> Unit)? = null
    var last: JSONObject? = null
    fun post(j: JSONObject) {
        last = j
        listener?.invoke(j)
    }
}

/**
 * Watches for Google Maps' ongoing navigation notification and extracts the
 * turn arrow, distance, instruction and ETA line from it.
 */
class NavListener : NotificationListenerService() {

    companion object {
        const val MAPS = "com.google.android.apps.maps"
    }

    private var activeKey: String? = null

    override fun onListenerConnected() {
        try {
            activeNotifications?.firstOrNull { isNav(it) }?.let { handle(it) }
        } catch (e: Exception) { /* ignore */ }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (isNav(sbn)) handle(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == activeKey) {
            activeKey = null
            NavBus.post(JSONObject().put("active", false))
        }
    }

    private fun isNav(sbn: StatusBarNotification): Boolean =
        sbn.packageName == MAPS && (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0

    private fun handle(sbn: StatusBarNotification) {
        val n = sbn.notification
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        if (title.isEmpty() && text.isEmpty()) return

        activeKey = sbn.key
        val j = JSONObject()
            .put("active", true)
            .put("title", title)
            .put("text", text)
            .put("sub", sub)
        iconBase64(n)?.let { j.put("icon", it) }
        NavBus.post(j)
    }

    private fun iconBase64(n: Notification): String? = try {
        val d = n.getLargeIcon()?.loadDrawable(this)
        if (d == null) null else {
            val size = 160
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, size, size)
            d.draw(Canvas(bmp))
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    } catch (e: Exception) { null }
}
