package app.clustercontrol.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Needs the CC_IT_* environment of integration.ps1 (throwaway sshd + real helper scripts + fake SLURM); skipped otherwise. */
class ClusterIntegrationTest {
    private companion object {
        /** The project folder on the throwaway cluster: the helper scripts under test fix it (PROJ_DIR in cluster/_lib.sh). */
        const val PROJECT = "neuro_symbolic_t2g"
    }

    /** The repo as GitHub serves it: LF everywhere (the Windows working tree is CRLF, git stores LF). */
    private class DirFetcher(private val root: File) : SourceFetcher {
        private fun blob(path: String): ByteArray = File(root, path).readBytes().let { raw ->
            if (path.endsWith(".sh")) raw else raw.filter { it != '\r'.code.toByte() }.toByteArray()
        }

        override fun entries(repo: String, branch: String): List<TreeEntry> =
            (listOf("src", "cluster", "experiments/configs").map { File(root, it) }.filter { it.exists() }
                .flatMap { it.walkTopDown().filter { f -> f.isFile } } +
                listOf("main.py", "pyproject.toml").map { File(root, it) }.filter { it.isFile })
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .map { TreeEntry(it, gitBlobSha(blob(it)), blob(it).size.toLong()) }

        override fun fetch(repo: String, branch: String, path: String): ByteArray = blob(path)
    }

    private val memoryKeys = object : HostKeys {
        val m = mutableMapOf<String, String>()
        override fun get(host: String) = m[host]
        override fun put(host: String, fingerprint: String) { m[host] = fingerprint }
    }

    private fun itConfig(): ClusterConfig {
        val host = System.getenv("CC_IT_HOST")
        assumeTrue(host != null, "CC_IT_HOST not set: integration test skipped")
        return ClusterConfig(
            host = host,
            user = System.getenv("CC_IT_USER"),
            privateKeyPem = File(System.getenv("CC_IT_KEY_FILE")).readText(),
            port = System.getenv("CC_IT_PORT").toInt(),
        )
    }

    @Test
    fun keepAliveSessionRunsCommands() {
        ClusterSession.open(itConfig(), DirectTransport, memoryKeys, serverAliveIntervalMs = 15_000).use {
            assertEquals(0, it.exec("true").rc)
        }
    }

    /**
     * The cluster's own retry policy, against the real chain scripts: a train that hits the time limit is put back with
     * --resume (at most MAX_RESUMES times), also when it was the only job in the queue.
     */
    @Test
    fun aTrainThatHitsTheTimeLimitIsResumedUpToTwoTimes() {
        val cfg = itConfig()
        ClusterSession.open(cfg, DirectTransport, memoryKeys).use { s ->
          try {
            val helper = HelperApi(s, "~/$PROJECT")
            SyncEngine(DirFetcher(File(System.getenv("CC_IT_REPO_DIR"))), "owner/repo").apply(SshRemoteStore(s, PROJECT))
            s.exec("rm -f /tmp/fakeslurm/active ~/$PROJECT/.chain_state/last_job")
            helper.rewriteQueue(emptyList())
            helper.resume()
            val cfgPath = "experiments/configs/aslg-pc12/qwen25-05b/baseline/zero-shot.yaml"
            var job = assertNotNull(helper.enqueue(listOf(buildEntry("train", cfgPath, "it-timeout")), startNow = true).snapshot.active).id

            for (attempt in 1..MAX_RESUMES) {
                // the job runs into the 12 h limit: the scheduler reports TIMEOUT and nothing else is queued
                s.exec("rm -f /tmp/fakeslurm/active; echo TIMEOUT > /tmp/fakeslurm/state-$job")
                val snap = helper.tick().snapshot
                val resumed = assertNotNull(snap.active, "attempt $attempt: the train should have been resumed")
                assertTrue(resumed.id != job)
                assertEquals(attempt, snap.lastJob?.retries)
                assertEquals("TIMEOUT", parseJobError(snap.errorsTail.last())?.errorType)
                assertTrue(parseJobError(snap.errorsTail.last())?.willRetry == true)
                job = resumed.id
            }

            // the third timeout exceeds the retries: the chain stops and says so
            s.exec("rm -f /tmp/fakeslurm/active; echo TIMEOUT > /tmp/fakeslurm/state-$job")
            val last = helper.tick().snapshot
            assertNull(last.active, "no more resumes after $MAX_RESUMES")
            assertTrue(last.queue.isEmpty())
            assertEquals(false, parseJobError(last.errorsTail.last())?.willRetry)
            // another tick changes nothing (no error spam, no new job)
            val again = helper.tick().snapshot
            assertNull(again.active)
            assertEquals(last.errorsCount, again.errorsCount)
          } finally {
            // the other test starts from an empty cluster
            s.exec("rm -rf ~/$PROJECT /tmp/fakeslurm")
          }
        }
    }

