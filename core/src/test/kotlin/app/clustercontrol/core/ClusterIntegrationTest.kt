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
    private class DirFetcher(private val root: File) : SourceFetcher {
        override fun tree(repo: String, branch: String): List<String> =
            listOf("cluster", "remote/cluster_helper.sh", "experiments/configs").map { File(root, it) }
                .filter { it.exists() }
                .flatMap { it.walkTopDown().filter { f -> f.isFile } }
                .map { it.relativeTo(root).invariantSeparatorsPath }

        override fun fetch(repo: String, branch: String, path: String): ByteArray = File(root, path).readBytes()
    }

    private val memoryKeys = object : HostKeys {
        val m = mutableMapOf<String, String>()
        override fun get(host: String) = m[host]
        override fun put(host: String, fingerprint: String) { m[host] = fingerprint }
    }

    @Test
    fun generatedKeyAuthenticates() {
        val host = System.getenv("CC_IT_HOST")
        assumeTrue(host != null, "CC_IT_HOST not set: integration test skipped")
        val key = KeyGen.generate("cc-it")
        File(System.getenv("CC_IT_AUTHORIZED_KEYS")).appendText("\n" + key.publicKeyLine + "\n")
        val cfg = ClusterConfig(host, System.getenv("CC_IT_USER"), key.privateKeyPem, port = System.getenv("CC_IT_PORT").toInt())
        ClusterSession.open(cfg, DirectTransport, memoryKeys).use { assertEquals(0, it.exec("true").rc) }
    }

    @Test
    fun fullClusterFlow() {
        val host = System.getenv("CC_IT_HOST")
        assumeTrue(host != null, "CC_IT_HOST not set: integration test skipped")
        val cfg = ClusterConfig(
            host = host,
            user = System.getenv("CC_IT_USER"),
            privateKeyPem = File(System.getenv("CC_IT_KEY_FILE")).readText(),
            port = System.getenv("CC_IT_PORT").toInt(),
        )
        ClusterSession.open(cfg, DirectTransport, memoryKeys).use { s ->
            val helper = HelperApi(s)

            // 1. empty cluster: the helper is missing
            assertFailsWith<HelperMissingException> { helper.status() }

            // 2. sync installs scripts + helper + configs; a second run changes nothing
            val engine = SyncEngine(DirFetcher(File(System.getenv("CC_IT_REPO_DIR"))), "owner/repo")
            val remote = SshRemoteStore(s)
            val first = engine.apply(remote)
            assertTrue(first.errors.isEmpty(), "sync errors: ${first.errors}")
            assertTrue("cluster/cluster_helper.sh" in first.updated)
            assertEquals(0, s.exec("test -x ~/neuro_symbolic_t2g/cluster/cluster_helper.sh").rc)
            assertTrue(engine.apply(remote).updated.isEmpty())

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
            s.exec(
                "mkdir -p ~/neuro_symbolic_t2g/logs && printf '[stage 1] steps=200\\n  step=10 loss=0.9 reward=0.1\\n  step=20 loss=0.8 reward=0.2\\n' > ~/neuro_symbolic_t2g/logs/slurm-eval-$jobId.log",
            )
            val mon = helper.monitor()
            assertEquals(20, parseProgress(mon.logTail.orEmpty()).step)
            val ts = helper.timeseries("it-a")
            assertTrue(ts.match)
            assertEquals(200, ts.total)
            assertEquals(2, ts.series.getValue("loss").size)
            s.exec("d=~/neuro_symbolic_t2g/experiments/results/aslg-pc12/qwen25-05b/baseline/zero-shot/run_1; mkdir -p \$d && echo '{\"rouge_l_mean\":0.5}' > \$d/eval_final.json")
            assertEquals(listOf("aslg-pc12/qwen25-05b/baseline/zero-shot"), helper.results("").keys.toList())
            assertTrue(helper.results("aslg-pc12-baseline-zero-shot").getValue("run_1").contains("rouge_l_mean"))

            // 6. kill
            assertNull(helper.kill().active)
            assertFailsWith<NoActiveJobException> { helper.kill() }
        }
    }
}
