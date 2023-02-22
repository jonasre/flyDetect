package no.uio.ifi.jonaspr.flydetect.detectionservice

import android.hardware.Sensor
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlin.math.abs
import kotlin.math.max

class DecisionComponent(
    private val service: DetectionService,
    accFrequency: Float,
    barFrequency: Float,
    landingDetectionMethod: String
) {
    private var flying = false
    private val flyingLive = MutableLiveData(false)
    private var roll: Boolean = false
    private var rollTimestamp: Long = 0L
    private var accOffset = 0f
    private val accBuffer: SensorDataRingBuffer
    private val barBuffer: SensorDataRingBuffer
    private val pressurePlateauDetectionMethod: (src: Array<Pair<Long, Float>>, index: Int) -> Int

    // The computed window size for pressure, compensated with the window size of moving average
    // and, potentially, variance
    private val computedBarWindow: Long

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

        pressurePlateauDetectionMethod = when (landingDetectionMethod) {
            "DERIVATIVE" -> ::detectPressurePlateauDerivative
            "MOVING_VAR" -> ::detectPressurePlateauVariance
            else -> throw java.lang.Exception("Illegal argument for landing detection method")
        }

        computedBarWindow = when (landingDetectionMethod) {
            "DERIVATIVE" -> DEFAULT_BAR_CHECK_INTERVAL + BAR_MOVING_AVG_WINDOW_SIZE*1_000_000L +
                    DERIVATIVE_TIME_STEP*1_000_000L
            "MOVING_VAR" -> DEFAULT_BAR_CHECK_INTERVAL + BAR_MOVING_AVG_WINDOW_SIZE*1_000_000L +
                    BAR_MOVING_VAR_WINDOW_SIZE*1_000_000L
            else -> throw java.lang.Exception("Illegal argument for landing detection method")
        }
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
    private var nextCheckWindowBar: Int = (computedBarWindow/1_000_000_000).toInt()

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
                Log.d(TAG, "[acc] Gap in data or rogue event at ${event.first} " +
                        "(aka ${asSeconds(event.first)} s)")
                checkAcc()
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
                Log.d(TAG, "[bar] Gap in data or rogue event at ${event.first} " +
                        "(aka ${asSeconds(event.first)} s)")
                checkBar()
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
        nextBarCheckTime = i + computedBarWindow
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
            lastPressurePlateau = Pair(0, 0f) // reset
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
        if (flying) throw IllegalStateException("checkAcc called when flying")
        val window = accBuffer.getLatest(nextCheckWindowAcc)
        val ma = movingAverage(window, ACC_MOVING_AVG_WINDOW_SIZE)
        val mv = movingVariance(window, ACC_MOVING_VAR_WINDOW_SIZE)

        nextAccCheckTime = window[window.lastIndex].first
        var timeUntilNextCheck = DEFAULT_ACC_CHECK_INTERVAL

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
                    "flying (approximate time: ${asSeconds(window[0].first)} s)")
            return
        }

        // Normalize data
        normalize(ma, mv)

        var i = 0
        while (i < ma.size) {
            if (!roll) {
                val takeoffRoll = takeoffRollDetection(ma, i)
                i = takeoffRoll
                when (takeoffRoll) {
                    -1 -> break
                    -2 -> {
                        // Could be takeoff roll, but we ran out of data.
                        Log.v(TAG, "End of buffer could contain takeoff roll, next check " +
                                "will be scheduled sooner")
                        // Schedule next check sooner than normal
                        timeUntilNextCheck /= 2
                        break
                    }
                    else -> {
                        Log.v(TAG, "Takeoff roll detected at ${ma[i].first} " +
                                "(aka ${asSeconds(ma[i].first)}s)")
                        roll = true
                        rollTimestamp = ma[i].first
                    }
                }
            } else {
                val latestTimeLiftoff = rollTimestamp + ROLL_LIFTOFF_MAX_DELAY
                val liftoff = liftoffDetection(ma, i, latestTimeLiftoff)
                i = liftoff
                when (liftoff) {
                    -1 -> {
                        Log.v(TAG, "No liftoff detected, takeoff roll was likely a " +
                                "false positive")
                        roll = false
                    }
                    -2 -> {
                        Log.v(TAG, "Not enough data in buffer to detect liftoff, next " +
                                "check will be scheduled sooner")
                        timeUntilNextCheck /= 2
                    }
                    else -> {
                        setFlyingStatus(true, ma[i].first, window[window.lastIndex].first)
                        roll = false
                        Log.i(TAG, "Flight detected at ${ma[i].first} (aka " +
                                "${asSeconds(ma[i].first)} s)")
                    }
                }
                break
            }
        }
        nextAccCheckTime += timeUntilNextCheck
    }

    private fun checkBar() {
        val window = barBuffer.getLatest(nextCheckWindowBar)
        val average = movingAverage(window, BAR_MOVING_AVG_WINDOW_SIZE)
        var timeUntilNextCheck = DEFAULT_BAR_CHECK_INTERVAL

        nextBarCheckTime = window[window.lastIndex].first

        var i = 0
        while (i < average.size && flying) {
            // Loop until end of array or until pressure has left its stable level
            while (
                i < average.size &&
                abs(lastPressurePlateau.second - average[i].second) <
                    STABLE_PRESSURE_MIN_DIFF
            ) i++
            if (i >= average.size) break // Stop if end of array was reached

            when (val plateauIndex = pressurePlateauDetectionMethod(average, i)) {
                -2 -> {
                    // End of data reached when pressure could be stable, schedule next check sooner
                    Log.v(TAG, "End of data was reached while pressure could be stable, " +
                            "scheduling next check sooner (time is " +
                            "${average[average.lastIndex].first} aka " +
                            "${asSeconds(average[average.lastIndex].first)}s)")
                    timeUntilNextCheck /= 2
                    break
                }
                -1 -> {
                    // Pressure is not stable
                    break
                }
                else -> {
                    // Stable pressure was found, register it at check if it was a landing
                    val landing = newPressurePlateau(
                        average[plateauIndex].first,
                        average[plateauIndex].second
                    )
                    if (landing) {
                        // Set flying status false if pressure indicates landing
                        setFlyingStatus(
                            false,
                            average[plateauIndex].first,
                            window[window.lastIndex].first
                        )
                    }
                    i = plateauIndex
                }
            }
        }
        nextBarCheckTime += timeUntilNextCheck
    }

    /**
     * Looks for a pressure plateau, i.e., where pressure is stable, using moving variance. The
     * moving variance is calculated from [movingAverage].
     *
     * @return
     * The index of the pressure plateau in [movingAverage], if found. Index calculated with
     * [startIndex].
     *
     * -2 if pressure might be stable at the end of [movingAverage], but we need more data.
     *
     * -1 if pressure is not stable.
     */
    private fun detectPressurePlateauVariance(
        movingAverage: Array<Pair<Long, Float>>,
        startIndex: Int
    ): Int {
        val variance = movingVariance(
            movingAverage.copyOfRange(startIndex, movingAverage.size),
            BAR_MOVING_VAR_WINDOW_SIZE
        )
        val ret = filterThresholdMin(variance, STABLE_PRESSURE_THRESHOLD, STABLE_PRESSURE_MIN_TIME)
        if (ret >= 0) return ret + startIndex
        return ret
    }

    /**
     * Looks for a pressure plateau, i.e., where pressure is stable, using derivative. The
     * derivative is calculated from [movingAverage].
     *
     * @return
     * The index of the pressure plateau in [movingAverage], if found. Index calculated with
     * [startIndex].
     *
     * -1 if pressure is not stable.
     */
    private fun detectPressurePlateauDerivative(
        movingAverage: Array<Pair<Long, Float>>,
        startIndex: Int
    ): Int {
        val derivatives = derivative(movingAverage.copyOfRange(startIndex, movingAverage.size))
        for (i in derivatives.indices) {
            if (abs(derivatives[i].second) <= STABLE_PRESSURE_DERIVATIVE_THRESHOLD)
                return i + startIndex
        }
        return -1
    }

    private fun normalize(ma: Array<Pair<Long, Float>>, mv: Array<Pair<Long, Float>>) {
        // Find acceleration offset
        val stableIndex = accStableIndex(mv)
        if (stableIndex >= 0) {
            accOffset = GRAVITY_ACC - ma[stableIndex].second
            Log.d(TAG, "accOffset now $accOffset (at ${asSeconds(ma[stableIndex].first)} s)")
        }
        // Apply normalization
        for (j in ma.indices) ma[j] = Pair(ma[j].first, ma[j].second+accOffset)
    }

    /**
     * Returns index in the provided moving variance array where acceleration is stable. Returns -1
     * if no such index is found
     */
    private fun accStableIndex(mv: Array<Pair<Long, Float>>): Int {
        return filterThresholdMin(mv, STABLE_ACC_VARIANCE_THRESHOLD, STABLE_ACC_MIN_TIME)
    }

    // Returns true/false if the provided moving variance is too high to indicate flying or not
    private fun noiseFilter(mv: Array<Pair<Long, Float>>): Boolean {
        return filterThresholdMin(
            mv,
            ACC_WALK_FILTER_THRESHOLD,
            ACC_WALK_MIN_TIME,
            false
        ) >= 0
    }

    private fun takeoffRollDetection(source: Array<Pair<Long, Float>>, startIndex: Int): Int {
        val ret = filterRangeMin(
            source.copyOfRange(startIndex, source.size),
            TAKEOFF_ROLL_ACC_RANGE,
            TAKEOFF_ROLL_TIME_MIN
        )
        return if (ret < 0) ret else ret + startIndex
    }

    private fun liftoffDetection(
        source: Array<Pair<Long, Float>>,
        startIndex: Int,
        deadline: Long
    ): Int {
        val ret = filterRangeMin(
            source.copyOfRange(startIndex, source.size),
            LIFTOFF_ACC_RANGE,
            LIFTOFF_TIME_MIN
        )
        // -1 if liftoff was detected but after the deadline
        if (ret >= 0 && source[ret + startIndex].first > deadline) return -1
        // -1 if acceleration was within the range at the end of the data, but deadline was exceeded
        if (ret == -2 && source[source.lastIndex].first > deadline) return -1
        // -2 if acceleration was not within the range but the deadline has not been reached
        if (ret == -1 && source[source.lastIndex].first < deadline) return -2
        return if (ret < 0) ret else ret + startIndex
    }

    /**
     * Utility function. Find index in [source] where values have been above/below [threshold]
     * (decided by [successIfBelowThreshold]) for a duration of at least [minTime].
     *
     * @return
     * The index where [source] was on the correct side of the [threshold] for the specified
     * [minTime], if it happens.
     *
     * -2 if [source] was on the correct side of the [threshold] but we ran out of data
     *
     * -1 if [source] was not on the correct side of the [threshold] and we ran out of data
     */
    private fun filterThresholdMin(
        source: Array<Pair<Long, Float>>,
        threshold: Float,
        minTime: Long,
        successIfBelowThreshold: Boolean = true
    ): Int {
        var i = 0
        var startTime = -1L
        val m = if (successIfBelowThreshold) 1 else -1
        val th = threshold*m
        while (i < source.size) {
            val (timestamp, value) = source[i++]
            if (value*m > th) {
                startTime = -1L
            } else if (startTime < 0) {
                startTime = timestamp
            } else if (timestamp - startTime >= minTime) {
                return i - 1
            }
        }
        // The data was on the correct side of the threshold when we ran out of data
        if (startTime >= 0) return -2
        // The data was not on the correct side of the threshold long enough, neither was it on the
        // correct side when we ran out of data
        return -1
    }

    /**
     * Utility function. Find index in [source] where values have been inside [range] for a duration
     * of at least [minTime].
     *
     * @return
     * The index where [source] was within the [range] for the specified [minTime], if it happens.
     *
     * -2 if [source] was on within the [range] but we ran out of data
     *
     * -1 if [source] was not within the [range] and we ran out of data
     */
    private fun filterRangeMin(
        source: Array<Pair<Long, Float>>,
        range: ClosedFloatingPointRange<Float>,
        minTime: Long
    ): Int {
        var i = 0
        var startTime = -1L
        while (i < source.size) {
            val (timestamp, value) = source[i++]
            if (value !in range) {
                startTime = -1L
            } else if (startTime < 0) {
                startTime = timestamp
            } else if (timestamp - startTime >= minTime) {
                return i - 1
            }
        }
        // The data was within the range when we ran out of data
        if (startTime >= 0) return -2
        // The data was not within the range long enough, neither was it on the within the range
        // when we ran out of data
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

    private fun derivative(source: Array<Pair<Long, Float>>): Array<Pair<Long, Float>> {
        if (source.isEmpty()) return arrayOf()
        val derivatives = FloatArray(source.size)

        val highestTimestamp = source[source.lastIndex].first
        var top = 0
        for (i in source.indices) {
            val ceil = source[i].first + DERIVATIVE_TIME_STEP
            // Find top index for window
            while (top < source.size && source[top].first < ceil) top++
            if (top > source.lastIndex || source[top].first > highestTimestamp) {
                // Return when the second point used in the derivative doesn't exist.
                return Array(i) {
                    Pair(source[it].first, derivatives[it])
                }
            }
            val sTop = source[top]
            val sCur = source[i]
            val timeDiffSeconds = (sTop.first - sCur.first)/1_000_000_000.toDouble()
            derivatives[i] = ((sTop.second - sCur.second) / timeDiffSeconds).toFloat()
        }
        return arrayOf() // for the compiler
    }

    /**
     * Intended for displaying status on UI
     *
     * @return
     * Time in seconds until next analysis of sensor data
     */
    fun secondsUntilNextAnalysis(): Int {
        val analysisTime = if (flying) nextBarCheckTime else nextAccCheckTime
        return ((analysisTime - SystemClock.elapsedRealtimeNanos())/1_000_000_000).toInt()
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
        private const val STABLE_ACC_VARIANCE_THRESHOLD = 0.005f

        private const val STABLE_ACC_MIN_TIME = 10_000_000_000 //nanoseconds (ns)

        // If variance is above this value then the acceleration can not be considered for takeoff
        private const val ACC_WALK_FILTER_THRESHOLD = 4f

        private const val ACC_WALK_MIN_TIME = 12_000_000_000 //nanoseconds (ns)

        // Acceleration must be within this range to qualify as takeoff roll
        private val TAKEOFF_ROLL_ACC_RANGE = 9.95f..10.42f // m/s^2

        // Acceleration must be within TAKEOFF_ROLL_ACC_RANGE for this amount of time to qualify as
        // takeoff roll
        private const val TAKEOFF_ROLL_TIME_MIN = 17_000_000_000 //nanoseconds (ns)

        // Acceleration must be within this range to qualify as liftoff
        private val LIFTOFF_ACC_RANGE = 10.6f..12.0f // m/s^2

        // Acceleration must be within LIFTOFF_ACC_RANGE for this amount of time to qualify as
        // liftoff
        private const val LIFTOFF_TIME_MIN = 5_000_000_000 //nanoseconds (ns)

        // Liftoff must be detected before this time has passed since takeoff roll was detected
        private const val ROLL_LIFTOFF_MAX_DELAY = 41_000_000_000 //nanoseconds (ns)

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

        // Time step for the derivative
        private const val DERIVATIVE_TIME_STEP = 1_000 //milliseconds (ms)

        // Pressure is considered stable once it goes below this threshold
        private const val STABLE_PRESSURE_DERIVATIVE_THRESHOLD = 0.015f //derivative

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
    }
}