package no.uio.ifi.jonaspr.flydetect.detectionservice

import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class SensorManagerWrapper(private val sensorManager: SensorManager): SensorManagerInterface {
    override fun getDefaultSensor(type: Int): Sensor {
        return sensorManager.getDefaultSensor(type)
    }

    override fun registerListener(
        listener: SensorEventListener,
        sensor: Sensor,
        samplingPeriodUs: Int,
        maxReportLatencyUs: Int
    ): Boolean {
        return sensorManager.registerListener(
            listener, sensor, samplingPeriodUs, maxReportLatencyUs
        )
    }

    override fun unregisterListener(listener: SensorEventListener) {
        sensorManager.unregisterListener(listener)
    }

    override fun getReplayProgress(): Int {
        // Not in use in this class
        return 0
    }
}