package no.uio.ifi.jonaspr.flydetect.detectionservice

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Binder
import android.os.Build.VERSION
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.uio.ifi.jonaspr.flydetect.BuildConfig
import no.uio.ifi.jonaspr.flydetect.MainActivity
import no.uio.ifi.jonaspr.flydetect.R
import no.uio.ifi.jonaspr.flydetect.Util

class DetectionService : Service() {
    inner class LocalBinder : Binder() {
        fun stop() = this@DetectionService.stop()
        fun latestAccSample() = accListener.latest
        fun latestBarSample() = barListener.latest
        fun replayProgress() = sensorManager?.getReplayProgress() ?: 0
        fun flying() = decisionComponent.currentlyFlying()
    }
    private val binder = LocalBinder()

    private var sensorManager: SensorManagerInterface? = null
    private lateinit var accListener: AccelerometerListener
    private lateinit var barListener: BarometerListener
    private lateinit var decisionComponent: DecisionComponent

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Start command received")
        running = true

        val accSamplingFrequency =
            intent!!.getFloatExtra("accSamplingFrequency", -1f)

        val barSamplingFrequency =
            intent.getFloatExtra("barSamplingFrequency", -1f)

        // Get sensorFile for sensor injection (optional)
        // intent.getParcelableExtra(key) is deprecated since API level 33
        val sensorFile: Uri? = if (VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra("sensorFile", Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("sensorFile")
        }

        val resample = intent.getBooleanExtra("resampleSensorFile", true)

        decisionComponent = DecisionComponent(accSamplingFrequency, barSamplingFrequency)
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
                        getSystemService(Context.SENSOR_SERVICE) as SensorManager,
                        l,
                        resample
                    )
                }
            } else {
                decisionComponent.setStartTime(SystemClock.elapsedRealtimeNanos())
                SensorManagerWrapper(getSystemService(Context.SENSOR_SERVICE) as SensorManager)
            }.apply {
                registerListener(
                    accListener,
                    getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                    Util.convertHzMicroseconds(accSamplingFrequency),
                    0
                )
                registerListener(
                    barListener,
                    getDefaultSensor(Sensor.TYPE_PRESSURE),
                    Util.convertHzMicroseconds(barSamplingFrequency),
                    0
                )
            }
        }

        startForeground()
        return START_NOT_STICKY //Is there a better alternative?
    }

    private fun startForeground() {
        val notificationChannelID = BuildConfig.APPLICATION_ID
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(
                NotificationChannel(
                    notificationChannelID,
                    "DetectionService",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )

        val notification: Notification = Notification.Builder(this, notificationChannelID)
            .setOngoing(true)
            .setContentTitle("flyDetect is active")
            .setContentText("in the background")
            .setContentIntent(
                Intent(this, MainActivity::class.java).let {
                    PendingIntent.getActivity(this, 0,
                        it, PendingIntent.FLAG_IMMUTABLE
                    )
                }
            )
            .setSmallIcon(R.drawable.ic_launcher_foreground) //TODO: update at some point
            .build()

        val notificationID = 34
        startForeground(notificationID, notification)
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

    companion object {
        private const val TAG = "detectionService"
        var running = false
    }
}