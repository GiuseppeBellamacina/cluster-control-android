package app.clustercontrol

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LockPolicyTest {
    private var now = 0L
    private fun policy(on: Boolean = true) = LockPolicy(on, 30_000) { now }

    @Test
    fun lockedAtLaunchOnlyWhenEnabled() {
        assertTrue(policy().locked.value)
        assertFalse(policy(false).locked.value)
    }

    @Test
    fun aShortAbsenceStaysUnlocked() {
        val p = policy().apply { unlock() }
        p.onLeave()
        now += 29_000
        p.onReturn()
        assertFalse(p.locked.value)
    }

    @Test
    fun aLongAbsenceLocksAgain() {
        val p = policy().apply { unlock() }
        p.onLeave()
        now += 30_000
        p.onReturn()
        assertTrue(p.locked.value)
    }

    @Test
    fun disabledNeverLocksAndDisablingUnlocks() {
        val p = policy(false)
        p.onLeave()
        now += 600_000
        p.onReturn()
        assertFalse(p.locked.value)
        val q = policy()
        q.enable(false)
        assertFalse(q.locked.value)
    }

    @Test
    fun theFirstReturnAfterLaunchChangesNothing() {
        val p = policy().apply { unlock() }
        p.onReturn()
        assertFalse(p.locked.value)
    }
}
