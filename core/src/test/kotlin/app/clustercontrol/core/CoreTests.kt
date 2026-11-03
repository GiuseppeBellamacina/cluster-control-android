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
    fun etaSeconds() {
        assertEquals(3723L, parseEtaSeconds("1:02:03"))
        assertEquals(754L, parseEtaSeconds("12:34"))
        assertNull(parseEtaSeconds("?"))
        assertNull(parseEtaSeconds("00:??"))
        assertNull(parseEtaSeconds(null))
    }

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
    fun diffNoticesJobEndAndAllDone() {
        val a = ActiveJob("1", "train-conll-2003-qwen25-15b-grpo-zero-shot", "RUNNING")
        val b = ActiveJob("2", "eval-x", "RUNNING")
        // a job ended and the next one is running: one notice for the ended job
        val n = diffSnapshots(snap(a, queue = 1), snap(b)).single()
        assertEquals(NoticeKind.JOB_FINISHED, n.kind)
        assertEquals("1", n.key)
        assertTrue("CoNLL-2003" in n.message)
        // ended together with new chain errors: failed
        assertEquals(NoticeKind.JOB_FAILED, diffSnapshots(snap(a, queue = 1), snap(b, errors = 1)).single().kind)
        // the last job ended and nothing is left: a single "all done" notice that names it
        val done = diffSnapshots(snap(a), snap(null)).single()
        assertEquals(NoticeKind.ALL_DONE, done.kind)
        assertTrue("Ultimo" in done.message && "terminato" in done.message)
        assertTrue("non riuscito" in diffSnapshots(snap(a), snap(null, errors = 1)).single().message)
        // queue drained while idle
        assertEquals(NoticeKind.ALL_DONE, diffSnapshots(snap(null, queue = 1), snap(null)).single().kind)
        // quiet cases
        assertTrue(diffSnapshots(snap(a), snap(a)).isEmpty())
        assertTrue(diffSnapshots(snap(), snap(stopped = true)).isEmpty())
        assertTrue(diffSnapshots(snap(), snap(errors = 2)).isEmpty())
        assertTrue(diffSnapshots(snap(null, queue = 1), snap(b, queue = 0)).isEmpty())
        assertTrue(diffSnapshots(null, snap(a)).isEmpty())
    }
}

class SyncTest {
    private fun entry(path: String, size: Long = 10) = TreeEntry(path, gitBlobSha(path.toByteArray()), size)

    private val files = mapOf(
        "cluster/_lib.sh" to "lib",
        "cluster/train.sh" to "train",
        "cluster/presets.yaml" to "presets",
        "src/a.py" to "a",
        "src/data/words.json" to "w",
        "src/__pycache__/a.py" to "c",
        "experiments/configs/x/base.yaml" to "base",
        "experiments/results/x/run/eval_final.json" to "r",
        "experiments/figures/f.png" to "f",
        "main.py" to "main",
        "pyproject.toml" to "pp",
        "README.md" to "readme",
    ).mapValues { it.value.toByteArray() }

    private val fetcher = object : SourceFetcher {
        override fun entries(repo: String, branch: String) = files.map { (p, b) -> TreeEntry(p, gitBlobSha(b), b.size.toLong()) }
        override fun fetch(repo: String, branch: String, path: String) = files.getValue(path)
    }

    private class FakeRemote : RemoteStore {
        val store = mutableMapOf<String, ByteArray>()
        val modes = mutableMapOf<String, Boolean>()

        override fun blobShas(relPaths: List<String>) = relPaths.associateWith { store[it]?.let(::gitBlobSha) }

        override fun putAll(files: List<RemoteFile>) = files.forEach {
            store[it.relPath] = it.bytes
            modes[it.relPath] = it.executable
        }
    }

    @Test
    fun gitBlobShaMatchesGit() {
        // `printf 'hello\n' | git hash-object --stdin`
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", gitBlobSha("hello\n".toByteArray()))
    }

    @Test
    fun manifestIsAnAllowList() {
        val items = SyncManifest.items(fetcher.entries("o/r", "main"))
        assertEquals(
            listOf("cluster/_lib.sh", "cluster/train.sh", "experiments/configs/x/base.yaml", "main.py", "pyproject.toml", "src/a.py"),
            items.map { it.path },
        )
        assertEquals(listOf(true, true, false, false, false, false), items.map { it.executable })
        // never results, figures, app-only presets, docs, caches or oversized files
        val big = SyncManifest.items(listOf(entry("src/huge.py", 600_000), entry("experiments/configs/a.yaml")))
        assertEquals(listOf("experiments/configs/a.yaml"), big.map { it.path })
    }

