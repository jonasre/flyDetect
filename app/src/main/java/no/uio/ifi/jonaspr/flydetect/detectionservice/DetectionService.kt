package no.uio.ifi.jonaspr.flydetect.detectionservice

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build.VERSION
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.uio.ifi.jonaspr.flydetect.*
import no.uio.ifi.jonaspr.flydetect.flightdata.Flight
import no.uio.ifi.jonaspr.flydetect.flightdata.Storage
import no.uio.ifi.jonaspr.flydetect.detectionservice.sensor.*

class DetectionService : Service() {
    private val binder = DetectionServiceBinder(this)
    var sensorManager: SensorManagerInterface? = null
    lateinit var accListener: AccelerometerListener
    lateinit var barListener: BarometerListener
    lateinit var decisionComponent: DecisionComponent
    private var markers: Map<String, Int>? = null
    private var currentFlight: Flight? = null

    private var accSamplingFrequency = -1f
    private var barSamplingFrequency = -1f

    var isUsingSensorInjection = false

    fun getStats() = decisionComponent.flightStats() + (markers ?: mapOf())

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Start command received")
        running = true

        accSamplingFrequency = intent!!.getFloatExtra("accSamplingFrequency", -1f)
        barSamplingFrequency = intent.getFloatExtra("barSamplingFrequency", -1f)

        val landingDetectionMethod = intent.getStringExtra("landingDetectionMethod")!!
        val normalize = intent.getBooleanExtra("normalize", true)

        // Get sensorFile for sensor injection (optional)
        // intent.getParcelableExtra(key) is deprecated since API level 33
        val sensorFile: Uri?
        if (VERSION.SDK_INT >= 33) {
            sensorFile = intent.getParcelableExtra("sensorFile", Uri::class.java)
            @Suppress("UNCHECKED_CAST")
            markers = intent.getSerializableExtra("markers", HashMap::class.java)
                    as Map<String, Int>?
        } else @Suppress("DEPRECATION") {
            sensorFile = intent.getParcelableExtra("sensorFile")
            @Suppress("UNCHECKED_CAST")
            markers = intent.getSerializableExtra("markers") as Map<String, Int>?

        }
        if (sensorFile != null) isUsingSensorInjection = true

        val resample = intent.getBooleanExtra("resampleSensorFile", true)

        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).let {
            decisionComponent = DecisionComponent(
                this,
                Util.convertHzMicroseconds(it.getDefaultSensor(Sensor.TYPE_ACCELEROMETER).minDelay),
                Util.convertHzMicroseconds(it.getDefaultSensor(Sensor.TYPE_PRESSURE).minDelay),
                landingDetectionMethod,
                normalize
            )
        }

        accListener = AccelerometerListener(decisionComponent)
        barListener = BarometerListener(decisionComponent)

        CoroutineScope(Dispatchers.Default).launch {
            // Use CustomSensorManager if sensor data should be injected,
            // else use default SensorManager
            sensorManager = if (sensorFile != null) {
                withContext(Dispatchers.IO) {
                    val inputStream = contentResolver.openInputStream(sensorFile)
                    val l = inputStream!!.bufferedReader().readLines()
                    inputStream.close()
                    decisionComponent.setStartTime(l[1].toLong()*1_000_000) // index 1 is timestamp
                    return@withContext CustomSensorManager(
                        getSystemService(SENSOR_SERVICE) as SensorManager,
                        l,
                        resample
                    )
                }
            } else {
                decisionComponent.setStartTime(SystemClock.elapsedRealtimeNanos())
                SensorManagerWrapper(getSystemService(SENSOR_SERVICE) as SensorManager)
            }
            registerSensorListener(Sensor.TYPE_ACCELEROMETER)
        }

        startForeground()
        return START_REDELIVER_INTENT
    }

    private fun startForeground() {
        val notificationChannelID = BuildConfig.APPLICATION_ID
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(
                NotificationChannel(
                    notificationChannelID,
                    "DetectionService",
                    NotificationManager.IMPORTANCE_LOW
                )
            )

        val notification: Notification = Notification.Builder(this, notificationChannelID)
            .setOngoing(true)
            .setContentTitle("flyDetect is active")
            .setContentText("Tap to open")
            .setContentIntent(
                Intent(this, MainActivity::class.java).let {
                    PendingIntent.getActivity(this, 0,
                        it, PendingIntent.FLAG_IMMUTABLE
                    )
                }
            )
            .setSmallIcon(R.drawable.ic_flydetect_white)
            .build()

        val notificationID = 34
        startForeground(notificationID, notification)
    }

    fun registerSensorListener(type: Int) {
        val listener: SensorEventListener
        val samplingFrequency: Float
        when (type) {
            Sensor.TYPE_PRESSURE -> {
                listener = barListener
                samplingFrequency = barSamplingFrequency
            }
            Sensor.TYPE_ACCELEROMETER -> {
                listener = accListener
                samplingFrequency = accSamplingFrequency
            }
            else -> return
        }
        sensorManager?.apply {
            registerListener(
                listener,
                getDefaultSensor(type),
                Util.convertHzMicroseconds(samplingFrequency),
                30_000_000
            )
        }
    }

    fun unregisterSensorListener(type: Int) {
        val listener = when (type) {
            Sensor.TYPE_PRESSURE -> barListener
            Sensor.TYPE_ACCELEROMETER -> accListener
            else -> return
        }
        sensorManager?.unregisterListener(listener)
    }

    fun stop() {
        Log.i(TAG, "Stop signal received")
        //unregister listeners, stop other things
        sensorManager?.unregisterListener(accListener)
        sensorManager?.unregisterListener(barListener)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
    }

    fun notifyFlightStatusChange(flying: Boolean, forced: Boolean) {
        val broadcastIntent: Intent
        if (isUsingSensorInjection) return
        if (flying) {
            // This is the start of a new flight
            currentFlight = Flight(System.currentTimeMillis())
            currentFlight!!.forcedStart = forced
            broadcastIntent = Intent("no.uio.ifi.jonaspr.flydetect.FLIGHT_BEGIN")
        } else {
            // This is the end of a flight
            currentFlight!!.end = System.currentTimeMillis()
            currentFlight!!.forcedEnd = forced
            Storage.saveFlight(applicationContext, currentFlight!!)
            broadcastIntent = Intent("no.uio.ifi.jonaspr.flydetect.FLIGHT_END")
        }
        applicationContext.sendBroadcast(broadcastIntent)
    }

    companion object {
        private const val TAG = "detectionService"
        var running = false
    }
}