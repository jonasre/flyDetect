package no.uio.ifi.jonaspr.flydetect.ui.home

import androidx.lifecycle.ViewModel
import no.uio.ifi.jonaspr.flydetect.Util

class HomeViewModel : ViewModel() {

    fun generateFlightStatsMessage(map: Map<String, Int>?): String {
        if (map == null) return ""
        val takeoffMarker = 0 //TODO
        val takeoffMarkerMessage = Util.formatSeconds(takeoffMarker)
        val fStart = map["flightStart"]!!
        val tMarkerStartDiff = Util.formatSecondsSigned(fStart - takeoffMarker)
        val fStartDetect = map["flightStartDetect"]!!
        val fStartDetectDiff = Util.formatSecondsSigned(fStartDetect - fStart)
        val landingMarker = 0 //TODO
        val landingMarkerMessage = Util.formatSeconds(landingMarker)
        val fEnd = map["flightEnd"]!!
        val lMarkerEndDiff = Util.formatSecondsSigned(fEnd - landingMarker)
        val fEndDetect = map["flightEndDetect"]!!
        val fEndDetectDiff = Util.formatSecondsSigned(fEndDetect - fEnd)
        val fCount = map["flightCount"]!!

        var fStartMessage = "N/A"; var fStartDetectMessage = "N/A"
        var fEndMessage = "N/A"; var fEndDetectMessage = "N/A"
        if (fStart >= 0) {
            fStartMessage = "${Util.formatSeconds(fStart)} \n($tMarkerStartDiff from marker)"
            fStartDetectMessage = "${Util.formatSeconds(fStartDetect)}\n($fStartDetectDiff from " +
                    "data indicates flight)"
        }
        if (fEnd >= 0) {
            fEndMessage = "${Util.formatSeconds(fEnd)} \n($lMarkerEndDiff from marker)"
            fEndDetectMessage = "${Util.formatSeconds(fEndDetect)} \n($fEndDetectDiff from data " +
                    "indicates landing)"
        }

        return  "Takeoff marker: $takeoffMarkerMessage\n\n" +
                "Data indicates flight: $fStartMessage\n\n" +
                "Flight is detected: $fStartDetectMessage\n\n" +
                "Landing marker: $landingMarkerMessage\n\n" +
                "Data indicates landing: $fEndMessage\n\n" +
                "Landing is detected: $fEndDetectMessage\n\n" +
                "Number of flights detected: $fCount" + if (fCount > 1) " (displaying last)" else ""
    }
}