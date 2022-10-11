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
import no.uio.ifi.jonaspr.flydetect.Util
import no.uio.ifi.jonaspr.flydetect.`interface`.Failable


class DeveloperViewModel : ViewModel() {
    private val _sensorFileUri = MutableLiveData<Uri?>()
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

    val sensorFileUri: LiveData<Uri?> = _sensorFileUri
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
    fun fileName(resolver: ContentResolver, uri: Uri): String? {
        resolver.query(uri, null, null, null, null)!!.apply {
            return getString(getColumnIndex(OpenableColumns.DISPLAY_NAME).also { moveToFirst() })
                .also { close() }
        }
    }

    // Get information about the sensor file
    fun fetchSensorFileInfo(resolver: ContentResolver, uri: Uri?, fail: Failable? = null) {
        if (uri == null) {
            _sensorFileUri.postValue(null)
            resetText()
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            _loadingFile.postValue(true)
            try {
                var lines: List<String>
                with(resolver.openInputStream(uri)) {
                    lines = this?.bufferedReader()?.readLines()!!
                }

                if (lines.size < 10) {
                    return@launch // Something is wrong with the file
                }

                val startTime = lines[1].toLong()
                val endTime = lines[lines.lastIndex].split(":")[0].toLong()
                val duration = endTime - startTime

                var markersLocal = ""

                var i = 2
                while (lines[++i] != "") {
                    lines[i].split(";").let {
                        val timestamp = (it[1].toLong()-startTime)/1000
                        markersLocal += "(${Util.formatSeconds(timestamp.toInt())}) ${it[0]}\n"
                    }
                }

                val sensorEvents = lines.subList(i+1, lines.size).sortedBy {
                    it.split(":")[0].toLong()
                }

                val samplesCount = sensorEvents.size
                i = -1

                val acc = ArrayList<Long>()
                val bar = ArrayList<Long>()
                var hole = 0L
                var prevAcc = 0L
                var prevBar = 0L
                while (++i < sensorEvents.size-1) {
                    val split = sensorEvents[i].split(":")
                    val timestamp = split[0].toLong()
                    when (split.size) {
                        2 -> {
                            if (bar.size < 10) bar.add(timestamp)
                            if (prevBar != 0L && timestamp-prevBar > 1000) {
                                hole += timestamp-prevBar
                            }
                            prevBar = timestamp
                        }
                        4 -> {
                            if (acc.size < 10) acc.add(timestamp)
                            if (prevAcc != 0L && timestamp-prevAcc > 1000) {
                                hole += timestamp-prevAcc
                            }
                            prevAcc = timestamp
                        }
                    }

                }

                val accRate = calculateRate(acc)
                val barRate = calculateRate(bar)

                val qualityLocal: Float = (duration.toFloat()-(hole/2))/duration

                //Log.d("dvm", "ar:$accRate, br:$barRate, ")

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
                _sensorFileUri.postValue(uri)
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