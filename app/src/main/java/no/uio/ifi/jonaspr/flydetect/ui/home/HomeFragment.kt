package no.uio.ifi.jonaspr.flydetect.ui.home

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.*
import no.uio.ifi.jonaspr.flydetect.MainActivity
import no.uio.ifi.jonaspr.flydetect.R
import no.uio.ifi.jonaspr.flydetect.Util
import no.uio.ifi.jonaspr.flydetect.databinding.FragmentHomeBinding
import no.uio.ifi.jonaspr.flydetect.detectionservice.DetectionServiceBinder
import no.uio.ifi.jonaspr.flydetect.`interface`.Failable
import kotlin.random.Random

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private var serviceBinder: DetectionServiceBinder? = null
    private var job: Job? = null
    private var sensorFileLoaded = false
    private var clouds: Array<ImageView> = emptyArray()
    private var animators: List<ObjectAnimator> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val homeViewModel =
            ViewModelProvider(this)[HomeViewModel::class.java]

        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        clouds = Array(NUM_CLOUDS) { createCloudIcon() }
        //binding.aircraftIcon.bringToFront()

        binding.masterSwitch.setOnCheckedChangeListener { _, checked ->
            // Since the switch is might be toggled automatically when the app starts,
            // we must make sure that the service doesn't start if it's already running,
            // and not stopped when it's not running
            val binder = serviceBinder
            if (!checked && binder != null) {
                if (binder.isUsingSensorInjection()) {
                    val m = homeViewModel.generateFlightStatsMessage(binder.flightStats())
                    displayFlightStats(m)
                }
                stopAnimation()
                (activity as MainActivity).stopDetectionService()
            } else if (checked && binder == null) {
                // Start/bind to the service
                (activity as MainActivity).bindDetectionService(object : Failable {
                    override fun onFailure() {
                        // Display error to user
                        Snackbar.make(
                            root,
                            getString(R.string.fileNotFound),
                            Snackbar.LENGTH_LONG
                        ).show()
                        // Flip the switch back to unchecked
                        activity?.runOnUiThread {
                            binding.masterSwitch.isChecked = false
                        }
                    }
                })
            }
        }

        binding.flightButton.setOnClickListener {
            if (serviceBinder == null)  {
                Log.e(TAG, "Flight button visible while service is disconnected")
                return@setOnClickListener
            }
            serviceBinder?.let {
                val status = it.flying()
                val newState = if (status) "NOT FLYING" else "FLYING"
                activity?.let { a ->
                    AlertDialog.Builder(a).apply {
                        setTitle(getString(R.string.confirmation))
                        setMessage(
                            String.format(getString(R.string.force_flight_message), newState)
                        )
                        setPositiveButton(getString(R.string.yes)) { _, _ ->
                            Log.d(TAG, "Dialog OK")
                            it.forceFlight(!status)
                        }
                        setNegativeButton(getString(R.string.no)) { _, _ ->
                            Log.d(TAG, "Dialog CANCEL")
                        }
                        show()
                    }
                }
            }
        }

        (activity as MainActivity).sensorFile().observe(viewLifecycleOwner) {
            Log.d(TAG, "Sensor file update")
            sensorFileLoaded = it != null
            showReplayIfAppropriate()
        }
        return root
    }

    private fun stopUiUpdate() {
        job?.cancel()
        binding.flyingStatus.text = ""
        binding.latestSensorData.text = ""
        binding.nextAnalysis.text = ""
    }

    private fun showReplayIfAppropriate() {
        val binder = serviceBinder
        if (
            (sensorFileLoaded && binder == null) ||
            (binder != null && binder.isUsingSensorInjection())
        ) {
            binding.sensorFileLoaded.visibility = View.VISIBLE
            binding.replayProgress.visibility = View.VISIBLE
            binding.nextAnalysis.visibility = View.GONE
            binding.flightButton.isEnabled = false
        } else {
            binding.sensorFileLoaded.visibility = View.GONE
            binding.replayProgress.visibility = View.GONE
            binding.nextAnalysis.visibility = View.VISIBLE
            binding.flightButton.isEnabled = true
        }
    }

    private fun displayFlightStats(message: String) {
        if (message.isEmpty()) return
        activity?.let { a ->
            AlertDialog.Builder(a).apply {
                setTitle("Flight stats")
                setMessage(message)
                setPositiveButton("Close") { _, _ -> }
                show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as MainActivity).binder().observe(viewLifecycleOwner) {
            // This code is run whenever the service is connected or disconnected
            serviceBinder = it
            showReplayIfAppropriate()
            if (it != null) {
                // Checks the switch when the service is connected, necessary when starting the app
                // while the service is already running
                binding.masterSwitch.isChecked = true
                binding.flightButton.visibility = View.VISIBLE

                // UI update loop
                job = CoroutineScope(Dispatchers.Main).launch {
                    while (true) {
                        try {
                            updateUI()
                            delay(100)
                        } catch (e: CancellationException) {
                            // This block is to avoid catching JobCancellationException in the next
                            // catch block. The exception is rethrown
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "UI update loop encountered an exception", e)
                            break
                        }
                    }
                }

                it.flyingLiveData().observe(viewLifecycleOwner) { flying ->
                    if (flying) {
                        animators = startAnimation()
                        binding.flyingStatus.text = getString(R.string.flyingStateTrue)
                        binding.flightButton.text = getString(R.string.forceFlightFalse)
                        binding.flightButton.setCompoundDrawablesWithIntrinsicBounds(
                            R.drawable.ic_baseline_airplanemode_inactive_24,
                            0, 0, 0
                        )
                    } else {
                        stopAnimation()
                        binding.flyingStatus.text = getString(R.string.flyingStateFalse)
                        binding.flightButton.text = getString(R.string.forceFlightTrue)
                        binding.flightButton.setCompoundDrawablesWithIntrinsicBounds(
                            R.drawable.ic_baseline_airplanemode_active_24,
                            0, 0, 0
                        )
                    }
                }

            } else {
                stopUiUpdate()
                binding.replayProgress.progress = 0
                binding.flightButton.visibility = View.INVISIBLE
            }
        }
    }

    // Function called from a loop that updates the UI with user relevant information
    private fun updateUI() {
        serviceBinder?.let { sb ->
            val flying = sb.flying()
            // Display pressure or acceleration depending on flight status
            binding.latestSensorData.text = if (flying)
                String.format(getString(R.string.pressure_display), sb.latestBarSample())
            else
                String.format(getString(R.string.acceleration_display), sb.latestAccSample())

            // Update replay status (only visible if file is loaded)
            binding.replayProgress.progress = sb.replayProgress()

            // Display time until next analysis
            val time = sb.secondsUntilNextAnalysis()
            val timeString = if (time < 0)
                "00:00\n(waiting for data from sensors)" else Util.formatSecondsMMSS(time)
            binding.nextAnalysis.text = String.format(getString(R.string.nextAnalysis), timeString)
        }
    }

    // Creates the animators, un-hides the images and starts the animation
    private fun startAnimation(): List<ObjectAnimator> {
        // Create the aircraft animator
        val aircraftAnim = ObjectAnimator.ofFloat(
            binding.aircraftIcon,
            "translationY",
            -50f,
            50f
        ).apply {
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            duration = 3000
            start()
        }

        binding.aircraftIcon.visibility = View.VISIBLE

        // Generate random durations for the cloud animations
        val durations = Array(NUM_CLOUDS) { Random.nextLong(4000, 12000) }
        durations.sort() // Sort the durations
        var counter = durations.lastIndex // used to iterate backwards
        var aircraftToFront = false // Keeps track of if the aircraft has been moved to front yet

        // Create animators for clouds
        val objectAnimators = clouds.map { cloudIcon ->
            // Generate random duration, set scale from duration
            val animationDuration = durations[counter]
            val scale = 9_000f / animationDuration
            // Calculate an offset so that the clouds go completely out of the frame on both sides
            // of the screen. Also, avoid clouds getting cut off at the top or bottom.
            val offset: Float = if (scale < 1) 0f else (scale - 1) * cloudIcon.width
            val maxHeight = binding.animationWindow.height - cloudIcon.height

            cloudIcon.apply {
                visibility = View.VISIBLE // Make the cloud visible
                translationX = binding.animationWindow.width.toFloat() + offset // Set start x
                y = (0..maxHeight - offset.toInt()).random().toFloat() // Set y position

                // Scale the cloud up or down
                scaleX = scale
                scaleY = scale
                // Move the cloud to the front on z axis
                bringToFront()
            }

            // Move the aircraft to front at the appropriate time
            if (!aircraftToFront && counter < durations.size / 1.5) {
                binding.aircraftIcon.bringToFront()
                aircraftToFront = true
            }
            counter--

            // Create the animator
            ObjectAnimator.ofFloat(
                cloudIcon,
                "translationX",
                binding.animationWindow.width.toFloat(),
                -cloudIcon.width.toFloat() - offset
            ).apply {
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                duration = animationDuration
                startDelay = Random.nextLong(6000)
                start()
            }
        } as MutableList
        // Add the aircraft animator to the list as well
        objectAnimators.add(aircraftAnim)

        return objectAnimators
    }

    // Stops the animation, makes the animated images invisible
    private fun stopAnimation() {
        for (i in animators.indices) animators[i].cancel()
        for (i in clouds.indices) clouds[i].visibility = View.INVISIBLE
        binding.aircraftIcon.visibility = View.INVISIBLE
        animators = emptyList()
    }

    // Creates a cloud, an ImageView, and adds it to the animation window
    private fun createCloudIcon(): ImageView {
        val cloudIcon = ImageView(activity)
        cloudIcon.setImageResource(R.drawable.baseline_cloud_24)
        cloudIcon.visibility = View.INVISIBLE
        binding.animationWindow.addView(cloudIcon)

        return cloudIcon
    }

    override fun onPause() {
        super.onPause()
        stopUiUpdate()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "home"
        private const val NUM_CLOUDS = 10
    }
}