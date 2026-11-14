package app.clustercontrol

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf

import java.security.Security
import java.util.concurrent.TimeUnit
import app.clustercontrol.core.AutoAdvance
import app.clustercontrol.core.NoticeKind
import app.clustercontrol.core.parseRunStatus
import app.clustercontrol.core.diffSnapshots
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.bouncycastle.jce.provider.BouncyCastleProvider

private const val ERRORS = "errors"
private const val JOBS = "jobs"

class AndroidNotifier(private val ctx: Context) : Notifier {
    init {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ERRORS, "Errori", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(JOBS, "Job", NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun notify(id: Int, title: String, text: String) {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val open = android.app.PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, if (id == NoticeIds.FAILURE || id == NoticeIds.SUSPENDED || id == NoticeIds.STUCK) ERRORS else JOBS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }
}

/** One unique periodic work per enabled schedule; [desiredWork] decides which ones. */
class WorkScheduler(private val ctx: Context, private val stores: Stores) {
    fun sync() {
        val wm = WorkManager.getInstance(ctx)
        val want = desiredWork(stores.schedules.value, stores.state.value)
        val names = want.map { workName(it.id) }.toSet()
        want.forEach { s ->
            val request = PeriodicWorkRequestBuilder<ActionWorker>(s.effectiveIntervalMinutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresCharging(s.requireCharging).build(),
                )
                .setInputData(workDataOf("id" to s.id))
                .addTag(TAG)
                .build()
            wm.enqueueUniquePeriodicWork(workName(s.id), ExistingPeriodicWorkPolicy.UPDATE, request)
        }
        // cancel the works of disabled/deleted schedules (the unique name is also a tag of the info)
        wm.getWorkInfosByTag(TAG).get().flatMap { it.tags }.filter { it.startsWith(PREFIX) && it !in names }
            .distinct().forEach { wm.cancelUniqueWork(it) }
    }

    private fun workName(id: String) = PREFIX + id

    private var etaTarget = 0L

    /**
     * One check shortly after the running job should end, so the next job starts within minutes even with the app
     * closed (periodic work cannot run more often than every 15 minutes). Re-arming only when the target moves.
     */
    fun scheduleCheck(etaSeconds: Long) {
        val delayMs = (etaSeconds + 90).coerceIn(120, 13 * 3600) * 1000
        val target = System.currentTimeMillis() + delayMs
        if (kotlin.math.abs(target - etaTarget) < 5 * 60_000) return
        etaTarget = target
        val request = OneTimeWorkRequestBuilder<AdvanceWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(ETA_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val TAG = "cc"
        const val PREFIX = "sched-"
        const val ETA_WORK = "eta-advance"
    }
}

/** The check scheduled for when the running job should end: starts the next one if the cluster is idle. */
class AdvanceWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val g = ClusterApp.graph
        g.ops.advance("fine job prevista")
        // still running (the estimate was short)? look again when it should be over
        val s = g.stores.state.value.snapshot
        if (s?.active != null) g.scheduler.scheduleCheck(parseRunStatus(s.runStatus)?.etaSeconds ?: 900)
        return Result.success()
    }
}

class ActionWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    // Always success: a failed slot is recorded and notified, never retried (the next slot is the retry).
    override suspend fun doWork(): Result {
        val g = ClusterApp.graph
        val s = g.stores.schedules.value.firstOrNull { it.id == inputData.getString("id") }
        if (s != null && s.enabled && desiredWork(listOf(s), g.stores.state.value).isNotEmpty()) {
            g.ops.run(s.action, s.name, auto = true)
        }
        return Result.success()
    }
}

class Graph(ctx: Context) {
    val lockPrefs = ctx.getSharedPreferences("app_lock", Context.MODE_PRIVATE)
    val lock = LockPolicy(lockPrefs.getBoolean("enabled", true))
    val stores = Stores(ctx.filesDir)
    val notifier = AndroidNotifier(ctx)
    val scheduler = WorkScheduler(ctx, stores)
    private val connector = Connector(SecureHostKeys(ctx))
    private val recorder = Recorder(stores, notifier, onSuspended = { scheduler.sync() })
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // a job ending is news only when the app is not on screen (otherwise the dashboard shows it)
    val advancer = AutoAdvance()
    val live = LiveSession(connector, stores, recorder::handleFailure, scope, onChange = { prev, now, foreground ->
        now.active?.let { scheduler.scheduleCheck(parseRunStatus(now.runStatus)?.etaSeconds ?: 900) }
        if (!foreground) {
            diffSnapshots(prev, now).forEach {
                val id = if (it.kind == NoticeKind.ALL_DONE) NoticeIds.ALL_DONE else NoticeIds.JOB_BASE + (it.key.toIntOrNull() ?: 0)
                notifier.notify(id, it.title, it.message)
            }
        }
    })
    val ops = ClusterOps(live, connector, stores, recorder, advancer, notifier)

    init {
        // the live session polls every few seconds: the moment a job is over and work waits, the next one is started
        scope.launch {
            live.state.map { it.snapshot to it.snapshotAt }.distinctUntilChanged().collect { (snap, at) ->
                val fresh = at != null && System.currentTimeMillis() - at < 60_000
                if (snap != null && fresh) runCatching { ops.advance("automatico", snap) }
            }
        }
    }
}

class ClusterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android's stripped "BC" would shadow the full one (no Ed25519 before API 33)
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        Creds.init(this)
        graph = Graph(this)
        Thread { graph.scheduler.sync() }.start()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> { graph.live.start(); graph.lock.onReturn() }
                    Lifecycle.Event.ON_STOP -> { graph.live.stop(); graph.lock.onLeave() }
                    else -> {}
                }
            },
        )
    }

    companion object {
        lateinit var graph: Graph
    }
}
