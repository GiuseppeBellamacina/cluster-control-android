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
    fun runStatusIsParsedWhenPresent() {
        val base = listOf("STATUS_OK=1", "ACTIVE_JOB=", "QUEUE=", "QUEUE_COUNT=0", "LAST_JOB=", "STOPPED=0", "ERRORS_COUNT=0", "ERRORS_TAIL=[]")
            .joinToString("\n", postfix = "\n")
        assertNull(parseSnapshot(base).runStatus)
        val s = parseSnapshot(base + """RUN_STATUS={"run":{"kind":"train","phase":"sft"},"progress":{"step":7}}""" + "\n")
        assertEquals(7, (s.runStatus!!["progress"] as kotlinx.serialization.json.JsonObject)["step"].toString().toInt())
        assertNull(parseSnapshot(base + "RUN_STATUS=garbage\n").runStatus)
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

class SeriesTest {
    @Test
    fun parsesTheRunstatusSidecar() {
        val (total, series) = parseSeriesJson(
            """{"schema":3,"total_steps":500,"series":{"loss":[[10,0.5],[20,0.4]],"empty":[],"bad":[["x",1]]}}""",
        )
        assertEquals(500, total)
        assertEquals(listOf(Point(10, 0.5), Point(20, 0.4)), series.getValue("loss"))
        assertTrue("empty" !in series && "bad" !in series)
    }

    @Test
    fun malformedOrMissingSidecarYieldsNothing() {
        assertEquals(null to emptyMap(), parseSeriesJson(""))
        assertEquals(null to emptyMap(), parseSeriesJson("not json"))
        assertEquals(null to emptyMap(), parseSeriesJson("[1,2]"))
    }

    @Test
    fun downsampleKeepsBothEnds() {
        val pts = (1..100).map { Point(it, 1.0 / it) }
        val d = downsample(pts, 10)
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
    fun matchesTheReferenceConfigMap() {
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

class UpdatesTest {
    @Test
    fun versionComparison() {
        assertTrue(Updates.isNewer("0.3.0", "0.2.2"))
        assertTrue(Updates.isNewer("v0.10.0", "0.9.9"))
        assertTrue(Updates.isNewer("1.0", "0.9.9"))
        assertTrue(!Updates.isNewer("0.2.2", "0.2.2"))
        assertTrue(!Updates.isNewer("v1.0", "1.0.0"))
        assertTrue(!Updates.isNewer("0.2.1", "0.2.2"))
        assertTrue(!Updates.isNewer("0.3.0-rc1", "0.3.0"))
    }

    @Test
    fun parsesTheLatestRelease() {
        val json = """{"tag_name":"v0.3.0","body":"  Notes\n- one ","assets":[
            {"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":1},
            {"name":"ClusterControl-0.3.0.apk","browser_download_url":"https://x/a.apk","size":13000000,"digest":"sha256:ABCDEF"}]}"""
        val r = assertNotNull(Updates.parseLatest(json))
        assertEquals("0.3.0", r.version)
        assertEquals("https://x/a.apk", r.apkUrl)
        assertEquals(13_000_000L, r.sizeBytes)
        assertEquals("abcdef", r.sha256)
        assertEquals("Notes\n- one", r.notes)
        // no digest field and no apk asset
        assertNull(Updates.parseLatest("""{"tag_name":"v1","assets":[{"name":"a.apk","browser_download_url":"u"}]}""")!!.sha256)
        assertNull(Updates.parseLatest("""{"tag_name":"v1","assets":[]}"""))
    }
}

class RunStatusViewTest {
    private fun parse(text: String) = parseRunStatus(kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject)

    @Test
    fun readsAFileWrittenByTheRealLibrary() {
        // generated by runstatus itself (see core/src/test/resources): the shape is the contract
        val v = assertNotNull(parse(resource("run_status_sample.json")))
        assertEquals("train", v.kind)
        assertEquals("grpo", v.phase)
        assertEquals("running", v.state)
        assertEquals(3000, v.step)
        assertEquals(5000, v.total)
        assertEquals(60, percentOf(v.step, v.total))
        assertEquals(0.857, v.latest.getValue("reward"), 0.001)
        assertEquals(setOf("bleu_reward", "gloss_order_reward"), v.rewardComponents.keys)
        assertEquals(0.5, v.rewardComponents.getValue("bleu_reward"))
        assertTrue("lr" in v.otherMetrics && "grad_norm" in v.otherMetrics && "completions/mean_length" in v.otherMetrics)
        assertTrue("loss" !in v.otherMetrics && "reward" !in v.otherMetrics)
        assertEquals(11.4, v.gpuMemGb)
        assertTrue(v.mean.containsKey("reward") && v.best.containsKey("loss"))
        assertEquals(2, v.samples.size)
        assertEquals(true, v.samples[0].match)
        assertEquals(mapOf("bleu_reward" to -0.2, "gloss_order_reward" to 0.5), v.samples[1].rewards)
    }

    @Test
    fun readsWhatKindOfRunItIs() {
        val v = assertNotNull(parse(resource("run_status_sample.json")))
        assertEquals("grpo", v.method)
        assertEquals("llm", v.modelFamily)
        assertEquals("Qwen2.5-0.5B-Instruct", v.modelName)
        assertEquals("0.5B", v.modelParams)
        assertEquals("grpo", v.stage)
        assertEquals(listOf("sft" to "done", "grpo" to "running", "eval" to "pending"), v.stages.map { it.id to it.state })
        assertEquals(7770, v.stages.first().total)
        assertEquals(listOf("LoRA r=32" to null, "4bit" to "gpu"), v.badges.map { it.label to it.icon })
    }

    @Test
    fun anyKindOfRunIsAccepted() {
        val v = assertNotNull(
            parse("""{"run":{"kind":"inference","method":"ppo","model":{"family":"cnn"},"stages":[{"id":"warmup"},{"id":"x","label":"X","icon":"🔥","state":"failed"}],"badges":["a",{"label":"b"},3]}}"""),
        )
        assertEquals("ppo", v.method)
        assertEquals("cnn", v.modelFamily)
        assertEquals(listOf("warmup" to "pending", "x" to "failed"), v.stages.map { it.id to it.state })
        assertEquals("warmup", v.stages.first().label)
        assertEquals("🔥", v.stages[1].icon)
        assertEquals(listOf("a", "b"), v.badges.map { it.label })
    }

    @Test
    fun seriesSidecarOfTheRealLibraryParses() {
        val (total, series) = parseSeriesJson(resource("run_status_sample.series.json"))
        assertEquals(5000, total)
        assertTrue(series.keys.containsAll(listOf("loss", "reward", "rewards/bleu_reward")))
        assertTrue(series.getValue("loss").size in 2..200)
    }

    @Test
    fun aStepPastTheTotalNeverExceedsOneHundredPercent() {
        val v = assertNotNull(parse("""{"progress":{"step":7770,"total_steps":5000}}"""))
        assertEquals(7770, v.total)
        assertEquals(100, percentOf(v.step, v.total))
    }

    @Test
    fun evalResultsAndLegacyStringSamples() {
        val v = assertNotNull(
            parse(
                """{"run":{"kind":"eval"},"eval":{"active":true,"progress":"50/200"},
                    "metrics":{"latest":{"eval/rouge_l_mean":0.6,"eval/bleu":0.4,"loss":1.0}},"samples":["plain text",{"prompt":"p"}]}""",
            ),
        )
        assertEquals("eval", v.kind)
        assertTrue(v.evalActive)
        assertEquals("50/200", v.evalProgress)
        assertEquals(mapOf("rouge_l_mean" to 0.6, "bleu" to 0.4), v.evalResults)
        assertEquals(listOf("plain text", "p"), v.samples.map { it.prompt })
    }

    @Test
    fun missingEverythingIsFine() {
        assertNull(parseRunStatus(null))
        val v = assertNotNull(parse("{}"))
        assertNull(v.step)
        assertTrue(v.latest.isEmpty() && v.samples.isEmpty() && !v.evalActive)
    }
}

class AutoAdvanceTest {
    private val job = ActiveJob("1", "train-x", "RUNNING")
    private val entry = JobEntry("train", "c.yaml", "t1")

    private fun snap(active: ActiveJob? = null, queue: List<JobEntry> = listOf(entry), stopped: Boolean = false, last: String? = null) =
        Snapshot(active, queue, last?.let { LastJob(it, "train", "c.yaml", "t0", 0) }, stopped, 0, emptyList())

    @Test
    fun ticksAsSoonAsTheJobIsOverAndWorkWaits() {
        val a = AutoAdvance()
        assertEquals(Advance.Wait, a.decide(snap(active = job), 0))
        assertEquals(Advance.Tick, a.decide(snap(last = "1"), 1_000))
    }

    @Test
    fun nothingToDoWhenPausedEmptyOrNoSnapshot() {
        val a = AutoAdvance()
        assertEquals(Advance.Wait, a.decide(null, 0))
        assertEquals(Advance.Wait, a.decide(snap(stopped = true), 0))
        assertEquals(Advance.Wait, a.decide(snap(queue = emptyList()), 0))
    }

    @Test
    fun aStartedJobEndsTheAttempts() {
        val a = AutoAdvance()
        assertEquals(Advance.Tick, a.decide(snap(), 0))
        assertEquals(Advance.Wait, a.ticked(snap(active = job, queue = emptyList()), 0))
        assertEquals(Advance.Tick, a.decide(snap(), 1), "a fresh situation starts with no backoff")
    }

    @Test
    fun retriesWithBackoffThenGivesUpOnce() {
        val a = AutoAdvance(listOf(10, 20, 30))
        var now = 0L
        val waits = mutableListOf<Long>()
        var result: Advance = Advance.Wait
        repeat(3) {
            assertEquals(Advance.Tick, a.decide(snap(), now))
            result = a.ticked(snap(), now)
            if (result == Advance.Wait) {
                assertEquals(Advance.Wait, a.decide(snap(), now + 1), "not due yet")
                now += listOf(10L, 20L, 30L)[it]
                waits += now
            }
        }
        assertEquals(listOf(10L, 30L), waits)
        assertTrue(result is Advance.GiveUp)
        assertEquals(Advance.Wait, a.decide(snap(), now + 10_000), "after giving up it stays quiet")
    }

    @Test
    fun aFailedTickCountsLikeAJobThatDoesNotStart() {
        val a = AutoAdvance(listOf(10, 20))
        assertEquals(Advance.Tick, a.decide(snap(), 0))
        assertEquals(Advance.Wait, a.ticked(null, 0))
        assertTrue(a.ticked(null, 10) is Advance.GiveUp)
    }

    @Test
    fun resetAndNewSituationsStartOver() {
        val a = AutoAdvance(listOf(10))
        a.decide(snap(), 0)
        assertTrue(a.ticked(snap(), 0) is Advance.GiveUp)
        assertEquals(Advance.Wait, a.decide(snap(), 100))
        assertEquals(Advance.Tick, a.decide(snap(last = "2"), 100), "another job finished meanwhile")
        a.ticked(snap(last = "2"), 100)
        a.reset()
        assertEquals(Advance.Tick, a.decide(snap(last = "2"), 100))
    }
}

class JobErrorTest {
    @Test
    fun parsesTheClusterErrorLine() {
        val line = """{"tag":"t1","job_type":"train","slurm_id":"7","config":"c.yaml","error_type":"TIMEOUT","slurm_state":"TIMEOUT","exit_code":"0:0","timestamp":"2026-11-13 10:00:00","error_snippet":"x","retry_num":1,"resolved":true}"""
        val e = assertNotNull(parseJobError(line))
        assertEquals("t1", e.tag)
        assertEquals("TIMEOUT", e.errorType)
        assertEquals(1, e.retryNum)
        assertTrue(e.willRetry)
        assertEquals("Tempo limite (12 h) raggiunto", describeError(e.errorType))
        assertNull(parseJobError("not json"))
    }
}
