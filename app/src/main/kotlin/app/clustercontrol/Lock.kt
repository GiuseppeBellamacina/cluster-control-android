package app.clustercontrol

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** When the app asks for the owner again: at launch, and when it comes back after being away longer than [graceMs]. */
class LockPolicy(
    var enabled: Boolean,
    private val graceMs: Long = 30_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var leftAt: Long? = null
    private val _locked = MutableStateFlow(enabled)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    fun onLeave() {
        leftAt = clock()
    }

    fun onReturn() {
        val away = leftAt?.let { clock() - it }
        leftAt = null
        if (enabled && away != null && away >= graceMs) _locked.value = true
    }

    fun unlock() {
        _locked.value = false
    }

    fun enable(on: Boolean) {
        enabled = on
        if (!on) _locked.value = false
    }
}

/** Fingerprint / face, or the screen PIN / pattern where the system allows combining them (Android 11+). */
object LockAuth {
    val AUTHENTICATORS = if (Build.VERSION.SDK_INT >= 30) {
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    } else {
        BiometricManager.Authenticators.BIOMETRIC_WEAK
    }

    /** false when the phone has no screen lock or biometric: the lock cannot work, so it must not shut the owner out. */
    fun available(ctx: Context) = BiometricManager.from(ctx).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
}
