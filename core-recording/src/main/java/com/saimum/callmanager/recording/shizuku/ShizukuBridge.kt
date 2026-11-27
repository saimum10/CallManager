package com.saimum.callmanager.recording.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/** Where we are on the way to "Shizuku can record call audio for us". */
enum class ShizukuState {
    /** Shizuku isn't running (or isn't installed / too old). */
    NOT_RUNNING,

    /** Shizuku is running but hasn't allowed this app yet. */
    NEEDS_PERMISSION,

    /** Allowed; the recorder service is starting up inside Shizuku. */
    CONNECTING,

    /** Recorder service is up — call recording will go through Shizuku. */
    READY
}

/**
 * The app's single point of contact with Shizuku.
 *
 * Flow: Shizuku running → user allows this app (one-time Shizuku dialog) →
 * we bind [RecorderUserService], which Shizuku starts as the shell user →
 * [ShizukuCallRecorder] drives it during calls, and [grantPermissions] uses it
 * to hand the app its runtime permissions.
 *
 * Call [init] once from Application.onCreate.
 */
object ShizukuBridge {

    private const val TAG = "ShizukuBridge"
    private const val PERMISSION_REQUEST_CODE = 4711
    private const val SERVICE_VERSION = 1
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    private val _state = MutableStateFlow(ShizukuState.NOT_RUNNING)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var initialized = false
    private var binding = false
    private var userServiceArgs: Shizuku.UserServiceArgs? = null

    @Volatile
    private var service: IRecorderService? = null

    /** What the last failed step said — shown in the UI so a failure isn't a mystery. */
    @Volatile
    var lastProblem: String? = null
        private set

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refresh() }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        service = null
        binding = false
        refresh()
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == PERMISSION_REQUEST_CODE) refresh()
        }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            if (binder != null && binder.pingBinder()) {
                service = IRecorderService.Stub.asInterface(binder)
                lastProblem = null
                _state.value = ShizukuState.READY
            } else {
                lastProblem = "Shizuku gave an empty recorder service"
                refresh()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
            refresh()
        }
    }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        } catch (t: Throwable) {
            Log.e(TAG, "Couldn't register Shizuku listeners", t)
        }
        refresh()
    }

    /** Re-reads Shizuku's state and moves forward (asks nothing from the user). Safe to call any time. */
    fun refresh() {
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) {
                service = null
                binding = false
                _state.value = ShizukuState.NOT_RUNNING
                return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                _state.value = ShizukuState.NEEDS_PERMISSION
                return
            }
            val current = service
            if (current != null && current.asBinder().pingBinder()) {
                _state.value = ShizukuState.READY
                return
            }
            service = null
            _state.value = ShizukuState.CONNECTING
            bindRecorderService()
        } catch (t: Throwable) {
            Log.e(TAG, "refresh failed", t)
            lastProblem = "${t.javaClass.simpleName}: ${t.message}"
            _state.value = ShizukuState.NOT_RUNNING
        }
    }

    /** Pops Shizuku's "Allow this app?" dialog. The result arrives through [state]. */
    fun requestPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                _state.value = ShizukuState.NOT_RUNNING
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                refresh()
                return
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                // The user picked "deny and don't ask again" in Shizuku — the dialog won't show.
                lastProblem = "Permission was denied in Shizuku. Open Shizuku → Authorized apps and allow Call Manager."
                return
            }
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (t: Throwable) {
            Log.e(TAG, "requestPermission failed", t)
            lastProblem = "${t.javaClass.simpleName}: ${t.message}"
        }
    }

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Intent that opens the Shizuku app, or null if it isn't installed. */
    fun launchIntent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)

    fun isReady(): Boolean {
        val current = service ?: return false
        return _state.value == ShizukuState.READY && current.asBinder().isBinderAlive
    }

    /** The live recorder service, or null if Shizuku isn't ready. Use off the main thread. */
    internal fun recorderService(): IRecorderService? = if (isReady()) service else null

    /**
     * Asks Shizuku (shell user) to grant [permissions] to [packageName] and to
     * exempt it from battery optimization. Blocking Binder call — run it off
     * the main thread. Returns a short log, or null if Shizuku isn't ready.
     */
    fun grantPermissions(packageName: String, permissions: List<String>): String? {
        val current = recorderService() ?: return null
        return try {
            current.grantPermissions(packageName, permissions.toTypedArray())
        } catch (t: Throwable) {
            Log.e(TAG, "grantPermissions failed", t)
            "failed: ${t.javaClass.simpleName} ${t.message}"
        }
    }

    private fun bindRecorderService() {
        if (binding) return
        val context = appContext ?: return
        binding = true
        try {
            val args = userServiceArgs ?: Shizuku.UserServiceArgs(
                ComponentName(context.packageName, RecorderUserService::class.java.name)
            )
                .processNameSuffix("recorder")
                .daemon(false) // goes away together with the app process
                .version(SERVICE_VERSION)
                .also { userServiceArgs = it }
            Shizuku.bindUserService(args, connection)
        } catch (t: Throwable) {
            binding = false
            Log.e(TAG, "bindUserService failed", t)
            lastProblem = "${t.javaClass.simpleName}: ${t.message}"
        }
    }
}
