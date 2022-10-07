package no.uio.ifi.jonaspr.flydetect.detectionservice

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.reflect.jvm.isAccessible

class CustomSensorManager(
    private val sensorManager: SensorManager,
    private val lines: List<String>
    ): SensorManagerInterface, Runnable {

    private val sensorMap = HashMap<Int, Sensor>() // Map sensor type with Sensor object
    private lateinit var listener: SensorEventListener // Will hold the listener
    private var running = false
    private var currentIndex = 0
    private var stop = false
    // Constructor for SensorEvents
    private val sensorEventConstructor =
        SensorEvent::class.constructors.toList()[0].apply { isAccessible = true }

    override fun getDefaultSensor(type: Int): Sensor {
        return sensorManager.getDefaultSensor(type)
    }

    override fun registerListener(
        listener: SensorEventListener,
        sensor: Sensor,
        samplingPeriodUs: Int,
        maxReportLatencyUs: Int
    ): Boolean {
        // Make sure only one is registered at the same time
        if (sensorMap.containsKey(sensor.type)) return false
        sensorMap[sensor.type] = sensor

        this.listener = listener

        if (!running) {
            running = true
            Thread(this).start()
        }
        return true
    }

    override fun unregisterListener(listener: SensorEventListener) {
        stop = true
    }

    override fun getReplayProgress(): Int {
        if (currentIndex > 0) {
            return ((currentIndex.toDouble()/lines.size)*100).toInt()
        }
        return 0
    }

    // Takes a SensorEvent in string format, and creates a SensorEvent from it
    private fun newSensorEvent(s: String): SensorEvent {
        val split = s.split(":")
        // Find the sensor type
        val sensorType = when (split.size) {
            2 -> Sensor.TYPE_PRESSURE
            4 -> Sensor.TYPE_ACCELEROMETER
            else ->
                throw Exception("SensorEventString does not match expected input. Received '$s'")
        }

        // Skip first element and convert the rest to float
        val newValues = split.drop(1).map{value -> value.toFloat()}

        // Create the new SensorEvent
        return sensorEventConstructor.call(newValues.size).apply {
            sensor = sensorMap[sensorType] // Set the sensor as determined by the type
            timestamp = split[0].toLong()*1_000_000 // Set timestamp
            for (i in newValues.indices) values[i] = newValues[i] // Set all values
        }
    }

    override fun run() {
        Thread.sleep(1000)

        // Skip headers and markers
        var expectedBlanks = 2
        currentIndex = 0
        while (expectedBlanks > 0) {
            if (lines[currentIndex] == "") {
                expectedBlanks--
            }
            currentIndex++
        }

        // At this point, the rest of the list should be data
        while (currentIndex < lines.size && !stop) {
            val event = newSensorEvent(lines[currentIndex])
            listener.onSensorChanged(event)
            currentIndex++
            //if (currentIndex % 10 == 0) Thread.sleep(1) // <- delay
        }
        if (currentIndex == lines.size) Log.i("csm", "Replay complete")
        else Log.i("csm", "Replay stopped prematurely")
        running = false
        stop = false
    }
}