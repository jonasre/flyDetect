package no.uio.ifi.jonaspr.flydetect

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import androidx.preference.PreferenceManager
import com.google.android.material.navigation.NavigationView
import no.uio.ifi.jonaspr.flydetect.databinding.ActivityMainBinding
import no.uio.ifi.jonaspr.flydetect.detectionservice.DetectionService
import no.uio.ifi.jonaspr.flydetect.detectionservice.DetectionServiceBinder

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding

    private val mBinder = MutableLiveData<DetectionServiceBinder?>()
    fun binder(): LiveData<DetectionServiceBinder?> = mBinder

    private val mSensorFile = MutableLiveData<Uri?>()
    fun sensorFile(): LiveData<Uri?> = mSensorFile

    private var markers: Map<String, Int> = mapOf()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName?, service: IBinder?) {
            Log.i(TAG, "DetectionService connected")
            val binder = service as DetectionServiceBinder
            mBinder.value = binder
        }

        override fun onServiceDisconnected(p0: ComponentName?) {
            Log.w(TAG, "DetectionService disconnected")
            mBinder.value = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.appBarMain.toolbar)

        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navView: NavigationView = binding.navView
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
        val navController = navHostFragment.navController
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.nav_home, R.id.nav_developer, R.id.nav_flight_history, R.id.nav_settings
            ), drawerLayout
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)

        // Bind to DetectionService if it's already running
        if (DetectionService.running) {
            bindDetectionService()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    // Binds to DetectionService. The service is started if it's not already running
    fun bindDetectionService() {
        val intent = Intent(this, DetectionService::class.java)
        if (!DetectionService.running) {
            // start the service
            PreferenceManager.getDefaultSharedPreferences(this).let {
                applicationContext.startForegroundService(intent.apply {
                    putExtra(
                        "accSamplingFrequency",
                        it.getString("acc_sampling_frequency", "")!!.toFloat()
                    )
                    putExtra(
                        "barSamplingFrequency",
                        it.getString("bar_sampling_frequency", "")!!.toFloat()
                    )
                    if (mSensorFile.value != null) {
                        putExtra("sensorFile", mSensorFile.value)
                        putExtra("markers", markers as HashMap<String, Int>)
                    }
                    putExtra("resampleSensorFile", it.getBoolean("resampleSensorFile", true))
                })
            }
        }
        bindService(
            intent,
            connection,
            Context.BIND_ABOVE_CLIENT
        )
    }

    // Stops DetectionService and unbinds from it
    fun stopDetectionService() {
        mBinder.value?.stop()
        Log.i(TAG, "Unbinding from service")
        unbindService(connection)
        mBinder.postValue(null)
    }

    fun postFileRelated(uri: Uri?, m: Map<String, Int>) {
        mSensorFile.postValue(uri)
        markers = m
    }

    companion object {
        private const val TAG = "main"
    }
}