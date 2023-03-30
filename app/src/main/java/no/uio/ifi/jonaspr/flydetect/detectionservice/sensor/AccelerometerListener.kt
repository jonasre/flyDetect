package no.uio.ifi.jonaspr.flydetect.detectionservice.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import no.uio.ifi.jonaspr.flydetect.detectionservice.DecisionComponent
import kotlin.math.sqrt

class AccelerometerListener(
    private val decisionComponent: DecisionComponent
) : SensorEventListener {
    var latest = 0f
    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            // Calculate magnitude
            latest = it.values.let { v -> sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]) }
            // Send to decisionComponent
            decisionComponent.addAccSample(Pair(it.timestamp, latest))
        }
    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {}
}