    @Test
    fun fullClusterFlow() {
        val cfg = itConfig()
        ClusterSession.open(cfg, DirectTransport, memoryKeys).use { s ->
            val helper = HelperApi(s, "~/$PROJECT")

            // 1. empty cluster: the helper is missing
            assertFailsWith<HelperMissingException> { helper.status() }

            // 2. sync installs src + scripts + helper + configs; a second run changes nothing
            val engine = SyncEngine(DirFetcher(File(System.getenv("CC_IT_REPO_DIR"))), "owner/repo")
            val remote = SshRemoteStore(s, PROJECT)
            val first = engine.apply(remote)
            assertTrue(first.errors.isEmpty(), "sync errors: ${first.errors}")
            assertTrue("cluster/cluster_helper.sh" in first.updated)
            assertTrue(first.updated.any { it.startsWith("src/") && it.endsWith(".py") } && "pyproject.toml" in first.updated)
            assertTrue(first.updated.none { it.startsWith("experiments/results") || it.endsWith("presets.yaml") })
            assertEquals(0, s.exec("test -x ~/$PROJECT/cluster/cluster_helper.sh").rc)
            assertTrue(engine.apply(remote).updated.isEmpty())
            assertTrue(engine.status(remote).upToDate)
            // line endings do not count for code and configs (Windows uploads arrive as CRLF) ...
            val py = first.updated.first { it.startsWith("src/") && it.endsWith(".py") }
            s.exec("sed -i 's/\$/\\r/' ~/$PROJECT/$py")
            assertTrue(engine.status(remote).upToDate)
            // ... but a CRLF shell script does not run: it must show up, and the sync repairs it
            val sh = first.updated.first { it.startsWith("cluster/") && it.endsWith(".sh") && !it.endsWith("cluster_helper.sh") }
            s.exec("sed -i 's/\$/\\r/' ~/$PROJECT/$sh")
            assertEquals(listOf(sh), engine.status(remote).changes.map { it.path })
            assertEquals(listOf(sh), engine.apply(remote).updated)
            assertTrue(engine.status(remote).upToDate)

            // 3. queue management
            val st = helper.status()
            assertNull(st.active)
            assertTrue(st.queue.isEmpty())
            val cfgPath = "experiments/configs/aslg-pc12/qwen25-05b/baseline/zero-shot.yaml"
            val a = buildEntry("eval", cfgPath, "it-a")
            val b = buildEntry("eval", cfgPath, "it-b", "--resume")
            assertEquals(2, helper.enqueue(listOf(a, b)).snapshot.queue.size)
            assertEquals(listOf(b), helper.removeTag("it-a").queue)
            assertTrue(helper.rewriteQueue(emptyList()).queue.isEmpty())
            assertTrue(helper.pause().stopped)
            assertTrue(!helper.resume().stopped)

            // 4. start a job: enqueue + tick submits it through the fake sbatch
            val started = helper.enqueue(listOf(a), startNow = true)
            assertTrue(!started.tickFailed)
            val active = assertNotNull(started.snapshot.active, "the tick should have submitted the job")
            assertEquals("eval-it-a", active.name)
            val jobId = active.id

            // 5. monitor/timeseries/results read what the job wrote
            // what the runstatus library writes: a one-line state file per job and the series sidecar next to it
            assertNull(helper.monitor().runStatus, "no file yet")
            s.exec(
                """mkdir -p ~/$PROJECT/run_status && printf '%s' '{"run":{"kind":"eval","phase":"eval"},"progress":{"step":20,"total_steps":200,"eta_s":90},"metrics":{"latest":{"loss":0.8}}}' > ~/$PROJECT/run_status/$jobId.json && printf '%s' '{"total_steps":200,"series":{"loss":[[10,0.9],[20,0.8]]}}' > ~/$PROJECT/run_status/$jobId.series.json""",
            )
            val mon = helper.monitor()
            assertTrue(mon.runStatus.toString().contains(""""step":20"""), "RUN_STATUS: ${mon.runStatus}")
            val ts = helper.timeseries("it-a")
            assertTrue(ts.match)
            assertEquals(200, ts.total)
            assertEquals(2, ts.series.getValue("loss").size)
            s.exec("d=~/$PROJECT/experiments/results/aslg-pc12/qwen25-05b/baseline/zero-shot/run_1; mkdir -p \$d && echo '{\"rouge_l_mean\":0.5}' > \$d/eval_final.json")
            assertEquals(listOf("aslg-pc12/qwen25-05b/baseline/zero-shot"), helper.results("").keys.toList())
            assertTrue(helper.results("aslg-pc12-baseline-zero-shot").getValue("run_1").contains("rouge_l_mean"))

            // 6. kill
            assertNull(helper.kill().active)
            assertFailsWith<NoActiveJobException> { helper.kill() }
        }
    }
}
