package app.clustercontrol.core

import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun resource(name: String): String =
    object {}.javaClass.getResourceAsStream("/$name")!!.readBytes().toString(Charsets.UTF_8)

class ProtocolTest {
    private val us = ENTRY_SEP

    @Test
    fun statusSnapshot() {
        val out = """
            STATUS_OK=1
            ACTIVE_JOB=4242|train-aslg-pc12-grpo-few-shot|RUNNING
            QUEUE=eval:experiments/configs/a/b.yaml:t1${us}train:experiments/configs/c.yaml:t2:--resume
            QUEUE_COUNT=2
            LAST_JOB=4242:train:experiments/configs/a/b.yaml:t1:1
            STOPPED=1
            ERRORS_COUNT=3
            ERRORS_TAIL=["{\"a\":1}","x"]
        """.trimIndent()
        val s = parseSnapshot(out)
        assertEquals(ActiveJob("4242", "train-aslg-pc12-grpo-few-shot", "RUNNING"), s.active)
        assertEquals(2, s.queue.size)
        assertEquals("--resume", s.queue[1].extra)
        assertEquals(1, s.lastJob?.retries)
        assertTrue(s.stopped)
        assertEquals(3, s.errorsCount)
        assertEquals(listOf("{\"a\":1}", "x"), s.errorsTail)
    }

    @Test
    fun emptyStateAndMonitorExtras() {
        val log = Base64.getEncoder().encodeToString("  step=10 loss=0.5\n".toByteArray())
        val s = parseSnapshot("STATUS_OK=1\nACTIVE_JOB=\nQUEUE=\nQUEUE_COUNT=0\nLAST_JOB=\nSTOPPED=0\nERRORS_COUNT=0\nERRORS_TAIL=[]\nLOG_TAIL_B64=$log\nnoise line\n")
        assertNull(s.active)
        assertTrue(s.queue.isEmpty())
        assertEquals("  step=10 loss=0.5\n", s.logTail)
    }

    @Test
    fun missingStatusOkIsProtocolError() {
        assertFailsWith<ProtocolException> { parseSnapshot("OK_TICK=1\n") }
    }

    @Test
    fun entryBuildingValidates() {
        assertEquals("train:experiments/configs/a.yaml:tag1", buildEntry("train", "experiments/configs/a.yaml", "tag1").toLine())
        assertEquals("eval:p.yaml:t:--resume", buildEntry("eval", "p.yaml", "t", "--resume").toLine())
        assertFailsWith<IllegalArgumentException> { buildEntry("train", "p.yaml", "bad tag") }
        assertFailsWith<IllegalArgumentException> { buildEntry("run", "p.yaml", "t") }
        assertFailsWith<IllegalArgumentException> { buildEntry("train", "p.yaml", "t", "; rm -rf") }
    }
}

class LogParsingTest {
    @Test
    fun trainingProgress() {
        val log = """
            [stage 1] steps=1000
            some noise
              step=900 loss=0.123 reward=0.55 lr=1e-5
              step=910 loss=0.120 reward=0.56 lr=1e-5
            50%|#####     | 910/1000 [10:00<01:30, 1.5it/s]
        """.trimIndent()
        val p = parseProgress(log)
        assertEquals(910, p.step)
        assertEquals(1000, p.total)
        assertEquals(0.120, p.loss)
        assertEquals(0.56, p.reward)
        assertEquals("01:30", p.eta)
    }

    @Test
    fun evalProgressFallback() {
        assertEquals(30, parseProgress("eval progress: 30/100").step)
        assertNull(parseProgress("nothing here").step)
    }

    @Test
    fun seriesAndDownsample() {
        val text = (1..100).joinToString("\n") { "  step=$it loss=${1.0 / it} reward=0.5" }
        val s = parseSeries(text)
        assertEquals(100, s.getValue("loss").size)
        val d = downsample(s.getValue("loss"), 10)
        assertEquals(10, d.size)
        assertEquals(1, d.first().step)
        assertEquals(100, d.last().step)
    }
}

class CatalogTest {
    private val oracle = resource("config_map.tsv").lines().filter { it.isNotBlank() }
        .associate { it.substringBefore('\t') to it.substringAfter('\t') }
    private val tree = resource("config_tree.txt").lines().filter { it.isNotBlank() }

    @Test
    fun matchesTheRenderDriverConfigMap() {
        val catalog = ConfigCatalog.fromTree(tree).associate { it.name to it.path }
        assertEquals(oracle, catalog)
    }

    @Test
    fun resolveByNameAndAmbiguity() {
        val catalog = ConfigCatalog.fromTree(tree)
        assertEquals("aslg-pc12-grpo-few-shot", ConfigCatalog.resolve(catalog, "aslg-pc12-grpo-few-shot").name)
        assertFailsWith<IllegalArgumentException> { ConfigCatalog.resolve(catalog, "few-shot.yaml") }
        assertFailsWith<IllegalArgumentException> { ConfigCatalog.resolve(catalog, "does-not-exist") }
    }

