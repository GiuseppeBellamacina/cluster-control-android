package app.clustercontrol

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import app.clustercontrol.core.JobEntry
import app.clustercontrol.core.JobNames
import app.clustercontrol.ui.AppTheme
import app.clustercontrol.ui.QueueScreen
import app.clustercontrol.ui.Themes
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** The queue screen as the owner uses it: swipe a job away, then press Annulla. Never touches a cluster. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class QueueUndoTest {
    // the Compose test activity is registered here, not through ui-test-manifest: that one would also land in the shipped apk
    @get:Rule(order = 0)
    val registerActivity: TestRule = object : ExternalResource() {
        override fun before() {
            val app = RuntimeEnvironment.getApplication()
            Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app.packageName, ComponentActivity::class.java.name))
        }
    }

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val a = JobEntry("train", "experiments/configs/aslg-pc12/qwen25-05b/grpo/few-shot.yaml", "aslg-pc12-grpo-few-shot")
    private val b = JobEntry("eval", "experiments/configs/aslg-pc12/qwen25-05b/grpo/few-shot.yaml", "aslg-pc12-grpo-few-shot")
    private val c = JobEntry("train", "experiments/configs/conll-2003/qwen25-05b/sft/zero-shot.yaml", "conll-2003-sft-zero-shot")

    private fun awaitUndoButton(calls: List<String>) {
        try {
            rule.waitUntil(timeoutMillis = 5_000) { rule.onAllNodesWithText("Annulla").fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            throw AssertionError("no Annulla button; actions called: $calls", e)
        }
    }

    @Test
    fun swipingAJobAwayAndUndoingDoesNotCrash() {
        val queue = mutableListOf(a, b, c)
        val calls = mutableListOf<String>()
        var uiState by mutableStateOf(UiState(queue = queue.toList()))
        val actions = Proxy.newProxyInstance(Actions::class.java.classLoader, arrayOf(Actions::class.java)) { self, m, args ->
            calls += m.name
            when (m.name) {
                "equals" -> self === args[0]
                "hashCode" -> 1
                "toString" -> "FakeActions"
                "queueRemove" -> queue.removeAt(args[0] as Int).also { uiState = uiState.copy(queue = queue.toList()) }
                "queueUndo" -> {
                    queue.add(args[0] as Int, args[1] as JobEntry)
                    uiState = uiState.copy(queue = queue.toList())
                    null
                }
                else -> null
            }
        } as Actions
        val snackbar = SnackbarHostState()

        rule.setContent {
            AppTheme(Themes.LIGHT) {
                Box(Modifier.fillMaxSize()) {
                    QueueScreen(uiState, actions, snackbar, Modifier)
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        rule.waitForIdle()

        val victim = c
        rule.onNodeWithText(JobNames.of(victim).title).assertExists()
        rule.onNodeWithText(JobNames.of(victim).title).performTouchInput { swipe(start = center, end = Offset(center.x - 260f, center.y), durationMillis = 300) }
        rule.waitForIdle()
        awaitUndoButton(calls)
        rule.onNodeWithText("Annulla").performClick()
        rule.waitForIdle()
        // let any delayed effect of the restored row run
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()

        assertEquals(listOf(a, b, c), queue)
        rule.onNodeWithText(JobNames.of(victim).title).assertExists()
        assertEquals(true, "queueRemove" in calls && "queueUndo" in calls)
    }

    @Test
    fun swipingTheFirstJobAndUndoingKeepsTheOrder() {
        val queue = mutableListOf(a, c)
        val calls = mutableListOf<String>()
        var uiState by mutableStateOf(UiState(queue = queue.toList()))
        val actions = Proxy.newProxyInstance(Actions::class.java.classLoader, arrayOf(Actions::class.java)) { self, m, args ->
            calls += m.name
            when (m.name) {
                "equals" -> self === args[0]
                "hashCode" -> 1
                "toString" -> "FakeActions"
                "queueRemove" -> queue.removeAt(args[0] as Int).also { uiState = uiState.copy(queue = queue.toList()) }
                "queueUndo" -> {
                    queue.add(args[0] as Int, args[1] as JobEntry)
                    uiState = uiState.copy(queue = queue.toList())
                    null
                }
                else -> null
            }
        } as Actions
        val snackbar = SnackbarHostState()
        rule.setContent {
            AppTheme(Themes.LIGHT) {
                Box(Modifier.fillMaxSize()) {
                    QueueScreen(uiState, actions, snackbar, Modifier)
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        rule.waitForIdle()
        // remove and restore the same row twice in a row: the second round starts from the restored row
        repeat(2) {
            rule.onNodeWithText(JobNames.of(a).title).performTouchInput { swipe(start = center, end = Offset(center.x - 260f, center.y), durationMillis = 300) }
            rule.waitForIdle()
            awaitUndoButton(calls)
            rule.onNodeWithText("Annulla").performClick()
            rule.waitForIdle()
            rule.mainClock.advanceTimeBy(2_000)
            assertEquals(listOf(a, c), queue)
            rule.onNodeWithText(JobNames.of(a).title).assertExists()
        }
    }
}
