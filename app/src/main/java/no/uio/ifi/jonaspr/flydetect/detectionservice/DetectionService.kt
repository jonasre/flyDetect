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
import android.util.Log
import no.uio.ifi.jonaspr.flydetect.BuildConfig
import no.uio.ifi.jonaspr.flydetect.MainActivity
import no.uio.ifi.jonaspr.flydetect.R
import no.uio.ifi.jonaspr.flydetect.Util

class DetectionService : Service() {
    inner class LocalBinder : Binder() {
        fun stop() = this@DetectionService.stop()
        fun latestAccSample() = sensorListener.latestAcc
        fun latestBarSample() = sensorListener.latestBar
        fun replayProgress() = sensorManager.getReplayProgress()
    }
    private val binder = LocalBinder()

    private lateinit var sensorManager: SensorManagerInterface
    private lateinit var sensorListener: CustomSensorEventListener

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Start command received")
        running = true

        val accSamplingFrequency = Util.convertHzMicroseconds(
            intent!!.getFloatExtra("accSamplingFrequency", -1f).toInt()
        )
        val barSamplingFrequency = Util.convertHzMicroseconds(
            intent.getFloatExtra("barSamplingFrequency", -1f).toInt()
        )


        // Get sensorFile for sensor injection (optional)
        // intent.getParcelableExtra(key) is deprecated since API level 33
        val sensorFile: Uri? = if (VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra("sensorFile", Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("sensorFile")
        }

        sensorListener = CustomSensorEventListener()
        // Use CustomSensorManager if sensor data should be injected,
        // else use default SensorManager
        sensorManager = if (sensorFile != null) {

            val inputStream = contentResolver.openInputStream(sensorFile)
            val l = inputStream!!.bufferedReader().readLines()
            inputStream.close()
            CustomSensorManager(getSystemService(Context.SENSOR_SERVICE) as SensorManager, l)
        } else {
            SensorManagerWrapper(getSystemService(Context.SENSOR_SERVICE) as SensorManager)
        }.apply {
            registerListener(
                sensorListener,
                getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                accSamplingFrequency,
                0
            )
            registerListener(
                sensorListener,
                getDefaultSensor(Sensor.TYPE_PRESSURE),
                barSamplingFrequency,
                0
            )
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
        sensorManager.unregisterListener(sensorListener)
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