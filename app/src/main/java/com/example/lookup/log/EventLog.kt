package com.example.lookup.log

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * Logs to logcat under one tag and to a rolling file in the app's private files dir.
 * Pull with: adb exec-out run-as com.example.lookup cat files/lookup.log
 */
object EventLog {
    const val TAG = "LookUp"
    private const val MAX_BYTES = 1_000_000L
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var file: File? = null

    fun init(context: Context) {
        if (file == null) file = File(context.applicationContext.filesDir, "lookup.log")
    }

    fun log(message: String) {
        val line = "${LocalDateTime.now().format(timeFormat)} +${SystemClock.elapsedRealtime()}ms $message"
        Log.i(TAG, line)
        val target = file ?: return
        writer.execute {
            if (target.length() > MAX_BYTES) target.renameTo(File(target.parentFile, "lookup.log.1"))
            target.appendText(line + "\n")
        }
    }
}
