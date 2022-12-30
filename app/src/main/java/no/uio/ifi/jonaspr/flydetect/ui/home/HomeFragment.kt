package no.uio.ifi.jonaspr.flydetect.ui.home

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.*
import no.uio.ifi.jonaspr.flydetect.MainActivity
import no.uio.ifi.jonaspr.flydetect.R
import no.uio.ifi.jonaspr.flydetect.databinding.FragmentHomeBinding
import no.uio.ifi.jonaspr.flydetect.detectionservice.DetectionService

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private var serviceBinder: DetectionService.LocalBinder? = null
    private var job: Job? = null
    private var sensorFileLoaded = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val homeViewModel =
            ViewModelProvider(this)[HomeViewModel::class.java]

        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

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
                (activity as MainActivity).stopDetectionService()
            } else if (checked && binder == null) {
                (activity as MainActivity).bindDetectionService()
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
                        setTitle("Confirmation")
                        setMessage("Are you sure you want to force the flight status to " +
                                "$newState? This can cause problems with the detection.")
                        setPositiveButton("Yes") { _, _ ->
                            Log.d(TAG, "Dialog OK")
                            it.forceFlight(!status)
                        }
                        setNegativeButton("No") { _, _ ->
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
    }

    private fun showReplayIfAppropriate() {
        val binder = serviceBinder
        if (
            (sensorFileLoaded && binder == null) ||
            (binder != null && binder.isUsingSensorInjection())
        ) {
            binding.sensorFileLoaded.visibility = View.VISIBLE
            binding.replayProgress.visibility = View.VISIBLE
            binding.flightButton.isEnabled = false
        } else {
            binding.sensorFileLoaded.visibility = View.GONE
            binding.replayProgress.visibility = View.GONE
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


                job = CoroutineScope(Dispatchers.Main).launch {
                    while (true) {
                        try {
                            serviceBinder?.let { sb ->
                                val flying = it.flying()
                                binding.latestSensorData.text = if (flying)
                                    String.format(getString(R.string.pressure_display), it.latestBarSample())
                                else
                                    String.format(getString(R.string.acceleration_display), it.latestAccSample())
                                binding.replayProgress.progress = sb.replayProgress()
                            }
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
                        binding.flyingStatus.text = getString(R.string.flyingStateTrue)
                        binding.flightButton.text = getString(R.string.forceFlightFalse)
                        binding.flightButton.setCompoundDrawablesWithIntrinsicBounds(
                            R.drawable.ic_baseline_airplanemode_inactive_24,
                            0, 0, 0
                        )
                    } else {
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
    }
}