package com.example.fitboymk2

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Parcelable
import android.service.notification.StatusBarNotification
import kotlin.math.ceil

object NotificationParser {
    private fun String?.sanitize() = this?.replace("\u0000", " ") ?: " "

    fun parse(context: Context, sbn: StatusBarNotification, nId: Int): String? {
        if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null

        val extras = sbn.notification.extras
        val pm = context.packageManager

        val appName = try {
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))).toString().sanitize()
        } catch (e: Exception) { "Unknown" }

        val nTitle = extras.getString("android.title").sanitize()
        val nSubText = extras.getString("android.subText").sanitize()

        val msgBuilder = StringBuilder("$appName\u0000$nTitle\u0000$nSubText\u0000")

        if (extras.containsKey("android.messages")) {
            msgBuilder.append("T\u0000")
            val pArray = extras.getParcelableArray("android.messages", Parcelable::class.java)
            val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(pArray)

            var totalLines = 0
            for (message in messages) {
                val sender = message.senderPerson?.name?.toString() ?: "Unknown"
                val msgText = "$sender: ${message.text}"
                val numLines = ceil(msgText.length / 21.0).toInt()

                if (totalLines == 0 || (totalLines + numLines <= 6)) {
                    totalLines += numLines
                    msgBuilder.append(msgText.sanitize()).append("\n")
                }
            }
        } else {
            msgBuilder.append("D\u0000")
            val bigText = extras.getCharSequence("android.bigText")?.toString() ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""
            val formattedText = if (bigText.isNotEmpty()) bigText.take(255) else text.take(255)
            msgBuilder.append(formattedText.sanitize())
        }

        // Filter and append ID
        val filteredString = msgBuilder.toString().filter { it.code <= 255 }
        return "$filteredString\u0000${nId.toChar()}\u0000"
    }
}