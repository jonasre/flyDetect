package no.uio.ifi.jonaspr.flydetect.detectionservice

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

class DecisionComponent(accFrequency: Float, barFrequency: Float) {
    private var flying: Boolean = false
    private var roll: Boolean = false
    private var rollTimestamp: Long = 0L
    private var stablePressure: Float = 0f
    private val accBuffer = SensorEventRingBuffer(accFrequency)
    private val barBuffer = SensorEventRingBuffer(barFrequency)

    private var nextAccCheckTime: Long = 0
    private var nextBarCheckTime: Long = 0

    private var startTime = 0L

    private var nextCheckWindow: Int = (DEFAULT_CHECK_INTERVAL/1_000_000_000).toInt()

    // Adds an acceleration sample to its buffer
    fun addAccSample(event: SensorEvent) {
        accBuffer.insert(event)
        if (event.timestamp >= nextAccCheckTime) {
            checkAcc()
        }
    }

    // Adds a pressure sample to its buffer
    fun addBarSample(event: SensorEvent) {
        barBuffer.insert(event)
        if (event.timestamp >= nextBarCheckTime) {
            checkBar()
        }
    }

    // Sets the timestamp for last data check for both sensor types
    // This function should only be called once to initialize before sensor data is provided
    fun setStartTime(i: Long) {
        nextAccCheckTime = i + DEFAULT_CHECK_INTERVAL
        nextBarCheckTime = i + DEFAULT_CHECK_INTERVAL
        startTime = i
    }

    private fun checkAcc() {
        val window = accBuffer.getLatest(nextCheckWindow)
        val ma = movingAverage(window, MOVING_AVG_WINDOW_SIZE)
        var timeUntilNextCheck = DEFAULT_CHECK_INTERVAL
        // If something of interest is found, increase nextCheckWindow
        // this may be repeated until nextCheckWindow reaches the maximum size of the buffer (ish)
        // If nothing of interest is found, reset nextCheckWindow to its default value
        var i = 0
        if (ma.isEmpty()) {
            timeUntilNextCheck += window[0].timestamp - nextAccCheckTime
            Log.w(TAG, "Received empty moving average (gap in data?). Window: ${window.size}")
        }
        //if (ma.isNotEmpty())
            //Log.d(TAG, "checking from ${ma[0].first}, window: ${window.size}")
        while (i < ma.size) {
            if (!flying && !roll) {
                // Find first occurrence of acceleration withing takeoff roll range
                var rollStart = 0L
                while (i < ma.size) {
                    val (timestamp, value) = ma[i++]
                    if (value in TAKEOFF_ROLL_ACC_RANGE) {
                        rollStart = timestamp
                        break
                    }
                }

                var remainsInRange = rollStart != 0L
                // Keep iterating to check if acceleration remains withing the correct range
                while (i < ma.size && ma[i].first <= TAKEOFF_ROLL_TIME_MIN + rollStart) {
                    if (ma[i++].second !in TAKEOFF_ROLL_ACC_RANGE) {
                        // This means takeoff roll did not happen.
                        remainsInRange = false
                        break
                    }
                }

                // Acceleration was not within the range, restart loop
                if (!remainsInRange) continue

                // Did acceleration remain in range long enough that takeoff roll could've happened
                if (i >= ma.size) {
                    Log.d(TAG, "Some data looks like takeoff roll, awaiting more to confirm")
                    // Could be takeoff roll, but we ran out of data.
                    // Next check should be done sooner than normal
                    timeUntilNextCheck = DEFAULT_CHECK_INTERVAL / 2
                } else {
                    Log.i(TAG, "Takeoff roll detected at ${ma[i].first}")
                    roll = remainsInRange
                    rollTimestamp = ma[i].first
                }
            } else if (!flying && roll) {
                val latestTimeLiftoff = rollTimestamp + ROLL_LIFTOFF_MAX_DELAY
                while (i < ma.size) {
                    var liftoffEndTime = -1L
                    // Loop until the end of the buffer or until the max delay for liftoff
                    while (i < ma.size && ma[i].first < latestTimeLiftoff) {
                        // Find first occurrence of acceleration in liftoff range
                        if (ma[i].second in LIFTOFF_ACC_RANGE) {
                            liftoffEndTime = ma[i].first + LIFTOFF_TIME_MIN
                            break
                        }
                        i++
                    }

                    if (liftoffEndTime == -1L) {
                        // If latestTimeLiftoff is reached without finding a value in liftoff range
                        // In other words - no data was in liftoff range withing the time limit
                        // or there is a gap in the data
                        if (i < ma.size && ma[i].first >= latestTimeLiftoff) {
                            Log.i(TAG, "(STAGE 1) No liftoff detected, takeoff roll was " +
                                    "likely a false positive")
                            roll = false
                            break
                        }

                        // If there wasn't enough data to determine if liftoff happened or not
                        if (i >= ma.size) {
                            Log.i(TAG, "(STAGE 1) Not enough data in buffer to detect" +
                                    " liftoff, next check will be scheduled sooner")
                            timeUntilNextCheck = DEFAULT_CHECK_INTERVAL / 2
                            break
                        }
                    }

                    val startIterate = i
                    var remainsInRangeLiftoff = i < ma.size
                    while (i < ma.size && ma[i].first < liftoffEndTime) {
                        if (ma[i++].second !in LIFTOFF_ACC_RANGE) {
                            remainsInRangeLiftoff = false
                            break
                        }
                    }
                    val iterations = i - startIterate

                    // Data found wasn't in the liftoff range, restart loop
                    if (!remainsInRangeLiftoff) continue

                    // There wasn't enough data in the buffer to determine if liftoff happened
                    if (i >= ma.size) {
                        Log.i(TAG, "(STAGE 2) Not enough data in buffer to detect liftoff, " +
                                "next check will be scheduled sooner")
                        timeUntilNextCheck = DEFAULT_CHECK_INTERVAL / 2
                        break
                    }

                    // Liftoff possible, but too little data to be sure
                    if (iterations < MIN_EVENTS_LIFTOFF) {
                        Log.i(TAG, "(STAGE 2) Liftoff might've happened but there were too " +
                                "many gaps in the data to be sure. Takeoff roll now " +
                                "considered false positive")
                        roll = false
                        break
                    }


                    // If this point is reached then liftoff was detected
                    // takeoff roll + liftoff = flight
                    flying = true
                    Log.i(TAG, "Flight detected")
                    break

                }
            } else {
                break
            }
        }



        nextAccCheckTime += timeUntilNextCheck - (MOVING_AVG_WINDOW_SIZE * 1_000_000L)
    }

