package app.clustercontrol

import app.clustercontrol.core.ActiveJob
import app.clustercontrol.core.HelperMissingException
import app.clustercontrol.core.HostKeyChangedException
import app.clustercontrol.core.JobEntry
import app.clustercontrol.core.ScheduleAction
import app.clustercontrol.core.Snapshot
import app.clustercontrol.sstp.SstpException
import com.jcraft.jsch.JSchException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLogicTest {
    private fun tmp() = kotlin.io.path.createTempDirectory("cc").toFile()

    private val snap = Snapshot(ActiveJob("1", "train-x", "RUNNING"), listOf(JobEntry("eval", "a.yaml", "t", "--resume")), null, true, 2, listOf("e"), "/l", "log", null)

    @Test
    fun snapshotRoundTrip() = assertEquals(snap, snapshotFromJson(snap.toJson()))

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
    fun recorderTripsBreakerOnceAndKeepsHistory() {
        val stores = Stores(tmp())
        val notes = mutableListOf<Int>()
        var suspended = 0
        val r = Recorder(stores, { id, _, _ -> notes += id }, { suspended++ })
        val bad = r.record(ScheduleAction.TICK, "auto", true) { throw JSchException("Auth fail") }
        r.record(ScheduleAction.TICK, "auto", true) { throw JSchException("Auth fail") }
        assertFalse(bad.ok)
        assertTrue(stores.state().autoSuspended)
        assertEquals(1, suspended)
        assertEquals(2, notes.count { it == NoticeIds.FAILURE })
        assertEquals(2, stores.history().size)
        assertTrue(desiredWork(stores.schedules().map { it.copy(enabled = true) }, stores.state()).isEmpty())
        assertTrue(r.record(ScheduleAction.STATUS, "m", false) { "ok" }.ok)
    }

    @Test
    fun historyCappedAndSchedulesSeeded() {
        val s = Stores(tmp())
        repeat(120) { s.addHistory(HistoryEntry(it.toLong(), "m", "TICK", true, "", 1)) }
        assertEquals(Stores.HISTORY_MAX, s.history().size)
        assertEquals(119, s.history().first().at)
        assertEquals(2, s.schedules().size)
        assertTrue(s.schedules().none { it.enabled })
        assertEquals(listOf("tick-60"), desiredWork(s.schedules().map { if (it.id == "tick-60") it.copy(enabled = true) else it }, AppState()).map { it.id })
    }

    @Test
    fun metricsAndProgress() {
        assertEquals(listOf("rouge_l_mean" to "0.5000", "pass_at_1" to "0.2500"), resultMetrics("""{"a":{"pass_at_1":0.25},"rouge_l_mean":0.5}"""))
        val p = progressOf(snap.copy(logTail = "[stage 1] steps=200\n  step=10 loss=0.9 reward=0.1\n"))
        assertEquals(10, p.step)
        assertEquals(0.05f, p.fraction)
        assertEquals("5 min fa", ageText(10 * 60_000, 5 * 60_000))
    }
}
