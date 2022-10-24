package no.uio.ifi.jonaspr.flydetect.detectionservice

import android.hardware.SensorEvent

class DecisionComponent(accFrequency: Float, barFrequency: Float) {
    private val accBuffer = SensorEventRingBuffer(accFrequency)
    private val barBuffer = SensorEventRingBuffer(barFrequency)

    private var lastAccCheck: Long = 0
    private var lastBarCheck: Long = 0

    // Adds an acceleration sample to its buffer
    fun addAccSample(event: SensorEvent) {
        accBuffer.insert(event)
    }

    // Adds a pressure sample to its buffer
    fun addBarSample(event: SensorEvent) {
        barBuffer.insert(event)
    }

    // Sets the timestamp for last data check for both sensor types
    // This function should only be called once to initialize before sensor data is provided
    fun setStartTime(i: Long) {
        lastAccCheck = i
        lastBarCheck = i
    }
}