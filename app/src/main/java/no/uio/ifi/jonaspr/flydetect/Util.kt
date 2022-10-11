package no.uio.ifi.jonaspr.flydetect

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
}