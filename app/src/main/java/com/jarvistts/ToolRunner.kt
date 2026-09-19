package com.jarvistts

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Executes a parsed [ToolCall] and returns a short plain-text result for the model to
 *  phrase back to the user. Must be called on the main thread (starts activities).
 *  Side-effecting tools never act silently: email opens a prefilled draft the user
 *  has to send themselves.
 */
class ToolRunner(private val context: Context) {
    fun run(call: ToolCall): String =
        try {
            when (call.name) {
                "get_time" -> getTime()
                "get_battery" -> getBattery()
                "set_timer" -> setTimer(call.args["seconds"])
                "send_email" -> draftEmail(call.args)
                else -> "Unknown tool."
            }
        } catch (e: Exception) {
            "The tool failed: ${e.message}"
        }

    private fun getTime(): String =
        "It is " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("h:mm a 'on' EEEE, MMMM d", Locale.getDefault())) + "."

    private fun getBattery(): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "The battery is at $pct percent."
    }

    private fun setTimer(seconds: String?): String {
        val secs = seconds?.toIntOrNull()
        if (secs == null || secs <= 0) return "No valid duration was given."
        val intent =
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Timer set for ${Tools.describeDuration(secs)}."
    }

    private fun draftEmail(args: Map<String, String>): String {
        val to = Tools.normalizeSpokenEmail(args["to"].orEmpty())
        if (!Tools.isPlausibleEmail(to)) return "I could not make out a valid email address, so I did not make a draft."
        val intent =
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                .putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                .putExtra(Intent.EXTRA_SUBJECT, args["body"].orEmpty().split(" ").take(6).joinToString(" "))
                .putExtra(Intent.EXTRA_TEXT, args["body"].orEmpty())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "I have opened a draft to $to. Review it and press send."
    }
}
