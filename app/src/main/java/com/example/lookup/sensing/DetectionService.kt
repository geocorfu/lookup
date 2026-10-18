package com.example.lookup.sensing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import com.example.lookup.log.EventLog
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest

/**
 * Foreground service of type "health": it is backed by ACTIVITY_RECOGNITION, may be started
 * from boot later, and needs no location. GPS speed (Step 5) will add the "location" type.
 * All state machine calls happen on the main looper.
 */
class DetectionService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val machine = DetectionStateMachine()
    private var lastState: DetectionState? = null
    private var running = false
    private lateinit var sensorManager: SensorManager

    private val tick = object : Runnable {
        override fun run() {
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
        startForeground(NOTIFICATION_ID, notification("Starting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
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
