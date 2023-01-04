package no.uio.ifi.jonaspr.flydetect

import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt

object Util {
    // Converts Hz to microseconds and vice versa
    fun convertHzMicroseconds(f: Int, rounded: Boolean = false) : Int {
        return convertHzMicroseconds(f.toFloat(), rounded)
    }

    // Converts Hz to microseconds and vice versa
    fun convertHzMicroseconds(f: Float, rounded: Boolean = false) : Int {
        if (f == 0f) return 0
        val mil = 1_000_000
        if (rounded) return (mil.toDouble()/f).roundToInt()
        return (mil/f).toInt()
    }

    // Formats seconds to hh:mm:ss
    fun formatSeconds(duration: Int): String {
        val hours = duration / 3600
        val minutes = (duration % 3600) / 60
        val seconds = duration % 60

        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    // Formats seconds to (+|-)hh:mm:ss
    fun formatSecondsSigned(duration: Int): String {
        val prefix = if (duration < 0) "-" else "+"
        return prefix + formatSeconds(abs(duration))
    }

    fun dateFormatTh(time: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = time
        val suffix = when (cal.get(Calendar.DAY_OF_MONTH) % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
        return SimpleDateFormat("MMMM d'$suffix' yyyy", Locale.ENGLISH).format(Date(time))
    }

}