package no.uio.ifi.jonaspr.flydetect.ui.developer

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.uio.ifi.jonaspr.flydetect.Util
import no.uio.ifi.jonaspr.flydetect.`interface`.Failable


class DeveloperViewModel : ViewModel() {
    private val _sensorFileTitle = MutableLiveData<String>()
    private val _duration = MutableLiveData<String>()
    private val _samples = MutableLiveData<Int>()
    private val _quality = MutableLiveData<Float>()
    private val _accelerometerRate = MutableLiveData<Int>()
    private val _barometerRate = MutableLiveData<Int>()
    private val _markers = MutableLiveData<String>()
    private val _loadingFile = MutableLiveData<Boolean>().apply {
        value = false
    }

    val sensorFileTitle: LiveData<String> = _sensorFileTitle
    val duration: LiveData<String> = _duration
    val samples: LiveData<Int> = _samples
    val quality: LiveData<Float> = _quality
    val accelerometerRate: LiveData<Int> = _accelerometerRate
    val barometerRate: LiveData<Int> = _barometerRate
    val markers: LiveData<String> = _markers
    val loadingFile: LiveData<Boolean> = _loadingFile

    init {
        resetText()
    }

    /* Gets the filename from a Uri */
    fun fileName(resolver: ContentResolver, uri: Uri): String {
        resolver.query(uri, null, null, null, null)?.apply {
            return getString(getColumnIndex(OpenableColumns.DISPLAY_NAME).also { moveToFirst() })
                .also { close() }
        }
        return "UNKNOWN FILENAME"
    }

    // Get information about the sensor file
    fun fetchSensorFileInfo(
        resolver: ContentResolver,
        uri: Uri?,
        fail: Failable? = null,
        callback: (uri: Uri?, markers: Map<String, Int>?) -> Unit = { _, _ -> }
    ) {
        if (uri == null) {
            callback(null, mapOf())
            resetText()
            return
        }
        CoroutineScope(Dispatchers.Default).launch {

            _loadingFile.postValue(true)
            try {
                var lines: List<String>
                withContext(Dispatchers.IO) {
                    with(resolver.openInputStream(uri)) {
                        lines = this?.bufferedReader()?.readLines()!!
                    }
                }

                if (lines.size < 10) {
                    return@launch // Something is wrong with the file
                }

                val startTime = lines[1].toLong()
                val endTime = lines[lines.lastIndex].split(":")[0].toLong()
                val duration = endTime - startTime

                var markersLocal = ""
                val markersMap = HashMap<String, Int>()

                var i = 2
                if (lines[i + 1] == "") i++
                while (lines[++i] != "") {
                    lines[i].split(";").let {
                        val timestamp = ((it[1].toLong()-startTime)/1000).toInt()
                        markersLocal += "(${Util.formatSeconds(timestamp)}) ${it[0]}\n"
                        markersMap.put(it[0].lowercase(), timestamp)
                    }
                }
                val sensorEvents = lines.subList(i+1, lines.size).sortedBy {
                    it.substring(0, it.indexOf(":")).toLong()
                }

                val samplesCount = sensorEvents.size
                i = -1

                val acc = ArrayList<Long>()
                val bar = ArrayList<Long>()
                var hole = 0L
                var prevAcc = 0L
                var prevBar = 0L
                while (++i < sensorEvents.size-1) {
                    val firstColonIndex = sensorEvents[i].indexOf(":")
                    val timestamp = sensorEvents[i].substring(0, firstColonIndex).toLong()
                    if (timestamp < 1000) continue // low timestamps are usually incorrect, skip
                    when (sensorEvents[i].substring(firstColonIndex + 1).count { it == ':' }) {
                        0 -> {
                            // We use (i > sensorEvents.size/10) to skip the first samples.
                            // This is to avoid irregular sampling frequencies at the start of the
                            // recording.
                            if (i > sensorEvents.size/10 && bar.size < 4) bar.add(timestamp)
                            if (prevBar != 0L && timestamp-prevBar > 1000) {
                                hole += timestamp-prevBar
                            }
                            prevBar = timestamp
                        }
                        2 -> {
                            if (i > sensorEvents.size/10 && acc.size < 4) acc.add(timestamp)
                            if (prevAcc != 0L && timestamp-prevAcc > 1000) {
                                hole += timestamp-prevAcc
                            }
                            prevAcc = timestamp
                        }
                    }
                }

                val accRate = calculateRate(acc)
                val barRate = calculateRate(bar)
                var sensorCount = 0
                if (acc.isNotEmpty()) sensorCount++
                if (bar.isNotEmpty()) sensorCount++

                // In very rare circumstances, it is possible to get division by zero here
                val qualityLocal: Float = (duration.toFloat()-(hole/sensorCount))/duration

                _sensorFileTitle.postValue(lines[0])
                _duration.postValue(Util.formatSeconds((duration/1000).toInt()))
                _samples.postValue(samplesCount)
                _quality.postValue(qualityLocal)
                _accelerometerRate.postValue(
                    Util.convertHzMicroseconds(accRate*1000, true)
                )
                _barometerRate.postValue(
                    Util.convertHzMicroseconds(barRate*1000, true)
                )
                _markers.postValue(markersLocal)

                // If this point is reached, then the file is probably ok.
                // The following line confirms to the rest of the app that the file is ready.
                callback(uri, markersMap)
            } catch (e: Exception) {
                // something went wrong while loading
                fail?.onFailure()
            }
            _loadingFile.postValue(false)
        }
    }

    private fun resetText() {
        _sensorFileTitle.postValue("")
        _duration.postValue("")
        _samples.postValue(0)
        _quality.postValue(0f)
        _accelerometerRate.postValue(0)
        _barometerRate.postValue(0)
        _markers.postValue("")
    }

    private fun calculateRate(list: List<Long>): Int {
        if (list.size < 2) return 0
        var sum = 0L
        for (j in 0 until list.size-1) {
            sum += list[j+1] - list[j]
        }
        return (sum/(list.size-1)).toInt()
    }

}