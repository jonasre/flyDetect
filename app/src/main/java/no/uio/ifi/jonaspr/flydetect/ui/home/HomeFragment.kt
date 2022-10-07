package no.uio.ifi.jonaspr.flydetect.ui.home

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
import kotlin.math.sqrt

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private var serviceBinder: DetectionService.LocalBinder? = null
    private var job: Job? = null

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
            if (!checked && serviceBinder != null) {
                (activity as MainActivity).stopDetectionService()
            } else if (checked && serviceBinder == null) {
                (activity as MainActivity).bindDetectionService()
            }
        }

        (activity as MainActivity).sensorFile().observe(viewLifecycleOwner) {
            Log.d(TAG, "Sensor file update")
            if (it != null) {
                binding.sensorFileLoaded.visibility = View.VISIBLE
                binding.replayProgress.visibility = View.VISIBLE
            } else {
                binding.sensorFileLoaded.visibility = View.GONE
                binding.replayProgress.visibility = View.GONE
            }
        }
        return root
    }

    private fun stopUiUpdate() {
        job?.cancel()
        binding.pressure.text = ""
        binding.acceleration.text = ""
    }

    override fun onResume() {
        super.onResume()
        (activity as MainActivity).binder().observe(viewLifecycleOwner) {
            // This code is run whenever the service is connected or disconnected
            serviceBinder = it
            if (it != null) {
                // Checks the switch when the service is connected, necessary when starting the app
                // while the service is already running
                binding.masterSwitch.isChecked = true


                job = CoroutineScope(Dispatchers.Main).launch {
                    while (true) {
                        try {
                            val magnitude = it.latestAccSample().let { v ->
                                sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                            }
                            binding.acceleration.text =
                                String.format(getString(R.string.acceleration_display), magnitude)
                            binding.pressure.text =
                                String.format(getString(R.string.pressure_display), it.latestBarSample())
                            binding.replayProgress.progress = serviceBinder?.replayProgress()!!
                            delay(100)
                        } catch (e: Exception) {
                            break
                        }
                    }
                }

            } else {
                stopUiUpdate()
                binding.replayProgress.progress = 0
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