    private fun checkBar() {
        // ...
        val window = barBuffer.getLatest(nextCheckWindow)
        val variance = movingVariance(window, 10_000)
        var timeUntilNextCheck = DEFAULT_CHECK_INTERVAL

        var i = 0
        while (i < variance.size) {
            // Loop until end of array or until pressure has left its stable level
            while (
                i < variance.size &&
                abs(stablePressure - window[i].values[0]) < STABLE_PRESSURE_MIN_DIFF
            ) i++
            if (i >= variance.size) break // Stop if end of array was reached

            var stableStartTime = 0L
            // Loop until end of array or until first pressure under stable threshold is reached
            while (i < variance.size) {
                if (variance[i].second < STABLE_PRESSURE_THRESHOLD) {
                    stableStartTime = variance[i].first
                    break
                }
                i++
            }

            if (i >= variance.size) break

            // Loop until end of array or as long as variance is below stable threshold
            // Stop if variance is below stable threshold long enough to consider pressure as stable
            while (i < variance.size && variance[i].second < STABLE_PRESSURE_THRESHOLD) {
                if (stableStartTime + STABLE_PRESSURE_MIN_TIME <= variance[i].first) {
                    // Stable pressure detected
                    stablePressure = window[i].values[0]
                    Log.i(TAG, "Stable pressure detected ($stablePressure at " +
                            "${variance[i].first}, aka ${(variance[i].first-startTime)/1_000_000_000} s)")
                    if (variance[i].first != window[i].timestamp)
                        throw Exception("Timestamps in moving variance and window do not match")
                    break
                }
                i++
            }

            if (i >= variance.size){
                // End of array was reached while checking variance, schedule next check sooner
                Log.d(TAG, "End of array was reached while checking variance, " +
                        "scheduling next check sooner")
                timeUntilNextCheck /= 2
                break
            }

        }
        nextBarCheckTime += timeUntilNextCheck
    }

