package app.clustercontrol

import app.clustercontrol.core.ActiveJob
import app.clustercontrol.core.HelperMissingException
import app.clustercontrol.core.HostKeyChangedException
import app.clustercontrol.core.JobEntry
import app.clustercontrol.core.Snapshot
import app.clustercontrol.sstp.SstpException
import com.jcraft.jsch.JSchException
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertEquals(ErrorKind.AUTH, classify(SstpException("MS-CHAPv2 failure E=691")).kind)
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
        assertEquals(listOf("tick-60" to true, "sync-24h" to false), s.schedules.value.map { it.id to it.enabled })
        assertEquals(listOf("tick-60"), desiredWork(s.schedules.value, AppState()).map { it.id })
    }

    @Test
    fun oldInstallsAreMigratedOnceAndThenLeftAlone() {
        val dir: File = tmp()
        // v1 stored: both schedules off, no version marker in the state
        File(dir, "schedules.json").writeText(
            JsonArray(Stores.SEED.map { it.copy(enabled = false).toJson() }).toString(),
        )
        val migrated = Stores(dir)
        assertTrue(migrated.schedules.value.first { it.id == "tick-60" }.enabled)
        assertEquals(Stores.SCHEDULES_VERSION, migrated.state.value.schedulesVersion)
        // the user switches the tick off: a restart must not turn it back on
        migrated.saveSchedules(migrated.schedules.value.map { if (it.id == "tick-60") it.copy(enabled = false) else it })
        assertFalse(Stores(dir).schedules.value.first { it.id == "tick-60" }.enabled)
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
    fun tagsAndProgress() {
        assertEquals("conll-2003-grpo", seriesTag("train-conll-2003-grpo"))
        assertEquals("x", seriesTag("eval-x"))
        val p = progressOf(snap.copy(logTail = "[stage 1] steps=200\n  step=10 loss=0.9 reward=0.1\n"))
        assertEquals(10, p.step)
        assertEquals(200, p.total)
    }
}
