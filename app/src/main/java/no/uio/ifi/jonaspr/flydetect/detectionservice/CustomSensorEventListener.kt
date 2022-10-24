package no.uio.ifi.jonaspr.flydetect.detectionservice

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.util.Log

class CustomSensorEventListener(
    private val decisionComponent: DecisionComponent
) : SensorEventListener {
    var latestAcc = floatArrayOf(0f, 0f, 0f)
    var latestBar = 0f
    var accCount = 0
    var barCount = 0

    fun resetCounters() {
        accCount = 0
        barCount = 0
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val v = event?.values!!
        val t : Long = event.timestamp/1_000_000 //Nanoseconds to milliseconds

        when (event.sensor?.type) {
            // Pressure data
            Sensor.TYPE_PRESSURE -> {
                decisionComponent.addBarSample(event)
                latestBar = v[0]
                barCount++
                //Log.d(TAG, "SensorEvent PRESSURE: $t@${v[0]}")
            }
            // Accelerometer data
            Sensor.TYPE_ACCELEROMETER -> {
                decisionComponent.addAccSample(event)
                latestAcc = v
                accCount++
                //Log.d(TAG, "SensorEvent ACCELEROMETER: $t@${v[0]}:${v[1]}:${v[2]}")
            }
            else -> {
                Log.w(TAG, "SensorEvent UNKNOWN")
            }
        }
    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {}

    companion object {
        private const val TAG = "CustomSensorEventListener"
    }
}