    private fun movingAverage(source: Array<SensorEvent>, ms: Int): Array<Pair<Long, Float>> {
        if (source.isEmpty()) return arrayOf()
        val ma = FloatArray(source.size)
        if (source[0].sensor.type == Sensor.TYPE_ACCELEROMETER) {
            // Calculate magnitude
            for (i in source.indices) {
                ma[i] = source[i].values.let { v -> sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]) }
            }
        } else {
            // sensor type is pressure
            for (i in source.indices) ma[i] = source[i].values[0]
        }

        val windowSize: Long = ms*1_000_000L
        var sum = 0f
        var top = 0
        var current = 0f
        val highestTimestamp = source[source.lastIndex].timestamp
        for (i in source.indices) {
            sum -= current
            current = ma[i]
            val ceil = source[i].timestamp + windowSize
            if (ceil > highestTimestamp) {
                // Return when there isn't enough events to calculate the average.
                // This is used to avoid noise at the end of the array
                return Array(i) {
                    Pair(source[it].timestamp, ma[it])
                }
            }
            // Find top index for window
            while (top < source.size && source[top].timestamp < ceil) {
                // add to the sum as we go
                sum += ma[top]
                top++
            }
            ma[i] = sum / (top - i)
        }
        return arrayOf() // for the compiler
    }

    private fun movingVariance(source: Array<SensorEvent>, ms: Int): Array<Pair<Long, Float>> {
        if (source.isEmpty()) return arrayOf()
        val mv = FloatArray(source.size)
        if (source[0].sensor.type == Sensor.TYPE_ACCELEROMETER) {
            // Calculate magnitude
            for (i in source.indices) {
                mv[i] = source[i].values.let { v -> sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]) }
            }
        } else {
            // sensor type is pressure
            for (i in source.indices) mv[i] = source[i].values[0]
        }

        val windowSize: Long = ms*1_000_000L
        var sum = 0f
        var top = 0
        var current = 0f
        val highestTimestamp = source[source.lastIndex].timestamp
        for (i in source.indices) {
            sum -= current
            current = mv[i]
            val ceil = source[i].timestamp + windowSize
            if (ceil > highestTimestamp) {
                // Return when there isn't enough events to calculate the average.
                // This is used to avoid noise at the end of the array
                return Array(i) {
                    Pair(source[it].timestamp, mv[it])
                }
            }
            // Find top index for window
            while (top < source.size && source[top].timestamp < ceil) {
                // add to the sum as we go
                sum += mv[top]
                top++
            }
            val mean = sum / (top - i)
            var total = 0f
            for (j in i..top) {
                val deviation = mean - mv[j]
                total += deviation * deviation
            }
            mv[i] = total/(top-i)
        }
        return arrayOf() // for the compiler
    }

    companion object {
        private const val TAG = "DC"
        private const val DEFAULT_CHECK_INTERVAL = 60_000_000_000 //nanoseconds (ns)

        /* Acceleration related constants */
        private const val MOVING_AVG_WINDOW_SIZE = 10_000 //milliseconds (ms)
        private const val TAKEOFF_ROLL_TIME_MIN = 20_000_000_000 //nanoseconds (ns)
        private const val LIFTOFF_TIME_MIN = 5_000_000_000 //nanoseconds (ns)
        private const val ROLL_LIFTOFF_MAX_DELAY = 26_000_000_000 //nanoseconds (ns)
        private const val MIN_EVENTS_LIFTOFF = (LIFTOFF_TIME_MIN/1_000_000_000) * 5
        private val TAKEOFF_ROLL_ACC_RANGE = 9.95..10.3 // m/s^2
        private val LIFTOFF_ACC_RANGE = 10.8..11.6 // m/s^2

        /* Pressure related constants */
        private const val STABLE_PRESSURE_THRESHOLD = 0.004f //variance
        private const val STABLE_PRESSURE_MIN_TIME = 10_000_000_000 //nanoseconds (ns)
        private const val STABLE_PRESSURE_MIN_DIFF = 3f //hPa
    }
}