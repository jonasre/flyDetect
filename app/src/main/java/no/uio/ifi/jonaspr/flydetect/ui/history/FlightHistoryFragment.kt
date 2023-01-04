package no.uio.ifi.jonaspr.flydetect.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DividerItemDecoration
import no.uio.ifi.jonaspr.flydetect.Storage
import no.uio.ifi.jonaspr.flydetect.databinding.FragmentFlightHistoryBinding

class FlightHistoryFragment : Fragment() {

    private var _binding: FragmentFlightHistoryBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFlightHistoryBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val flights = Storage.getFlights(root.context)
        val adapter = FlightHistoryListAdapter(flights, ::showMessageIfAppropriate)

        binding.flightHistoryList.adapter = adapter
        binding.flightHistoryList.addItemDecoration(
            DividerItemDecoration(root.context, DividerItemDecoration.VERTICAL)
        )

        showMessageIfAppropriate(flights.isEmpty())

        return root
    }

    private fun showMessageIfAppropriate(emptyFlights: Boolean) {
        if (emptyFlights) {
            // Show message if no flights are registered
            binding.flightHistoryList.visibility = View.INVISIBLE
            binding.noFlightsMessage.visibility = View.VISIBLE
        } else {
            binding.flightHistoryList.visibility = View.VISIBLE
            binding.noFlightsMessage.visibility = View.INVISIBLE
        }
    }
}