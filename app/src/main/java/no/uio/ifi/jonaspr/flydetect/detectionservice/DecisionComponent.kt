package no.uio.ifi.jonaspr.flydetect.detectionservice

import android.hardware.Sensor
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlin.math.abs
import kotlin.math.max

class DecisionComponent(
    private val service: DetectionService,
    accFrequency: Float,
    barFrequency: Float
) {
    private var flying = false
    private val flyingLive = MutableLiveData(false)
    private var roll: Boolean = false
    private var rollTimestamp: Long = 0L
    private var accOffset = 0f
    private val accBuffer: SensorDataRingBuffer
    private val barBuffer: SensorDataRingBuffer

    init {
        // Set appropriate buffer size. This is to avoid buffer overflow when using a sensor file
        // with a higher sampling rate than what is specified in the settings
        val sensorInjection = service.isUsingSensorInjection
        val accSize =
            if (sensorInjection) 100 * SECONDS_OF_ACC else (accFrequency * SECONDS_OF_ACC).toInt()
        val barSize =
            if (sensorInjection) 4 * SECONDS_OF_BAR else (barFrequency * SECONDS_OF_BAR).toInt()
        accBuffer = SensorDataRingBuffer(accSize)
        barBuffer = SensorDataRingBuffer(barSize)
    }

    private var flightStart = -1
    private var flightStartDetect = -1
    private var flightEnd = -1
    private var flightEndDetect = -1
    private var flightCount = 0

    // Pair of <timestamp, pressure> where pressure is stable.
    private var lastPressurePlateau: Pair<Long, Float> = Pair(0, 0f)

    private var nextAccCheckTime: Long = 0
    private var nextBarCheckTime: Long = 0

    private var startTime = 0L

    private var nextCheckWindowAcc: Int = (COMPUTED_ACC_WINDOW/1_000_000_000).toInt()
    private var nextCheckWindowBar: Int = (COMPUTED_BAR_WINDOW/1_000_000_000).toInt()

    fun currentlyFlying() = flyingLive.value!!
    fun flyingLiveData(): LiveData<Boolean> = flyingLive
    private fun asSeconds(t: Long): Int = ((t-startTime)/1_000_000_000).toInt()

    fun flightStats(): Map<String, Int> {
        return mapOf(
            "flightStart" to flightStart,
            "flightStartDetect" to flightStartDetect,
            "flightEnd" to flightEnd,
            "flightEndDetect" to flightEndDetect,
            "flightCount" to flightCount
        )
    }

    // Adds an acceleration sample to its buffer
    fun addAccSample(event: Pair<Long, Float>) {
        accBuffer.latestEntry()?.let {
            // If the time difference between the newest event and the one before it is greater
            // than ACC_MAX_DELAY, check the buffer before adding the new event
            if (event.first - it.first > ACC_MAX_DELAY) {
                checkAcc()
                Log.d(TAG, "[acc] Gap in data or rogue event at ${event.first} " +
                        "(aka ${asSeconds(event.first)} s)")
            }
        }
        accBuffer.insert(event) // Insert the new event
        if (event.first >= nextAccCheckTime) {
            // Check the buffer if scheduled check time is reached
            checkAcc()
        }
    }

    // Adds a pressure sample to its buffer
    fun addBarSample(event: Pair<Long, Float>) {
        barBuffer.latestEntry()?.let {
            // If the time difference between the newest event and the one before it is greater
            // than BAR_MAX_DELAY, check the buffer before adding the new event
            if (event.first - it.first > BAR_MAX_DELAY) {
                checkBar()
                Log.d(TAG, "[bar] Gap in data or rogue event at ${event.first} " +
                        "(aka ${asSeconds(event.first)} s)")
            }
        }
        barBuffer.insert(event) // Insert the new event
        if (event.first >= nextBarCheckTime) {
            // Check the buffer if scheduled check time is reached
            checkBar()
        }
    }

    // Sets the timestamp for last data check for both sensor types
    // This function should only be called once to initialize before sensor data is provided
    fun setStartTime(i: Long) {
        nextAccCheckTime = i + COMPUTED_ACC_WINDOW
        nextBarCheckTime = i + COMPUTED_BAR_WINDOW
        startTime = i
    }

    fun setFlyingStatus(x: Boolean) {
        setFlyingStatus(x, -1, -1)
    }

    private fun setFlyingStatus(x: Boolean, t1: Long, t2: Long) {
        if (x == flying) return // Do nothing if status is not changed
        flying = x
        flyingLive.postValue(x)

        val forced = t1 < 0
        service.notifyFlightStatusChange(flying, forced)

        // Register/unregister listeners accordingly
        if (flying) {
            service.registerSensorListener(Sensor.TYPE_PRESSURE)
            service.unregisterSensorListener(Sensor.TYPE_ACCELEROMETER)
            accBuffer.clear()
        } else {
            service.registerSensorListener(Sensor.TYPE_ACCELEROMETER)
            service.unregisterSensorListener(Sensor.TYPE_PRESSURE)
            barBuffer.clear()
        }

        // Stats variables
        if (forced) return
        if (flying) {
            flightCount++
            flightStart = asSeconds(t1)
            flightStartDetect = asSeconds(t2)
        } else {
            flightEnd = asSeconds(t1)
            flightEndDetect = asSeconds(t2)
        }
    }

    private fun checkAcc() {
        val window = accBuffer.getLatest(nextCheckWindowAcc)
        val ma = movingAverage(window, ACC_MOVING_AVG_WINDOW_SIZE)
        val mv = movingVariance(window, ACC_MOVING_VAR_WINDOW_SIZE)

        var timeUntilNextCheck = DEFAULT_ACC_CHECK_INTERVAL
        // If something of interest is found, increase nextCheckWindow
        // this may be repeated until nextCheckWindow reaches the maximum size of the buffer (ish)
        // If nothing of interest is found, reset nextCheckWindow to its default value
        var i = 0
        if (ma.isEmpty()) {
            timeUntilNextCheck += window[0].first - nextAccCheckTime
            Log.w(TAG, "Received empty moving average (gap in data?). Window: ${window.size}")
        }

        // Skip the rest of the check if the data is noisy (could be walking/running, and therefore
        // the user is not in an aircraft)
        val skip = noiseFilter(mv)
        if (skip) {
            nextAccCheckTime += timeUntilNextCheck
            Log.v(TAG, "Stopping acc check early because data is too noisy to indicate " +
                    "flying (approximate time: ${asSeconds(ma[i].first)} s)")
            return
        }

        // Find acceleration offset
        val stableIndex = accStableIndex(mv)
        if (stableIndex != -1) {
            accOffset = GRAVITY_ACC - ma[stableIndex].second
            Log.d(TAG, "accOffset now $accOffset (at ${asSeconds(ma[stableIndex].first)} s)")
        }

        // Apply normalization
        for (j in ma.indices) ma[j] = Pair(ma[j].first, ma[j].second+accOffset)

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
                    Log.v(TAG, "Some data looks like takeoff roll, awaiting more to confirm")
                    // Could be takeoff roll, but we ran out of data.
                    // Next check should be done sooner than normal
                    timeUntilNextCheck /= 2
                } else {
                    Log.v(TAG, "Takeoff roll detected at ${ma[i].first} " +
                            "(aka ${asSeconds(ma[i].first)}s)")
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
                            Log.v(TAG, "(STAGE 1) No liftoff detected, takeoff roll was " +
                                    "likely a false positive")
                            roll = false
                            break
                        }
                        // If there wasn't enough data to determine if liftoff happened or not
                        if (i >= ma.size) {
                            Log.v(TAG, "(STAGE 1) Not enough data in buffer to detect" +
                                    " liftoff, next check will be scheduled sooner")
                            timeUntilNextCheck /= 2
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
                        Log.v(TAG, "(STAGE 2) Not enough data in buffer to detect liftoff, " +
                                "next check will be scheduled sooner")
                        timeUntilNextCheck /= 2
                        break
                    }
                    // Liftoff possible, but too little data to be sure
                    if (iterations < MIN_EVENTS_LIFTOFF) {
                        Log.v(TAG, "(STAGE 2) Liftoff might've happened but there were too " +
                                "many gaps in the data to be sure. Takeoff roll now " +
                                "considered false positive")
                        roll = false
                        break
                    }
                    // If this point is reached then liftoff was detected
                    // takeoff roll + liftoff = flight
                    setFlyingStatus(true, ma[i].first, window[window.lastIndex].first)
                    roll = false
                    Log.i(TAG, "Flight detected at ${ma[i].first} (aka " +
                            "${asSeconds(ma[i].first)} s)")
                    break
                }
            } else {
                break
            }
        }
        nextAccCheckTime += timeUntilNextCheck
    }

    private fun checkBar() {
        val window = barBuffer.getLatest(nextCheckWindowBar)
        val average = movingAverage(window, BAR_MOVING_AVG_WINDOW_SIZE)
        val variance = movingVariance(average, BAR_MOVING_VAR_WINDOW_SIZE)
        var timeUntilNextCheck = DEFAULT_BAR_CHECK_INTERVAL

        var i = 0
        while (i < variance.size && flying) {
            // Loop until end of array or until pressure has left its stable level
            while (
                i < variance.size &&
                abs(lastPressurePlateau.second - average[i].second) <
                    STABLE_PRESSURE_MIN_DIFF
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
                    val landing = newPressurePlateau(variance[i].first, average[i].second)
                    if (landing)
                        // Set flying status false if pressure indicates landing
                        setFlyingStatus(false, variance[i].first, window[window.lastIndex].first)
                    if (variance[i].first != average[i].first)
                        throw Exception("Timestamps in moving variance and window do not match")
                    break
                }
                i++
            }

            if (i >= variance.size){
                // End of array was reached while checking variance, schedule next check sooner
                Log.v(TAG, "End of array was reached while checking variance, " +
                        "scheduling next check sooner (time is ${variance[i-1].first} aka " +
                        "${asSeconds(variance[i-1].first)}s)")
                timeUntilNextCheck /= 2
                break
            }
        }
        nextBarCheckTime += timeUntilNextCheck
    }

    private fun accStableIndex(mv: Array<Pair<Long, Float>>): Int {
        return filterThresholdMin(mv, STABLE_ACC_VARIANCE_THRESHOLD, STABLE_ACC_MIN_TIME)
    }

    private fun noiseFilter(mv: Array<Pair<Long, Float>>): Boolean {
        return filterThresholdMin(
            mv,
            ACC_WALK_FILTER_THRESHOLD,
            ACC_WALK_MIN_TIME,
            false
        ) >= 0
    }

    private fun filterThresholdMin(
        data: Array<Pair<Long, Float>>,
        threshold: Float,
        minTime: Long,
        aboveThreshold: Boolean = true
    ): Int {
        var i = 0
        var startTime = -1L
        val m = if (aboveThreshold) 1 else -1
        val th = threshold*m
        while (i < data.size) {
            val (timestamp, value) = data[i]
            i++
            if (value*m > th) {
                startTime = -1L
                continue
            } else if (startTime < 0) {
                startTime = timestamp
            } else if (timestamp - startTime >= minTime) {
                return i - 1
            }
        }
        return -1
    }

    private fun newPressurePlateau(t: Long, v: Float): Boolean {
        Log.v(TAG, "New pressure plateau registered ($v at $t, aka ${asSeconds(t)} s)")

        val diff = v - lastPressurePlateau.second
        val timeDiff = t - lastPressurePlateau.first
        lastPressurePlateau = Pair(t, v)
        if (timeDiff < LANDING_PRESSURE_MAX_TIME && diff in LANDING_PRESSURE_DIFF_RANGE) {
            Log.i(TAG, "Landing detected at $t (aka ${asSeconds(t)} s)")
            return true
        }
        return false
    }

    private fun movingAverage(source: Array<Pair<Long, Float>>, ms: Int): Array<Pair<Long, Float>> {
        if (source.isEmpty()) return arrayOf()
        val ma = FloatArray(source.size)

        val windowSize: Long = ms*1_000_000L
        var sum = 0f
        var top = 0
        var current = 0f
        for (i in source.indices) {
            sum -= current
            current = source[i].second
            val ceil = source[i].first + windowSize
            // Find top index for window
            while (top < source.size && source[top].first < ceil) {
                // add to the sum as we go
                sum += source[top].second
                top++
            }
            if (top > source.lastIndex ||
                (source[top].first - ceil)/windowSize.toDouble() > MAX_RELATIVE_MARGIN_CEIL
            ) {
                // Return when there isn't enough events to calculate the average.
                // This is used to avoid noise at the end of the array
                return Array(i) {
                    Pair(source[it].first, ma[it])
                }
            }
            ma[i] = sum / (top - i)
        }
        return arrayOf() // for the compiler
    }

    private fun movingVariance(source: Array<Pair<Long, Float>>, ms: Int): Array<Pair<Long, Float>> {
        if (source.isEmpty()) return arrayOf()
        val mv = FloatArray(source.size)

        val windowSize: Long = ms*1_000_000L
        var sum = 0f
        var top = 0
        var current = 0f
        for (i in source.indices) {
            sum -= current
            current = source[i].second
            val ceil = source[i].first + windowSize
            // Find top index for window
            while (top < source.size && source[top].first < ceil) {
                // add to the sum as we go
                sum += source[top].second
                top++
            }
            if (top > source.lastIndex ||
                (source[top].first - ceil)/windowSize.toDouble() > MAX_RELATIVE_MARGIN_CEIL) {
                // Return when there isn't enough events to calculate the average.
                // This is used to avoid noise at the end of the array
                return Array(i) {
                    Pair(source[it].first, mv[it])
                }
            }
            val mean = sum / (top - i)
            var total = 0f
            for (j in i..top) {
                val deviation = mean - source[j].second
                total += deviation * deviation
            }
            mv[i] = total/(top-i)
        }
        return arrayOf() // for the compiler
    }

    companion object {
        private const val TAG = "DC"
        private const val DEFAULT_ACC_CHECK_INTERVAL = 60_000_000_000 //nanoseconds (ns)
        private const val DEFAULT_BAR_CHECK_INTERVAL = 120_000_000_000 //nanoseconds (ns)
        private const val MAX_RELATIVE_MARGIN_CEIL = 0.1f

        /* Acceleration related constants */

        // Standard gravitational acceleration on earth
        private const val GRAVITY_ACC = 9.80665f // m/s^2

        // How many seconds of acceleration data should be stored in the buffer
        private const val SECONDS_OF_ACC = 120

        // If the gap between the newest event and the one before it exceeds this value, the buffer
        // before the newest event will be checked to avoid skipping data
        private const val ACC_MAX_DELAY = 2_000_000_000

        // Window size for moving average
        private const val ACC_MOVING_AVG_WINDOW_SIZE = 10_000 //milliseconds (ms)

        // Window size for moving variance
        private const val ACC_MOVING_VAR_WINDOW_SIZE = 10_000 //milliseconds (ms)

        // Variance must be below this value to consider acceleration stable
        private const val STABLE_ACC_VARIANCE_THRESHOLD = 0.004f

        private const val STABLE_ACC_MIN_TIME = 10_000_000_000 //nanoseconds (ns)

        // If variance is above this value then the acceleration can not be considered for takeoff
        private const val ACC_WALK_FILTER_THRESHOLD = 4f

        private const val ACC_WALK_MIN_TIME = 12_000_000_000 //nanoseconds (ns)

        // Acceleration must be within this range to qualify as takeoff roll
        private val TAKEOFF_ROLL_ACC_RANGE = 9.95..10.42 // m/s^2

        // Acceleration must be within TAKEOFF_ROLL_ACC_RANGE for this amount of time to qualify as
        // takeoff roll
        private const val TAKEOFF_ROLL_TIME_MIN = 20_000_000_000 //nanoseconds (ns)

        // Acceleration must be within this range to qualify as liftoff
        private val LIFTOFF_ACC_RANGE = 10.6..11.6 // m/s^2

        // Acceleration must be within LIFTOFF_ACC_RANGE for this amount of time to qualify as
        // liftoff
        private const val LIFTOFF_TIME_MIN = 5_000_000_000 //nanoseconds (ns)

        // Liftoff must be detected before this time has passed since takeoff roll was detected
        private const val ROLL_LIFTOFF_MAX_DELAY = 33_000_000_000 //nanoseconds (ns)

        // Liftoff cannot be detected without at least this many sensor samples
        private const val MIN_EVENTS_LIFTOFF = (LIFTOFF_TIME_MIN/1_000_000_000) * 5

        // The computed window size for acceleration, compensated with the window size of moving
        // average or variance (whichever is greater)
        private val COMPUTED_ACC_WINDOW = DEFAULT_ACC_CHECK_INTERVAL + max(
            ACC_MOVING_AVG_WINDOW_SIZE*1_000_000L, ACC_MOVING_VAR_WINDOW_SIZE*1_000_000L
        )


        /* Pressure related constants */

        // How many seconds of pressure data should be stored in the buffer
        private const val SECONDS_OF_BAR = 480

        // If the gap between the newest event and the one before it exceeds this value, the buffer
        // before the newest event will be checked to avoid skipping data
        private const val BAR_MAX_DELAY = 8_000_000_000

        // Variance must be below this value to qualify as stable
        private const val STABLE_PRESSURE_THRESHOLD = 0.004f //variance

        // Variance must be below STABLE_PRESSURE_THRESHOLD for this amount of time to qualify as
        // stable
        private const val STABLE_PRESSURE_MIN_TIME = 8_700_000_000 //nanoseconds (ns)

        // Difference from last stable pressure value must be at least this to qualify as a new
        // plateau
        private const val STABLE_PRESSURE_MIN_DIFF = 1.2f //hPa

        // Window size for moving average/variance
        private const val BAR_MOVING_AVG_WINDOW_SIZE = 30_000 //milliseconds (ms)
        private const val BAR_MOVING_VAR_WINDOW_SIZE = 10_000 //milliseconds (ms)

        // Change in pressure must be within this range to qualify as landing
        private val LANDING_PRESSURE_DIFF_RANGE = -23f..-2f //hPa

        // Stable pressure before landing can't last longer than this to qualify as pre-landing
        // pressure
        private const val LANDING_PRESSURE_MAX_TIME = 620_000_000_000 //nanoseconds (ns)

        // The computed window size for pressure, compensated with the window size of moving average
        // and variance
        private const val COMPUTED_BAR_WINDOW = DEFAULT_BAR_CHECK_INTERVAL +
                BAR_MOVING_AVG_WINDOW_SIZE*1_000_000L + BAR_MOVING_VAR_WINDOW_SIZE*1_000_000L
    }
}