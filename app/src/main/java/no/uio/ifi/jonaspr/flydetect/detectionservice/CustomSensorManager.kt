package no.uio.ifi.jonaspr.flydetect.detectionservice

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.reflect.jvm.isAccessible

class CustomSensorManager(
    private val sensorManager: SensorManager,
    private var lines: List<String>,
    private val resample: Boolean = false
): SensorManagerInterface, Runnable {

    private val sensorMap = HashMap<Int, Sensor>() // Map sensor type with Sensor object
    private val samplingPeriodNsMap = HashMap<Int, Long>() // Map for holding sampling periods
    private val listeners = HashMap<SensorEventListener, Int>() // Holds the listeners
    private val listenerByTypeMap = HashMap<Int, SensorEventListener>() //SensorType:Listener
    private val eventCount = HashMap<Int, Int>() //SensorType:NumberOfEvents
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
        if (!eventCount.containsKey(sensor.type)) eventCount[sensor.type] = 0
        samplingPeriodNsMap[sensor.type] = samplingPeriodUs*1000L

        listeners[listener] = sensor.type
        listenerByTypeMap[sensor.type] = listener

        if (!running) {
            running = true
            Thread(this).start()
        }
        return true
    }

    override fun unregisterListener(listener: SensorEventListener) {
        val type = listeners[listener]
        listeners.remove(listener)
        listenerByTypeMap.remove(type)
        if (listeners.isEmpty()) {
            stop = true
        }
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
        Thread.sleep(200)
        // Skip headers and markers
        var i = 0
        while (lines[i].indexOf(":") == -1) i++
        // At this point, the rest of the list should be data
        lines = lines.subList(i, lines.size)

        if (resample) {
            resample(lines)
        } else {
            while (currentIndex < lines.size && !stop) {
                val event = newSensorEvent(lines[currentIndex])
                sendSensorEvent(event)
                currentIndex++
                //if (currentIndex % 10 == 0) Thread.sleep(1) // <- delay
            }
        }

        if (currentIndex == lines.size) Log.i(TAG, "Replay complete")
        else Log.i(TAG, "Replay stopped prematurely")
        Log.d(TAG, "Forwarded ${eventCount[Sensor.TYPE_ACCELEROMETER]} accelerometer and " +
                "${eventCount[Sensor.TYPE_PRESSURE]} barometer samples")
        running = false
        stop = false
    }

    // Run sensor data through the listener, but resamples the data using linear interpolation
    private fun resample(lines: List<String>) {
        val sensorEventStrings = lines.sortedBy {
            it.substring(0, it.indexOf(":")).toLong()
        }
        // Hashmap for holding the next timestamp for each sensor
        val nextTimestampMap = HashMap<Int, Long>().apply {
            put(Sensor.TYPE_ACCELEROMETER, 0L)
            put(Sensor.TYPE_PRESSURE, 0L)
        }

        // Hashmap for holding the previous SensorEvent for each sensor
        val lastEventMap = HashMap<Int, SensorEvent?>().apply {
            put(Sensor.TYPE_ACCELEROMETER, null)
            put(Sensor.TYPE_PRESSURE, null)
        }

        // Iterate through all sensor event strings
        currentIndex = 0
        while (currentIndex < sensorEventStrings.size && !stop) {
            // Create a SensorEvent object for this line
            val cur = newSensorEvent(sensorEventStrings[currentIndex++])
            // Load the previous SensorEvent related to this one (cur), and save cur as previous
            // for the next iteration
            val prev: SensorEvent? = lastEventMap[cur.sensor.type]
            lastEventMap[cur.sensor.type] = cur

            // If this is the first event for this sensor type, or if there is a hole in the data
            if (prev == null || cur.timestamp - prev.timestamp > 1_000_000_000) {
                // Send the SensorEvent to the listener
                sendSensorEvent(cur)
                // Update when next SensorEvent should be inserted
                nextTimestampMap[cur.sensor.type] =
                    cur.timestamp + samplingPeriodNsMap[cur.sensor.type]!!
                continue
            }
            // If the timestamp of this event (cur) is greater or equal to the next scheduled event
            while (cur.timestamp >= nextTimestampMap[cur.sensor.type]!!) {
                // Send the new SensorEvent to the listener
                sendSensorEvent(newSensorEventAt(nextTimestampMap[cur.sensor.type]!!, prev, cur))
                // Update when next SensorEvent should be inserted
                nextTimestampMap[cur.sensor.type] =
                    nextTimestampMap[cur.sensor.type]!! + samplingPeriodNsMap[cur.sensor.type]!!
            }
        }
    }

    private fun newSensorEventAt(
        timestamp: Long,
        event1: SensorEvent,
        event2: SensorEvent
    ): SensorEvent {
        // If the timestamp matches one of the given events, there is no need to create a new one
        when (timestamp) {
            event1.timestamp -> return event1
            event2.timestamp -> return event2
        }
        // Assert that event1.timestamp < timestamp < event2.timestamp
        if (event1.timestamp > timestamp || event2.timestamp < timestamp)
            throw Exception(
                "SensorEvent timestamps not accepted (Event1: ${event1.timestamp}, " +
                        "Event2: ${event2.timestamp}, Requested: $timestamp)"
            )
        if (event1.sensor != event2.sensor)
            throw Exception("Sensor types are not equal for the supplies SensorEvents")

        // When this point is reached, we know that the arguments are valid
        val deltaX = event2.timestamp - event1.timestamp // Find difference in timestamps
        val deltaY = FloatArray(event1.values.size) // Create array for difference in values
        // Find difference in values
        for (j in deltaY.indices) deltaY[j] = event2.values[j] - event1.values[j]

        // If event1.timestamp was 0 and event2.timestamp was 1, where would timestamp be?
        val a = (timestamp - event1.timestamp)/deltaX.toDouble()

        // Create the new SensorEvent
        return sensorEventConstructor.call(deltaY.size).apply {
            sensor = event1.sensor
            this.timestamp = timestamp
            for (j in values.indices) // Calculate the new values
                values[j] = (event1.values[j] + (a*deltaY[j])).toFloat()
        }
    }

    private fun sendSensorEvent(event: SensorEvent) {
        val listener = listenerByTypeMap[event.sensor.type]
        if (listener != null) {
            listenerByTypeMap[event.sensor.type]?.onSensorChanged(event)
            val count = eventCount[event.sensor.type]
            if (count != null) eventCount[event.sensor.type] = count + 1
        }
    }

    companion object {
        private const val TAG = "CSM"
    }
}