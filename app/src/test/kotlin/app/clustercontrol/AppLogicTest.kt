package app.clustercontrol

import app.clustercontrol.core.ActiveJob
import app.clustercontrol.core.HelperMissingException
import app.clustercontrol.core.HostKeyChangedException
import app.clustercontrol.core.JobEntry
import app.clustercontrol.core.Snapshot
import app.clustercontrol.ui.iconPath
import app.clustercontrol.sstp.SstpException
import com.jcraft.jsch.JSchException
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppLogicTest {
    private fun tmp() = kotlin.io.path.createTempDirectory("cc").toFile()

    private val snap = Snapshot(ActiveJob("1", "train-x", "RUNNING"), listOf(JobEntry("eval", "a.yaml", "t", "--resume")), null, true, 2, listOf("e"), "/l", "log", null)

    @Test
    fun snapshotRoundTrip() = assertEquals(snap, snapshotFromJson(snap.toJson()))

    @Test
    fun stateRoundTripKeepsTheSeedVersion() {
        val s = AppState(autoSuspended = true, suspendReason = "x", snapshot = snap, snapshotAt = 5, schedulesVersion = 2)
        assertEquals(s, stateFromJson(s.toJson()))
    }

    @Test
    fun classifyErrors() {
        assertEquals(ErrorKind.AUTH, classify(JSchException("Auth fail for methods 'publickey'")).kind)
        assertEquals(ErrorKind.AUTH, classify(HostKeyChangedException("h")).kind)
        assertEquals(ErrorKind.AUTH, classify(SstpException("MS-CHAPv2 authentication failed (E=691)")).kind)
        // a timeout or abort during the auth stage is a network problem: it must not suspend the automations
        assertEquals(ErrorKind.UNREACHABLE, classify(SstpException("timeout at stage 'auth'")).kind)
        assertEquals(ErrorKind.UNREACHABLE, classify(SstpException("server sent Call Abort (2) at stage 'auth'")).kind)
        assertEquals(ErrorKind.UNREACHABLE, classify(JSchException("timeout: socket is not established")).kind)
        assertEquals(ErrorKind.HELPER_MISSING, classify(HelperMissingException()).kind)
        assertEquals(ErrorKind.CONFIG, classify(ConfigMissingException()).kind)
    }

    @Test
    fun recorderTripsBreakerOnceAndKeepsHistory() = runBlocking {
        val stores = Stores(tmp())
        val notes = mutableListOf<Int>()
        var suspended = 0
        val r = Recorder(stores, { id, _, _ -> notes += id }, { suspended++ })
        val bad = r.record(Op.TICK, "auto", true) { throw JSchException("Auth fail") }
        r.record(Op.TICK, "auto", true) { throw JSchException("Auth fail") }
        assertFalse(bad.ok)
        assertTrue(stores.state.value.autoSuspended)
        assertEquals(1, suspended)
        assertEquals(2, notes.count { it == NoticeIds.FAILURE })
        assertEquals(2, stores.history.value.size)
        assertTrue(desiredWork(stores.schedules.value.map { it.copy(enabled = true) }, stores.state.value).isEmpty())
        assertTrue(r.record(Op.QUEUE, "manuale", false) { "ok" }.ok)
        assertEquals(listOf("QUEUE", "TICK", "TICK"), stores.history.value.map { it.action })
    }

    @Test
    fun historyIsCappedLiveAndClearable() {
        val s = Stores(tmp())
        repeat(Stores.HISTORY_MAX + 20) { s.addHistory(HistoryEntry(it.toLong(), "m", "TICK", true, "", 1)) }
        assertEquals(Stores.HISTORY_MAX, s.history.value.size)
        assertEquals((Stores.HISTORY_MAX + 19).toLong(), s.history.value.first().at)
        s.clearHistory()
        assertTrue(s.history.value.isEmpty())
    }

    @Test
    fun freshInstallSeedsTheDefaults() {
        val s = Stores(tmp())
        // the automatic start is on; the forced tick and the script sync are opt-in
        assertEquals(listOf("advance-15" to true, "tick-60" to false, "sync-24h" to false), s.schedules.value.map { it.id to it.enabled })
        assertEquals(listOf("advance-15"), desiredWork(s.schedules.value, AppState()).map { it.id })
    }

    @Test
    fun oldInstallsAreMigratedOnceAndThenLeftAlone() {
        val dir: File = tmp()
        // an old install: its schedules were all off and there is no version marker in the state
        File(dir, "schedules.json").writeText(
            JsonArray(Stores.SEED.map { it.copy(enabled = false).toJson() }).toString(),
        )
        val migrated = Stores(dir)
        assertTrue(migrated.schedules.value.first { it.id == "advance-15" }.enabled)
        assertEquals(Stores.SCHEDULES_VERSION, migrated.state.value.schedulesVersion)
        // the user switches it off: a restart must not turn it back on
        migrated.saveSchedules(migrated.schedules.value.map { if (it.id == "advance-15") it.copy(enabled = false) else it })
        assertFalse(Stores(dir).schedules.value.first { it.id == "advance-15" }.enabled)
    }

    @Test
    fun queueEditorOptimisticEditsAreWrittenOnce() {
        val a = JobEntry("train", "a.yaml", "a")
        val b = JobEntry("train", "b.yaml", "b")
        val c = JobEntry("eval", "c.yaml", "c")
        val e = QueueEditor(listOf(a, b, c))
        assertNull(e.takeWrite())
        e.edit { it.drop(1) }
        e.edit { listOf(it[1], it[0]) }
        assertEquals(listOf(c, b), e.items)
        e.adopt(listOf(a)) // a poll meanwhile must not undo the edits
        assertEquals(listOf(c, b), e.items)
        assertEquals(listOf(c, b), e.takeWrite())
        assertNull(e.takeWrite()) // one write at a time
        e.edit { it + a } // edited again while writing
        e.written(listOf(c, b))
        assertEquals(listOf(c, b, a), e.items)
        assertEquals(listOf(c, b, a), e.takeWrite())
        e.written(listOf(c, b, a))
        e.adopt(listOf(b))
        assertEquals(listOf(b), e.items)
    }

    @Test
    fun queueEditorUndoNeedsNoWriteAndFailureReverts() {
        val a = JobEntry("train", "a.yaml", "a")
        val b = JobEntry("train", "b.yaml", "b")
        val e = QueueEditor(listOf(a, b))
        e.edit { it.drop(1) }
        e.edit { listOf(a, b) } // undo
        assertNull(e.takeWrite())
        e.edit { listOf(b) }
        assertEquals(listOf(b), e.takeWrite())
        e.failed(listOf(a, b))
        assertEquals(listOf(a, b), e.items)
        assertNull(e.takeWrite())
    }

    @Test
    fun backoffThenGiveUp() {
        assertEquals(listOf<Long?>(2_000, 5_000, 10_000, null), (1..4).map { backoffMs(it) })
    }

    @Test
    fun themesResolveAndAreReadable() {
        val themes = app.clustercontrol.ui.Themes
        assertEquals(themes.DARK, themes.resolve("system", systemDark = true))
        assertEquals(themes.LIGHT, themes.resolve("system", systemDark = false))
        assertEquals(themes.LIGHT, themes.resolve("a-theme-that-no-longer-exists", systemDark = false))
        assertEquals(themes.SEPIA, themes.resolve("sepia", systemDark = true), "a chosen theme ignores the phone")
        assertEquals(themes.all.size, themes.all.map { it.id }.toSet().size, "ids are unique")
        for (p in themes.all) {
            // text must stand out from its background and the accent must carry the text written on it
            assertTrue(contrast(p.ink, p.bg) >= 7.0, "${p.id}: ink on background")
            assertTrue(contrast(p.ink, p.card) >= 7.0, "${p.id}: ink on card")
            assertTrue(contrast(p.onAccent, p.accent) >= 3.0, "${p.id}: text on accent")
            assertTrue(contrast(p.muted, p.card) >= 3.0, "${p.id}: secondary text")
        }
    }

    private fun lum(c: androidx.compose.ui.graphics.Color): Double {
        fun ch(v: Float) = v.toDouble().let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    private fun contrast(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Double {
        val (hi, lo) = lum(a).coerceAtLeast(lum(b)) to lum(a).coerceAtMost(lum(b))
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun iconIdsBecomeSvgPathsOrNothing() {
        val icons: (String?) -> String? = ::iconPath
        assertNotNull(icons("grpo"))
        assertEquals(icons("grpo"), icons(" GRPO "))
        assertEquals(icons("sft"), icons("supervised"))
        assertNull(icons("some-unknown-method"))
        assertNull(icons("🔥"), "an emoji is not an icon id")
        assertNull(icons(null))
        for (id in listOf(
            "train", "eval", "inference", "sft", "grpo", "ppo", "dpo", "rl", "pretrain", "finetune", "distill", "llm", "nn",
            "cnn", "rnn", "transformer", "ml", "tree", "diffusion", "vision", "speech", "gpu", "data",
            "state-done", "state-running", "state-failed",
        )) {
            val d = assertNotNull(icons(id), id)
            assertTrue(Regex("""M[MmLlHhVvCcSsQqTtAaZz0-9eE., \-]+""").matches(d), "$id: $d")
        }
    }

    @Test
    fun tagsAndProgress() {
        assertEquals("conll-2003-grpo", seriesTag("train-conll-2003-grpo"))
        assertEquals("x", seriesTag("eval-x"))
        val run = """{"run":{"phase":"grpo"},"progress":{"step":10,"total_steps":200,"eta_s":90},
            "metrics":{"latest":{"loss":0.9,"reward":0.1}}}"""
        val p = progressOf(snap.copy(runStatus = Json.parseToJsonElement(run).jsonObject))
        assertEquals(10, p.step)
        assertEquals(200, p.total)
        assertEquals(90L, p.etaSeconds)
        assertEquals(0.9, p.loss)
        assertEquals("grpo", p.phase)
        // no runstatus file (and a log full of step= lines): nothing is parsed from the log
        val none = progressOf(snap.copy(logTail = "  step=10 loss=0.9 reward=0.1\n"))
        assertNull(none.step)
        assertNull(none.etaSeconds)
    }
}
