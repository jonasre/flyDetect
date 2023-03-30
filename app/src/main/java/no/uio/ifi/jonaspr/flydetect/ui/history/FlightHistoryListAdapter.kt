package no.uio.ifi.jonaspr.flydetect.ui.history

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import no.uio.ifi.jonaspr.flydetect.R
import no.uio.ifi.jonaspr.flydetect.flightdata.Storage
import no.uio.ifi.jonaspr.flydetect.Util
import no.uio.ifi.jonaspr.flydetect.databinding.FlightHistoryItemBinding
import no.uio.ifi.jonaspr.flydetect.flightdata.Flight
import java.text.SimpleDateFormat
import java.util.*

class FlightHistoryListAdapter(flightSet: Set<Flight>, private val callback: (x: Boolean) -> Unit) :
    RecyclerView.Adapter<FlightHistoryListAdapter.ViewHolder>() {
    private var flightList = flightSet.sortedBy { it.start }.reversed().toMutableList()
    private val hourMinuteFormatter = SimpleDateFormat("HH:mm", Locale.ENGLISH)

    /**
     * Provide a reference to the type of views that you are using
     * (custom ViewHolder).
     */
    inner class ViewHolder(
        binding: FlightHistoryItemBinding
    ): RecyclerView.ViewHolder(binding.root) {
        private val date = binding.date
        private val startTime = binding.startTime
        private val duration = binding.duration
        private val forcedStart = binding.forcedStart
        private val forcedEnd = binding.forcedEnd
        private val context = binding.root.context

        fun bind(flight: Flight) {
            // set values of all field on item
            date.text = Util.dateFormatTh(flight.start)
            startTime.text = String.format(
                context.getString(R.string.start_time),
                hourMinuteFormatter.format(Date(flight.start))
            )
            duration.text = String.format(
                context.getString(R.string.flight_duration),
                Util.formatSeconds(((flight.end - flight.start).toInt())/1000)
            )
            forcedStart.visibility = if (flight.forcedStart) View.VISIBLE else View.INVISIBLE
            forcedEnd.visibility = if (flight.forcedEnd) View.VISIBLE else View.INVISIBLE

            itemView.setOnLongClickListener {
                context.let { a ->
                    AlertDialog.Builder(a).apply {
                        setTitle(context.getString(R.string.confirmation))
                        setMessage(context.getString(R.string.delete_flight_confirmation))
                        setPositiveButton(context.getString(R.string.yes)) { _, _ ->
                            val success = Storage.removeFlight(context, flight)
                            if (!success) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.flight_delete_error),
                                    Toast.LENGTH_LONG
                                ).show()
                                return@setPositiveButton
                            }
                            flightList.remove(flight)
                            notifyItemRemoved(layoutPosition)
                            this@FlightHistoryListAdapter.callback(flightList.isEmpty())
                        }
                        setNegativeButton(context.getString(R.string.no)) { _, _ -> }
                        show()
                    }
                }
                return@setOnLongClickListener true
            }
        }
    }

    // Create new views (invoked by the layout manager)
    override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
        // Create a new view, which defines the UI of the list item
        return ViewHolder(
            FlightHistoryItemBinding.inflate(
                LayoutInflater.from(viewGroup.context),
                viewGroup,
                false
            )
        )
    }

    // Replace the contents of a view (invoked by the layout manager)
    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {

        // Get element from your dataset at this position and replace the
        // contents of the view with that element
        viewHolder.bind(flightList[position])
    }

    // Return the size of your dataset (invoked by the layout manager)
    override fun getItemCount() = flightList.size

}
