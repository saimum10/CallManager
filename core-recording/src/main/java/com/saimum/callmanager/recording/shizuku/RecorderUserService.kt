package com.saimum.callmanager.recording.shizuku

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.AttributionSource
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.RemoteException
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/**
 * Shizuku "user service": Shizuku starts this class in its own process running
 * as the **shell user** (uid 2000) — NOT inside the app process. That is the
 * whole trick behind Shizuku call recorders:
 *
 * - the shell user holds CAPTURE_AUDIO_OUTPUT, so `AudioSource.VOICE_CALL`
 *   (the real "both sides of the call" tap) is allowed here, while a normal
 *   app is refused it on Android 10+;
 * - the shell user can run `pm grant`, so the app's runtime permissions can be
 *   granted without the user tapping through dialogs.
 *
 * Captured PCM is streamed back to the app over Binder ([IAudioSink]); the app
 * does the file writing / silence analysis exactly as before. No file or
 * file-descriptor sharing between the two processes is needed (that would run
 * into SELinux / scoped-storage rules).
 *
 * This process has no Application object, which AudioRecord needs for its
 * attribution (package name + uid). [prepareEnvironment] builds a minimal fake
 * one for "com.android.shell" — the same approach scrcpy uses for its audio
 * capture.
 *
 * Must keep a public no-arg constructor (Shizuku instantiates it by name).
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi")
class RecorderUserService : IRecorderService.Stub() {

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var lastError: String = ""

    private var fakeContext: Context? = null
    private var environmentTried = false
    private var environmentNote: String = ""

    // --- IRecorderService ------------------------------------------------

    override fun destroy() {
        synchronized(this) { stopCaptureLocked() }
        exitProcess(0)
    }

    @Synchronized
    override fun start(sources: IntArray?, sampleRateHz: Int, sink: IAudioSink?): Int {
        stopCaptureLocked()
        lastError = ""

        if (sink == null || sources == null || sources.isEmpty()) {
            lastError = "bad arguments"
            return ERROR_BAD_ARGUMENTS
        }

        val context = prepareEnvironment()

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            lastError = "getMinBufferSize returned $minBuffer"
            return ERROR_UNSUPPORTED_FORMAT
        }
        val bufferSize = maxOf(minBuffer * 2, CHUNK_BYTES * 2)

        val errors = StringBuilder()
        if (environmentNote.isNotEmpty()) errors.append("env: ").append(environmentNote).append("; ")

        for (source in sources) {
            val record = try {
                createAudioRecord(source, sampleRateHz, bufferSize, context)
            } catch (t: Throwable) {
                errors.append("src").append(source).append(": ")
                    .append(t.javaClass.simpleName).append(' ').append(t.message).append("; ")
                continue
            }

            val started = try {
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    errors.append("src").append(source).append(": not initialized; ")
                    false
                } else {
                    record.startRecording()
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        true
                    } else {
                        errors.append("src").append(source).append(": did not start; ")
                        false
                    }
                }
            } catch (t: Throwable) {
                errors.append("src").append(source).append(": start ")
                    .append(t.javaClass.simpleName).append(' ').append(t.message).append("; ")
                false
            }

            if (!started) {
                try {
                    record.release()
                } catch (_: Throwable) {
                }
                continue
            }

            audioRecord = record
            running = true
            captureThread = Thread({ captureLoop(record, sink) }, "call-capture").also { it.start() }
            return source
        }

        lastError = errors.toString().ifEmpty { "no audio source could be opened" }
        Log.e(TAG, "start failed: $lastError")
        return ERROR_NO_SOURCE
    }

    @Synchronized
    override fun stop() {
        stopCaptureLocked()
    }

    override fun getLastError(): String = lastError

    override fun grantPermissions(packageName: String?, permissions: Array<String>?): String {
        if (packageName.isNullOrBlank() || permissions == null) return "bad arguments"
        if (!PACKAGE_NAME_REGEX.matches(packageName)) return "bad package name"

        val log = StringBuilder()
        for (permission in permissions) {
            if (permission !in GRANTABLE_PERMISSIONS) {
                log.append(permission).append(": not allowed\n")
                continue
            }
            val (code, output) = runCommand("pm", "grant", packageName, permission)
            log.append(permission).append(": ")
                .append(if (code == 0) "granted" else "failed ($output)").append('\n')
        }

        // Keep Android from killing the listening service in the background.
        val (idleCode, idleOutput) = runCommand("cmd", "deviceidle", "whitelist", "+$packageName")
        log.append("battery whitelist: ")
            .append(if (idleCode == 0) "ok" else "failed ($idleOutput)").append('\n')

        return log.toString().trim()
    }

    // --- capture ----------------------------------------------------------

    private fun captureLoop(record: AudioRecord, sink: IAudioSink) {
        val buffer = ByteArray(CHUNK_BYTES)
        var failures = 0
        while (running) {
            val read = try {
                record.read(buffer, 0, buffer.size)
            } catch (t: Throwable) {
                -1
            }
            if (read > 0) {
                failures = 0
                try {
                    // oneway: the data is marshalled right here, so the buffer can be reused.
                    sink.onPcm(if (read == buffer.size) buffer else buffer.copyOf(read))
                } catch (e: RemoteException) {
                    // The app process is gone — nobody left to record for.
                    Log.w(TAG, "App went away; ending capture", e)
                    break
                }
            } else {
                failures++
                if (failures >= MAX_READ_FAILURES) {
                    Log.e(TAG, "AudioRecord.read kept failing (last code $read); ending capture")
                    break
                }
                try {
                    Thread.sleep(READ_RETRY_DELAY_MILLIS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun stopCaptureLocked() {
        running = false
        val record = audioRecord
        audioRecord = null
        // stop() first: it unblocks a read() that is waiting for data.
        if (record != null) {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            } catch (_: Throwable) {
            }
        }
        captureThread?.let {
            try {
                it.join(JOIN_TIMEOUT_MILLIS)
            } catch (_: InterruptedException) {
            }
        }
        captureThread = null
        if (record != null) {
            try {
                record.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun createAudioRecord(source: Int, sampleRateHz: Int, bufferSize: Int, context: Context?): AudioRecord {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && context != null) {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRateHz)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val builder = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
            try {
                // Hidden API (API 31+): lets us say "this recording belongs to com.android.shell".
                AudioRecord.Builder::class.java
                    .getDeclaredMethod("setContext", Context::class.java)
                    .apply { isAccessible = true }
                    .invoke(builder, context)
            } catch (t: Throwable) {
                Log.w(TAG, "AudioRecord.Builder.setContext unavailable", t)
            }
            return builder.build()
        }
        // Android 10/11: the constructor reads the package from ActivityThread.getApplication(),
        // which prepareEnvironment() has pointed at the fake shell application.
        return AudioRecord(
            source,
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
    }

    // --- fake "com.android.shell" app environment --------------------------

    /**
     * Returns a Context that identifies this process as the shell package, or
     * null if the hidden-API setup failed (capture is then attempted anyway
     * and the failure, if any, is reported through [getLastError]).
     */
    private fun prepareEnvironment(): Context? {
        fakeContext?.let { return it }
        if (environmentTried) return null
        environmentTried = true

        return try {
            val context = onMainLooper {
                val activityThreadClass = Class.forName("android.app.ActivityThread")

                var thread: Any? = activityThreadClass.getMethod("currentActivityThread").invoke(null)
                if (thread == null) {
                    val constructor = activityThreadClass.getDeclaredConstructor().apply { isAccessible = true }
                    thread = constructor.newInstance()
                    activityThreadClass.getDeclaredField("sCurrentActivityThread")
                        .apply { isAccessible = true }.set(null, thread)
                    activityThreadClass.getDeclaredField("mSystemThread")
                        .apply { isAccessible = true }.setBoolean(thread, true)
                }

                val systemContext = activityThreadClass.getDeclaredMethod("getSystemContext")
                    .apply { isAccessible = true }
                    .invoke(thread) as Context
                val fake = FakeShellContext(systemContext)

                fillAppInfo(activityThreadClass, thread!!)
                fillInitialApplication(activityThreadClass, thread, fake)
                fake
            }
            fakeContext = context
            context
        } catch (t: Throwable) {
            environmentNote = "${t.javaClass.simpleName} ${t.message}"
            Log.e(TAG, "Couldn't prepare the shell app environment", t)
            null
        }
    }

    private fun fillAppInfo(activityThreadClass: Class<*>, thread: Any) {
        try {
            val bindDataClass = Class.forName("android.app.ActivityThread\$AppBindData")
            val bindData = bindDataClass.getDeclaredConstructor()
                .apply { isAccessible = true }
                .newInstance()
            val appInfo = ApplicationInfo().apply { packageName = SHELL_PACKAGE }
            bindDataClass.getDeclaredField("appInfo")
                .apply { isAccessible = true }.set(bindData, appInfo)
            activityThreadClass.getDeclaredField("mBoundApplication")
                .apply { isAccessible = true }.set(thread, bindData)
        } catch (t: Throwable) {
            Log.w(TAG, "fillAppInfo failed", t)
        }
    }

    private fun fillInitialApplication(activityThreadClass: Class<*>, thread: Any, base: Context) {
        try {
            val app = Application()
            ContextWrapper::class.java.getDeclaredField("mBase")
                .apply { isAccessible = true }.set(app, base)
            activityThreadClass.getDeclaredField("mInitialApplication")
                .apply { isAccessible = true }.set(thread, app)
        } catch (t: Throwable) {
            Log.w(TAG, "fillInitialApplication failed", t)
        }
    }

    /** ActivityThread's constructor creates a Handler, so it has to be built on a thread that has a Looper. */
    private fun <T> onMainLooper(block: () -> T): T {
        val main = Looper.getMainLooper()
        if (main == null) {
            Looper.prepareMainLooper()
            return block()
        }
        if (main.thread == Thread.currentThread()) return block()

        val result = AtomicReference<Any?>()
        val error = AtomicReference<Throwable?>()
        val latch = CountDownLatch(1)
        Handler(main).post {
            try {
                result.set(block())
            } catch (t: Throwable) {
                error.set(t)
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(MAIN_LOOPER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw IllegalStateException("main looper did not respond")
        }
        error.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result.get() as T
    }

    private class FakeShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = SHELL_PACKAGE
        override fun getOpPackageName(): String = SHELL_PACKAGE
        override fun getApplicationContext(): Context = this

        @RequiresApi(Build.VERSION_CODES.S)
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()
    }

    // --- helpers ------------------------------------------------------------

    private fun runCommand(vararg command: String): Pair<Int, String> = try {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor() to output
    } catch (t: Throwable) {
        -1 to (t.message ?: t.javaClass.simpleName)
    }

    companion object {
        private const val TAG = "RecorderUserService"
        private const val SHELL_PACKAGE = "com.android.shell"

        // 16 kHz mono PCM16 = 32,000 bytes/s, so this is ~200 ms of audio per Binder call.
        private const val CHUNK_BYTES = 6400
        private const val MAX_READ_FAILURES = 50
        private const val READ_RETRY_DELAY_MILLIS = 20L
        private const val JOIN_TIMEOUT_MILLIS = 2000L
        private const val MAIN_LOOPER_TIMEOUT_SECONDS = 5L

        const val ERROR_NO_SOURCE = -1
        const val ERROR_BAD_ARGUMENTS = -2
        const val ERROR_UNSUPPORTED_FORMAT = -3

        private val PACKAGE_NAME_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

        // Only what call recording needs — this is not a general "grant anything" door.
        private val GRANTABLE_PERMISSIONS = setOf(
            "android.permission.RECORD_AUDIO",
            "android.permission.READ_PHONE_STATE",
            "android.permission.POST_NOTIFICATIONS"
        )
    }
}
