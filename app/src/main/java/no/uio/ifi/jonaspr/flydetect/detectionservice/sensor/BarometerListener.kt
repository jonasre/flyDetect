package no.uio.ifi.jonaspr.flydetect.detectionservice.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import no.uio.ifi.jonaspr.flydetect.detectionservice.DecisionComponent

class BarometerListener(
    private val decisionComponent: DecisionComponent
) : SensorEventListener {
    var latest = 0f
    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            latest = it.values[0]
            // Send to decisionComponent
            decisionComponent.addBarSample(Pair(it.timestamp, latest))
        }
    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {}
}