    @Test
    fun statusListsOnlyWhatDiffers() {
        val remote = FakeRemote()
        val engine = SyncEngine(fetcher, "o/r")
        val first = engine.status(remote)
        assertEquals(6, first.checked)
        assertTrue(first.changes.all { it.isNew } && first.changes.size == 6)
        engine.apply(remote)
        assertTrue(engine.status(remote).upToDate)
        remote.store["cluster/train.sh"] = "stale".toByteArray()
        assertEquals(listOf(SyncChange("cluster/train.sh", isNew = false)), engine.status(remote).changes)
    }

    @Test
    fun syncCanLeaveTheRunningCodeAlone() {
        val remote = FakeRemote()
        val r = SyncEngine(fetcher, "o/r").apply(remote, skip = SyncManifest::isCode)
        assertEquals(listOf("cluster/_lib.sh", "cluster/train.sh", "experiments/configs/x/base.yaml"), r.updated)
        assertEquals(listOf("main.py", "pyproject.toml", "src/a.py"), r.skipped)
        assertTrue("src/a.py" !in remote.store)
    }

    @Test
    fun syncIsIdempotentAndUploadsOnlyChanges() {
        val remote = FakeRemote()
        val engine = SyncEngine(fetcher, "o/r")
        val first = engine.apply(remote)
        assertEquals(6, first.updated.size)
        assertTrue(first.errors.isEmpty())
        assertEquals(true, remote.modes["cluster/train.sh"])
        assertEquals(false, remote.modes["src/a.py"])
        assertTrue("experiments/results/x/run/eval_final.json" !in remote.store && "README.md" !in remote.store)
        assertTrue(engine.apply(remote).updated.isEmpty())
        remote.store["src/a.py"] = "stale".toByteArray()
        assertEquals(listOf("src/a.py"), engine.apply(remote).updated)
    }
}

class TimingAndNamesTest {
    @Test
    fun countdownAndAgo() {
        assertEquals("23:41", formatCountdown(23 * 60_000L + 41_000))
        assertEquals("1:02:03", formatCountdown(3_723_000))
        assertEquals("ora", formatCountdown(-5))
        assertEquals("adesso", formatAgo(10_000))
        assertEquals("12 min fa", formatAgo(12 * 60_000L))
        assertEquals("3 h fa", formatAgo(3 * 3_600_000L))
        assertEquals("2 g fa", formatAgo(2 * 86_400_000L))
    }

    @Test
    fun nextTick() {
        assertNull(nextTickAt(null, 60, 1_000))
        assertEquals(1_000 + 60 * 60_000L, nextTickAt(1_000, 60, 5_000))
        assertEquals(1_000 + 15 * 60_000L, nextTickAt(1_000, 5, 5_000)) // clamped to the 15 min minimum
    }

    @Test
    fun percentAndMove() {
        assertEquals(31, percentOf(1560, 5000))
        assertNull(percentOf(null, 5000))
        assertNull(percentOf(10, 0))
        assertEquals(listOf("b", "c", "a"), listOf("a", "b", "c").moved(0, 2))
        assertEquals(listOf("c", "a", "b"), listOf("a", "b", "c").moved(2, 0))
        assertFailsWith<IllegalArgumentException> { listOf("a").moved(0, 3) }
    }

    @Test
    fun jobTitles() {
        assertEquals(JobTitle("train", "CoNLL-2003 · Qwen2.5 1.5B", "GRPO · zero-shot"), JobNames.parse("train-conll-2003-qwen25-15b-grpo-zero-shot"))
        assertEquals(JobTitle("eval", "ASLG-PC12 · Qwen2.5 0.5B", "SFT + GRPO · few-shot"), JobNames.parse("eval-aslg-pc12-sft-grpo-few-shot"))
        assertEquals("Baseline · zero-shot · no grammar", JobNames.parse("phoenix-2014t-baseline-zero-shot-no-grammar").subtitle)
        assertEquals("Ablation · loss · dr-grpo", JobNames.parse("aslg-pc12-ablations-loss-dr-grpo").subtitle)
        assertEquals(JobTitle(null, "something-else", ""), JobNames.parse("something-else"))
        assertEquals("eval", JobNames.of(JobEntry("eval", "c.yaml", "wos-46985-baseline-zero-shot")).type)
    }

    @Test
    fun everyCatalogCellHasAReadableTitle() {
        val tree = object {}.javaClass.getResourceAsStream("/config_tree.txt")!!.readBytes().toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
        for (c in ConfigCatalog.fromTree(tree)) {
            val t = JobNames.parse(c.name)
            assertTrue(" · " in t.title && t.subtitle.isNotBlank(), "no readable title for ${c.name}: $t")
        }
    }
}
