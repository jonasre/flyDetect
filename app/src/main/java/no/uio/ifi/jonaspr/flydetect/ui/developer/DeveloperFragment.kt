package no.uio.ifi.jonaspr.flydetect.ui.developer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import no.uio.ifi.jonaspr.flydetect.MainActivity
import no.uio.ifi.jonaspr.flydetect.R
import no.uio.ifi.jonaspr.flydetect.`interface`.Failable
import no.uio.ifi.jonaspr.flydetect.databinding.FragmentDeveloperBinding

class DeveloperFragment : Fragment() {

    private var _binding: FragmentDeveloperBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private lateinit var filePickerLauncher: ActivityResultLauncher<Intent>



    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val developerViewModel =
            ViewModelProvider(this)[DeveloperViewModel::class.java]

        _binding = FragmentDeveloperBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val postFileRelated = (activity as MainActivity)::postFileRelated
        val defaultCallback = { uri: Uri?, markers: Map<String, Int>? ->
            postFileRelated(uri, markers ?: mapOf())
        }

        filePickerLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    developerViewModel.fetchSensorFileInfo(
                        requireActivity().contentResolver,
                        result.data?.data,
                        object : Failable {
                            override fun onFailure() {
                                Handler(Looper.getMainLooper()).post{
                                    Toast.makeText(
                                        context,
                                        "Error while reading file",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        defaultCallback
                    )
                }
            }

        (activity as MainActivity).sensorFile().observe(viewLifecycleOwner) {
            if (it == null) {
                binding.clearButton.visibility = View.GONE
                binding.fileLoadButton.text = getString(R.string.load_sensor_file)
            } else {
                binding.clearButton.visibility = View.VISIBLE
                binding.fileLoadButton.text =
                    developerViewModel.fileName(requireActivity().contentResolver, it)
            }
        }
        developerViewModel.apply {
            sensorFileTitle.observe(viewLifecycleOwner) {
                val replace = if (it != "") it else "N/A"
                binding.sensorFileTitle.text =
                    String.format(getString(R.string.sensorFileTitle, replace))
            }
            duration.observe(viewLifecycleOwner) {
                val replace = if (it != "") it else "N/A"
                binding.durationText.text = String.format(getString(R.string.duration), replace)
            }
            samples.observe(viewLifecycleOwner) {
                val replace = if (it != 0) it.toString() else "N/A"
                binding.samplesText.text = String.format(getString(R.string.samples), replace)
            }
            quality.observe(viewLifecycleOwner) {
                val replace = if (it != 0f) String.format("%.1f", it*100)+" %" else "N/A"
                binding.qualityText.text = String.format(getString(R.string.quality), replace)
            }
            accelerometerRate.observe(viewLifecycleOwner) {
                val replace = if (it != 0) "$it Hz" else "N/A"
                binding.accSamplingRate.text =
                    String.format(getString(R.string.accelerometer_rate), replace)
            }
            barometerRate.observe(viewLifecycleOwner) {
                val replace = if (it != 0) "$it Hz" else "N/A"
                binding.barSamplingRate.text =
                    String.format(getString(R.string.barometer_rate), replace)
            }
            markers.observe(viewLifecycleOwner) {
                binding.markersContent.text = if (it != "") it else "N/A"
            }

            loadingFile.observe(viewLifecycleOwner) {
                if (it) {
                    binding.fileLoadButton.isEnabled = false
                    binding.clearButton.isEnabled = false
                    binding.fileLoading.visibility = View.VISIBLE
                } else {
                    binding.fileLoadButton.isEnabled = true
                    binding.clearButton.isEnabled = true
                    binding.fileLoading.visibility = View.GONE
                }
            }
        }


        binding.fileLoadButton.setOnClickListener {
            filePickerLauncher.launch(Intent().apply {
                type = "text/plain"
                action = Intent.ACTION_GET_CONTENT
            })
        }

        binding.clearButton.setOnClickListener {
            developerViewModel.fetchSensorFileInfo(
                requireActivity().contentResolver, null, callback = defaultCallback
            )
        }

        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}