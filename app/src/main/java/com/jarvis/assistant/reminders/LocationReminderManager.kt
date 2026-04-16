package com.jarvis.assistant.reminders

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import org.json.JSONArray
import org.json.JSONObject

/**
 * LocationReminderManager — manages geofence-based location reminders using
 * the Google Play Services Geofencing API.
 *
 * PERMISSIONS REQUIRED (declare in manifest):
 *   android.permission.ACCESS_FINE_LOCATION
 *   android.permission.ACCESS_BACKGROUND_LOCATION  (Android 10+ for background geofences)
 *
 * GRADLE DEPENDENCY REQUIRED:
 *   implementation("com.google.android.gms:play-services-location:21.x.x")
 *
 * USAGE:
 *   val manager = LocationReminderManager(context)
 *   manager.addReminder(LocationReminder("remind-milk", "Buy milk", 51.5, -0.12))
 *   manager.removeReminder("remind-milk")
 *
 * Geofences fire [LocationReminderReceiver] when the device enters the radius.
 */
class LocationReminderManager(private val context: Context) {

    companion object {
        private const val TAG       = "LocationReminderManager"
        private const val PREFS     = "jarvis_location_reminders"
        private const val KEY_LIST  = "reminder_list"

        // PendingIntent request code
        private const val PI_REQUEST = 7001
    }

    private val geofencingClient: GeofencingClient =
        LocationServices.getGeofencingClient(context)

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Adds a new [LocationReminder] and registers it as an active geofence.
     *
     * Fails silently (with a log) if the required location permissions are not granted.
     */
    fun addReminder(reminder: LocationReminder) {
        if (!hasLocationPermission()) {
            Log.w(TAG, "addReminder: ACCESS_FINE_LOCATION not granted — cannot register geofence")
            return
        }

        // Persist first so the receiver can retrieve details even before the callback fires
        persistReminder(reminder)

        val geofence = Geofence.Builder()
            .setRequestId(reminder.id)
            .setCircularRegion(reminder.lat, reminder.lng, reminder.radiusMeters)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .setLoiteringDelay(30_000)  // 30 s in region before triggering
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        try {
            geofencingClient.addGeofences(request, geofencePendingIntent)
                .addOnSuccessListener {
                    Log.i(TAG, "Geofence registered: id=${reminder.id} label='${reminder.label}'")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to register geofence id=${reminder.id}: ${e.message}", e)
                    // Remove from prefs if registration failed so state stays consistent
                    removePersistedReminder(reminder.id)
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException adding geofence — missing location permission", e)
            removePersistedReminder(reminder.id)
        }
    }

    /**
     * Removes the reminder with [id] from active geofences and persistent storage.
     */
    fun removeReminder(id: String) {
        geofencingClient.removeGeofences(listOf(id))
            .addOnSuccessListener {
                Log.i(TAG, "Geofence removed: id=$id")
                removePersistedReminder(id)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to remove geofence id=$id: ${e.message}")
                // Still remove from prefs to avoid stale entries
                removePersistedReminder(id)
            }
    }

    /**
     * Returns all persisted [LocationReminder] objects (active + pending registration).
     */
    fun getAll(): List<LocationReminder> = loadReminders()

    /**
     * Looks up a single reminder by [id]. Returns null if not found.
     */
    fun findById(id: String): LocationReminder? = loadReminders().firstOrNull { it.id == id }

    // ── PendingIntent for geofence transitions ────────────────────────────────

    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(context, LocationReminderReceiver::class.java).apply {
            action = LocationReminderReceiver.ACTION_GEOFENCE_TRANSITION
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE
            else 0

        PendingIntent.getBroadcast(context, PI_REQUEST, intent, flags)
    }

    // ── Permission helpers ────────────────────────────────────────────────────

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // ── Persistence (SharedPreferences + JSON) ────────────────────────────────

    private fun persistReminder(reminder: LocationReminder) {
        val existing = loadReminders().toMutableList()
        existing.removeAll { it.id == reminder.id }
        existing.add(reminder)
        saveReminders(existing)
    }

    private fun removePersistedReminder(id: String) {
        val updated = loadReminders().filter { it.id != id }
        saveReminders(updated)
    }

    private fun saveReminders(reminders: List<LocationReminder>) {
        val array = JSONArray()
        reminders.forEach { r ->
            array.put(
                JSONObject().apply {
                    put("id",            r.id)
                    put("label",         r.label)
                    put("lat",           r.lat)
                    put("lng",           r.lng)
                    put("radiusMeters",  r.radiusMeters.toDouble())
                }
            )
        }
        prefs.edit().putString(KEY_LIST, array.toString()).apply()
    }

    private fun loadReminders(): List<LocationReminder> {
        val json = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                LocationReminder(
                    id           = obj.getString("id"),
                    label        = obj.getString("label"),
                    lat          = obj.getDouble("lat"),
                    lng          = obj.getDouble("lng"),
                    radiusMeters = obj.getDouble("radiusMeters").toFloat()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse persisted reminders: ${e.message}", e)
            emptyList()
        }
    }
}

/**
 * A single geofence-triggered location reminder.
 *
 * @param id           Unique identifier — also used as the Geofence request ID.
 * @param label        Human-readable reminder label (spoken by Jarvis on arrival).
 * @param lat          WGS-84 latitude of the target location.
 * @param lng          WGS-84 longitude of the target location.
 * @param radiusMeters Geofence radius in metres (default 100 m).
 */
data class LocationReminder(
    val id:           String,
    val label:        String,
    val lat:          Double,
    val lng:          Double,
    val radiusMeters: Float = 100f
)
