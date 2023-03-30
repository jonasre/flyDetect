package no.uio.ifi.jonaspr.flydetect.flightdata

data class Flight(val start: Long) {
    var forcedStart: Boolean = false
    var end: Long = 0
    var forcedEnd: Boolean = false
}
