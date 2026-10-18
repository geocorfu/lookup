package com.example.lookup.sensing

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.example.lookup.MainActivity
import com.example.lookup.R
import com.example.lookup.core.DetectionState
import com.example.lookup.core.DetectionStateMachine
import com.example.lookup.core.Event
import com.example.lookup.core.VehicleSpeedDetector
import com.example.lookup.log.EventLog
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Foreground service of type "health" (backed by ACTIVITY_RECOGNITION) plus "location" for GPS
 * speed. GPS runs only while a walk is in progress or a vehicle is active, to save battery.
 * All state machine calls happen on the main looper.
 */
class DetectionService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val machine = DetectionStateMachine()
    private val vehicle = VehicleSpeedDetector()
    private var gpsOn = false
    private var lastState: DetectionState? = null
    private var running = false
    private lateinit var sensorManager: SensorManager

    private val tick = object : Runnable {
        override fun run() {
            vehicle.onTick(SystemClock.elapsedRealtime())?.let { onVehicleSpeedChanged(it, "GPS silent") }
            handle(Event.Tick)
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val delayMs = SystemClock.elapsedRealtime() - event.timestamp / 1_000_000
            handle(Event.StepDetected, note = "sensor delay ${delayMs}ms")
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val counterListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            EventLog.log("step counter total=${event.values[0].toLong()}")
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) {
                if (!location.hasSpeed()) continue
                val accuracy = if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null
                val kmh = location.speed * 3.6
                val summary = "%.1f km/h (acc %s)".format(kmh, accuracy?.let { "%.1f m/s".format(it) } ?: "n/a")
                EventLog.log("GPS speed $summary")
                DetectionHub.update { it.copy(gps = "on, $summary") }
                vehicle.onSample(SystemClock.elapsedRealtime(), location.speed.toDouble(), accuracy)
                    ?.let { onVehicleSpeedChanged(it, "GPS") }
            }
        }
    }

    private val transitionsIntent: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            this,
            0,
            Intent(this, ActivityTransitionReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
        sensorManager = getSystemService(SensorManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Detection", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            EventLog.log("service stop requested")
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundWithTypes()
        if (!running) {
            running = true
            EventLog.log("service started (restart=${intent == null})")
            DetectionHub.sink = { event -> handler.post { handle(event) } }
            registerStepSensor()
            requestActivityTransitions()
            handler.post(tick)
            DetectionHub.update { it.copy(serviceRunning = true) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        sensorManager.unregisterListener(stepListener)
        sensorManager.unregisterListener(counterListener)
        setGps(false)
        ActivityRecognition.getClient(this).removeActivityTransitionUpdates(transitionsIntent)
        DetectionHub.sink = null
        DetectionHub.update { it.copy(serviceRunning = false) }
        EventLog.log("service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handle(event: Event, note: String? = null) {
        val snapshot = machine.handle(event, SystemClock.elapsedRealtime())
        val stateChanged = snapshot.state != lastState
        if (event != Event.Tick || stateChanged) {
            val countdown = snapshot.countdownRemainingMs?.let { " countdown=${it}ms" } ?: ""
            val suffix = note?.let { " ($it)" } ?: ""
            EventLog.log(
                "$event$suffix -> ${snapshot.state} steps=${snapshot.stepsSinceWalkStart}" +
                    "$countdown suppressed=${snapshot.suppressed}",
            )
        }
        if (stateChanged) {
            lastState = snapshot.state
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification(snapshot.state.name))
        }
        DetectionHub.update { it.copy(snapshot = snapshot) }
        setGps(snapshot.state != DetectionState.IDLE || vehicle.active)
    }

    private fun onVehicleSpeedChanged(active: Boolean, source: String) {
        EventLog.log("vehicle by speed -> $active ($source)")
        DetectionHub.update { it.copy(vehicleSpeedActive = active) }
        handle(Event.VehicleSpeedDetected(active))
    }

    private fun hasFineLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Location type needs location permission and a foreground start; fall back to health only. */
    private fun startForegroundWithTypes() {
        val health = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        if (hasFineLocation()) {
            try {
                startForeground(NOTIFICATION_ID, notification("Starting"), health or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                return
            } catch (e: SecurityException) {
                EventLog.log("location FGS type refused, health only: $e")
            }
        }
        startForeground(NOTIFICATION_ID, notification("Starting"), health)
    }

    private fun setGps(on: Boolean) {
        if (on == gpsOn) return
        val client = LocationServices.getFusedLocationProviderClient(this)
        if (on) {
            if (!hasFineLocation()) {
                DetectionHub.update { it.copy(gps = "no permission") }
                return
            }
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, GPS_INTERVAL_MS).build()
            try {
                client.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
                gpsOn = true
                EventLog.log("GPS on")
                DetectionHub.update { it.copy(gps = "on, waiting for fix") }
            } catch (e: SecurityException) {
                EventLog.log("GPS denied: $e")
                DetectionHub.update { it.copy(gps = "denied") }
            }
        } else {
            client.removeLocationUpdates(locationCallback)
            gpsOn = false
            EventLog.log("GPS off")
            DetectionHub.update { it.copy(gps = "off") }
        }
    }

    private fun registerStepSensor() {
        val wakeUp = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR, true)
        val sensor = wakeUp ?: sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        if (sensor == null) {
            EventLog.log("no step detector sensor on this device")
            DetectionHub.update { it.copy(stepSensor = "missing") }
            return
        }
        val ok = sensorManager.registerListener(stepListener, sensor, SensorManager.SENSOR_DELAY_FASTEST, 0, handler)
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
            sensorManager.registerListener(counterListener, it, SensorManager.SENSOR_DELAY_NORMAL, 0, handler)
        }
        val description = "${sensor.name} (wakeUp=${sensor.isWakeUpSensor}, registered=$ok)"
        EventLog.log("step sensor: $description")
        DetectionHub.update { it.copy(stepSensor = description) }
    }

    private fun requestActivityTransitions() {
        val transitions = ActivityTransitionReceiver.MONITORED.flatMap { type ->
            listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map {
                ActivityTransition.Builder().setActivityType(type).setActivityTransition(it).build()
            }
        }
        try {
            ActivityRecognition.getClient(this)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), transitionsIntent)
                .addOnSuccessListener { EventLog.log("activity transitions registered") }
                .addOnFailureListener { EventLog.log("activity transitions failed: $it") }
        } catch (e: SecurityException) {
            EventLog.log("activity transitions denied: $e")
        }
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Look Up detection")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    companion object {
        private const val CHANNEL_ID = "detection"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 1_000L
        private const val GPS_INTERVAL_MS = 1_000L
        private const val ACTION_STOP = "com.example.lookup.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DetectionService::class.java))
        }

        /** Debug-only control while detection does not block anything. */
        fun stop(context: Context) {
            context.startService(Intent(context, DetectionService::class.java).setAction(ACTION_STOP))
        }
    }
}
