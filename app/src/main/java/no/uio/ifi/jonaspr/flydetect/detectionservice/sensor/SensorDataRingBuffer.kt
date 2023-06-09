package no.uio.ifi.jonaspr.flydetect.detectionservice.sensor

class SensorDataRingBuffer(size: Int) {
    private val array: Array<Pair<Long, Float>?>
    private var topIndex: Int

    init {
        array = arrayOfNulls(size)
        topIndex = prevIndex(0)
    }

    fun latestEntry() = array[topIndex]

    // Insert a SensorEvent object
    fun insert(event: Pair<Long, Float>) {
        if (compareEvents(event, array[topIndex]) >= 0) {
            // The timestamp of this event is either equal or greater to the newest
            topIndex = nextIndex(topIndex)
            array[topIndex] = event
        } else if (compareEvents(event, array[nextIndex(topIndex)]) > 0) {
            // The timestamp is not the oldest in the buffer, it must be saved
            var insertionPoint = binarySearch(event)
            val move = distanceBetween(insertionPoint, topIndex)
            if (move < 0) {
                insertionPoint = prevIndex(insertionPoint)
            }
            makeSpace(insertionPoint, move)
            array[insertionPoint] = event
        }
    }

    // Get the last n seconds of sensor events
    fun getLatest(seconds: Int): Array<Pair<Long, Float>> {
        if (array[topIndex] == null) return arrayOf()
        val latestTimestamp = array[topIndex]!!.first
        val timestampCutoff = latestTimestamp - (seconds * TIMESTAMP_MULTIPLIER)
        val cutoffIndex = binarySearch(timestampCutoff)
        return getRange(cutoffIndex, topIndex)
    }

    fun getLatestUntil(timestamp: Long): Array<Pair<Long, Float>> {
        if ((array[topIndex]?.first ?: Long.MAX_VALUE) < timestamp) return emptyArray()
        val cutoffIndex = binarySearch(timestamp)
        array[cutoffIndex]?.let {
            val prev = prevIndex(cutoffIndex)
            if ((array[prev]?.first ?: Long.MIN_VALUE) > timestamp && timestamp != -1L)
                throw IllegalStateException("There might have been loss of data")
        }
        return getRange(cutoffIndex, topIndex)
    }

    @Suppress("UNCHECKED_CAST")
    private fun getRange(a: Int, b: Int): Array<Pair<Long, Float>> {
        if (a !in 0..array.size || b !in 0..array.size)
            throw IndexOutOfBoundsException(
                "Argument(s) (a = $a, b = $b) outside allowed range (0..${array.size})"
            )

        return if (a > b) {
            concatArrays(
                array.copyOfRange(a, array.size) as Array<Pair<Long, Float>>,
                array.copyOfRange(0, b + 1) as Array<Pair<Long, Float>>
            )
        } else {
            array.copyOfRange(a, b + 1) as Array<Pair<Long, Float>>
        }
    }

    fun clear() {
        array.fill(null)
        //topIndex = prevIndex(0)
    }

    // Moves elements to make space for an element at index insertionPoint,
    // the oldest element is discarded. The value of distance determines
    // which way the elements should move.
    private fun makeSpace(insertionPoint: Int, distance: Int) {
        val zeroIndex = nextIndex(topIndex)
        if (distance == 0) {
            array[zeroIndex] = array[topIndex]
            topIndex = zeroIndex
        } else if (distance > 0) {
            var lastIndex = zeroIndex
            val stop = prevIndex(insertionPoint)
            var i = topIndex
            while (i != stop) {
                array[lastIndex] = array[i]
                lastIndex = i
                i = prevIndex(i)
            }
            topIndex = nextIndex(topIndex)
        } else {
            var lastIndex = zeroIndex
            val stop = nextIndex(insertionPoint)
            var i = nextIndex(lastIndex)
            while (i != stop) {
                array[lastIndex] = array[i]
                lastIndex = i
                i = nextIndex(i)
            }
        }
    }

    // Returns the index where e should be inserted
    private fun binarySearch(e: Pair<Long, Float>): Int {
        return binarySearch(e.first)
    }

    private fun binarySearch(timestamp: Long): Int {
        var low = 0
        var high = array.size - 1
        while (low <= high) {
            val mid = (low + high) / 2
            val unNormMid = unNormalizeIndex(mid)
            val compare = compareEvents(timestamp, array[unNormMid])
            if (compare < 0) {
                high = mid - 1
            } else if (compare > 0) {
                low = mid + 1
            } else {
                return unNormMid
            }
        }
        return unNormalizeIndex(low)
    }

    // Finds the shortest distance between two indices
    // Positive integer means that the shortest distance is from left to right,
    // negative integer means that the shortest distance is from right to left.
    private fun distanceBetween(a: Int, b: Int): Int {
        val positive: Int
        val negative: Int
        if (a > b) {
            positive = array.size - a + b
            negative = a - b
        } else {
            positive = b - a
            negative = array.size - b + a
        }
        return if (negative < positive) negative * -1 else positive
    }

    // Takes an index i where the array is assumed to have its lowest value at index 0,
    // returns the corresponding index where the lowest value in the array is at nextIndex(topIndex)
    private fun unNormalizeIndex(i: Int): Int {
        val zeroIndex = nextIndex(topIndex)
        var temp = zeroIndex + i
        if (temp >= array.size) {
            temp -= array.size
        }
        return temp
    }

    fun verifySorted(): Boolean {
        var last = nextIndex(topIndex)
        var i = nextIndex(last)
        while (i != topIndex) {
            if (array[last] != null && compareEvents(array[last]!!, array[i]) > 0) return false
            last = i
            i = nextIndex(i)
        }
        return true
    }

    // Returns the next valid index after i
    private fun nextIndex(i: Int): Int {
        return (i + 1) % array.size
    }

    // Returns the previous valid index before i
    private fun prevIndex(i: Int): Int {
        return (i + array.size - 1) % array.size
    }

    private fun compareEvents(e1: Pair<Long, Float>, e2: Pair<Long, Float>?): Int {
        return compareEvents(e1.first, e2)
    }

    private fun compareEvents(timestamp: Long, e2: Pair<Long, Float>?): Int {
        return if (e2 == null) 1 else timestamp.compareTo(e2.first)
    }

    @Suppress("UNCHECKED_CAST")
    private fun concatArrays(
        a: Array<Pair<Long, Float>>,
        b: Array<Pair<Long, Float>>
    ): Array<Pair<Long, Float>> {
        val result = a.copyOf(a.size + b.size) as Array<Pair<Long, Float>>
        System.arraycopy(b, 0, result, a.size, b.size)
        return result
    }

    override fun toString(): String {
        return array.contentToString()
    }

    companion object {
        private const val TIMESTAMP_MULTIPLIER = 1_000_000_000L
    }
}