    @Test
    fun presetsOfTheTuiResolveAgainstTheCatalog() {
        val catalog = ConfigCatalog.fromTree(tree)
        val presets = parsePresets(resource("presets.yaml"))
        assertTrue(presets.isNotEmpty())
        for (p in presets) {
            val entries = resolveJobs(catalog, p.jobs) // throws if a preset names a config that does not exist
            assertEquals(p.jobs.size, entries.size)
        }
    }
}

class SchedulesTest {
    private fun snap(active: ActiveJob? = null, queue: Int = 0, stopped: Boolean = false, errors: Int = 0) = Snapshot(
        active, List(queue) { JobEntry("train", "c.yaml", "t$it") }, null, stopped, errors, emptyList(),
    )

    @Test
    fun intervalIsClamped() {
        assertEquals(15, Schedule("a", "a", ScheduleAction.TICK, 5).effectiveIntervalMinutes)
        assertEquals(60, Schedule("a", "a", ScheduleAction.TICK, 60).effectiveIntervalMinutes)
    }

    @Test
    fun diffDetectsFinishAndStart() {
        val a = ActiveJob("1", "train-x", "RUNNING")
        val b = ActiveJob("2", "eval-x", "RUNNING")
        val kinds = diffSnapshots(snap(a, queue = 1), snap(b)).map { it.kind }
        assertTrue(NoticeKind.JOB_FINISHED in kinds && NoticeKind.JOB_STARTED in kinds)
        assertTrue(diffSnapshots(snap(a), snap(null)).any { it.kind == NoticeKind.JOB_FINISHED })
        assertTrue(diffSnapshots(snap(null, queue = 1), snap(null)).any { it.kind == NoticeKind.QUEUE_EMPTY })
        assertTrue(diffSnapshots(snap(), snap(errors = 2)).any { it.kind == NoticeKind.NEW_ERRORS })
        assertTrue(diffSnapshots(snap(), snap(stopped = true)).any { it.kind == NoticeKind.PAUSED })
        assertTrue(diffSnapshots(null, snap(a)).isEmpty())
    }
}

class SyncTest {
    private val files = mapOf(
        "cluster/_lib.sh" to "lib".toByteArray(),
        "cluster/train.sh" to "train".toByteArray(),
        "remote/cluster_helper.sh" to "helper".toByteArray(),
        "remote/app.py" to "driver".toByteArray(),
        "experiments/configs/aslg-pc12/qwen25-05b/base.yaml" to "base".toByteArray(),
        "src/x.py" to "x".toByteArray(),
        "README.md" to "r".toByteArray(),
    )
    private val fetcher = object : SourceFetcher {
        override fun tree(repo: String, branch: String) = files.keys.toList()
        override fun fetch(repo: String, branch: String, path: String) = files.getValue(path)
    }

    private class FakeRemote : RemoteStore {
        val store = mutableMapOf<String, ByteArray>()
        val modes = mutableMapOf<String, Boolean>()

        override fun hashes(relPaths: List<String>) = relPaths.associateWith { p ->
            store[p]?.let { MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) } }
        }

        override fun put(relPath: String, bytes: ByteArray, executable: Boolean) {
            store[relPath] = bytes
            modes[relPath] = executable
        }
    }

    @Test
    fun manifestSelectsOnlyWhatTheClusterNeeds() {
        val items = SyncManifest.items(files.keys.toList())
        val remote = items.map { it.remotePath }.toSet()
        assertEquals(
            setOf("cluster/_lib.sh", "cluster/train.sh", "cluster/cluster_helper.sh", "experiments/configs/aslg-pc12/qwen25-05b/base.yaml"),
            remote,
        )
        assertTrue("src/x.py" in SyncManifest.items(files.keys.toList(), SyncOptions(includeSrc = true)).map { it.remotePath })
    }

    @Test
    fun syncIsIdempotentAndUploadsOnlyChanges() {
        val remote = FakeRemote()
        val engine = SyncEngine(fetcher, "o/r")
        val first = engine.apply(remote)
        assertEquals(4, first.updated.size)
        assertEquals(true, remote.modes["cluster/cluster_helper.sh"])
        assertEquals(false, remote.modes["experiments/configs/aslg-pc12/qwen25-05b/base.yaml"])
        assertTrue(engine.apply(remote).updated.isEmpty())
        remote.store["cluster/train.sh"] = "stale".toByteArray()
        assertEquals(listOf("cluster/train.sh"), engine.apply(remote).updated)
        assertNotNull(engine.plan(remote).let { it })
    }
}
