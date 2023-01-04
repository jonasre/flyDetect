package no.uio.ifi.jonaspr.flydetect

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import no.uio.ifi.jonaspr.flydetect.detectionservice.Flight

object Storage {
    private const val NAME = "my_flights"
    private const val KEY = "flights"
    private val gson = Gson()

    // Saves a flight to shared preferences
    fun saveFlight(context: Context, flight: Flight) {
        val prefs = getSharedPreferences(context) // get prefs
        // Get string set of flights. Make a copy so that consistency is not compromised
        val flightStringSet = getFlightStringSet(prefs).toMutableSet()
        flightStringSet.add(gson.toJson(flight)) // add new flight to set
        saveFlightStringSet(prefs, flightStringSet) // save the edited set to prefs
    }

    // Gets all stored flights
    fun getFlights(context: Context): Set<Flight> {
        val prefs = getSharedPreferences(context) // get prefs
        val flightStringSet = getFlightStringSet(prefs) // get string set of flights
        return mutableSetOf<Flight>().apply { // return new set that's converted to Flight objects
            for (value in flightStringSet) // for each string in the set
                add(gson.fromJson(value, Flight::class.java)) // convert it to Flight
        }
    }

    // Removes a flight from the shared preferences
    fun removeFlight(context: Context, flight: Flight): Boolean {
        val flightStringSet = getFlightStringSet(context).toMutableSet()
        val success = flightStringSet.remove(gson.toJson(flight))
        if (success) saveFlightStringSet(context, flightStringSet)
        return success
    }

    private fun saveFlightStringSet(context: Context, flightStringSet: Set<String>) {
        saveFlightStringSet(getSharedPreferences(context), flightStringSet)
    }

    private fun saveFlightStringSet(prefs: SharedPreferences, flightStringSet: Set<String>) {
        prefs.edit().putStringSet(KEY, flightStringSet).apply()
    }

    private fun getFlightStringSet(context: Context): Set<String> {
        return getFlightStringSet(getSharedPreferences(context))
    }

    private fun getFlightStringSet(prefs: SharedPreferences): Set<String> {
        return prefs.getStringSet(KEY, setOf())!!
    }

    private fun getSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE)!!
    }

}