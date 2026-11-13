package dev.pulsarconnect.app.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pulsarconnect.app.security.BiometricStatus
import dev.pulsarconnect.app.security.checkBiometricStatus
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Hides [content] until the rider passes the device biometric prompt. This is UI gating only:
 * the local database is still not separately encrypted.
 */
@Composable
internal fun AppLockGate(
    enabled: Boolean,
    resetKey: Any?,
    onDisable: () -> Unit,
    content: @Composable () -> Unit,
) {
    var unlocked by rememberSaveable(resetKey) { mutableStateOf(!enabled) }
    var wasEnabled by rememberSaveable(resetKey) { mutableStateOf(enabled) }
    LaunchedEffect(enabled) {
        when {
            enabled && !wasEnabled -> unlocked = false
            !enabled -> unlocked = true
        }
        wasEnabled = enabled
    }
    if (!enabled) {
        content()
        return
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, enabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) unlocked = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (unlocked) {
        content()
        return
    }

    val status = checkBiometricStatus(context)
    var promptRequest by rememberSaveable(resetKey) { mutableIntStateOf(0) }
    var feedback by rememberSaveable(resetKey) { mutableStateOf<String?>(null) }

    LaunchedEffect(enabled, status, promptRequest) {
        if (!enabled || status != BiometricStatus.AVAILABLE) return@LaunchedEffect
        val activity = context.findFragmentActivity() ?: return@LaunchedEffect
        when (val result = promptForBiometric(activity)) {
            is BiometricUnlockResult.Unlocked -> {
                feedback = null
                unlocked = true
            }
            is BiometricUnlockResult.Failed -> feedback = result.message
        }
    }

    LockedScreen(
        status = status,
        feedback = feedback,
        onUnlock = {
            feedback = null
            promptRequest++
        },
        onDisable = onDisable,
    )
}

@Composable
private fun LockedScreen(
    status: BiometricStatus,
    feedback: String?,
    onUnlock: () -> Unit,
    onDisable: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.Lock,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "App locked",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = statusMessage(status),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        feedback?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(20.dp))
        if (status == BiometricStatus.AVAILABLE) {
            PillButton(label = "Unlock", onClick = onUnlock)
        } else {
            // Biometrics may be removed after the lock was enabled. Without this escape hatch
            // the rider would be permanently locked out of their own local records.
            PillButton(label = "Turn off app lock", onClick = onDisable, style = PillStyle.Outlined)
        }
    }
}

private fun statusMessage(status: BiometricStatus): String = when (status) {
    BiometricStatus.AVAILABLE -> "Confirm your fingerprint or face to view your bike records."
    BiometricStatus.NOT_ENROLLED ->
        "Add a fingerprint or face unlock in system settings, then reopen the app."
    BiometricStatus.NO_HARDWARE -> "This phone has no biometric sensor."
    BiometricStatus.TEMPORARILY_UNAVAILABLE -> "Biometrics are temporarily unavailable. Try again later."
    BiometricStatus.UNKNOWN -> "Biometric unlock is not available on this phone."
}

private sealed interface BiometricUnlockResult {
    data object Unlocked : BiometricUnlockResult
    data class Failed(val message: String) : BiometricUnlockResult
}

private suspend fun promptForBiometric(activity: FragmentActivity): BiometricUnlockResult =
    suspendCancellableCoroutine { continuation ->
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) continuation.resume(BiometricUnlockResult.Unlocked)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (continuation.isActive) {
                        continuation.resume(BiometricUnlockResult.Failed(authenticationMessage(errorCode)))
                    }
                }

                override fun onAuthenticationFailed() {
                    // The system prompt stays open so the rider can try again immediately.
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Pulsar Connect")
            .setSubtitle("Confirm your identity to view your bike records.")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .setNegativeButtonText("Cancel")
            .build()
        continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        prompt.authenticate(info)
    }

private fun authenticationMessage(errorCode: Int): String = when (errorCode) {
    BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
    -> "Unlock cancelled."
    BiometricPrompt.ERROR_LOCKOUT,
    BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
    -> "Too many attempts. Biometric unlock is temporarily locked."
    BiometricPrompt.ERROR_NO_BIOMETRICS -> "No biometrics are enrolled on this phone."
    BiometricPrompt.ERROR_HW_NOT_PRESENT,
    BiometricPrompt.ERROR_HW_UNAVAILABLE,
    -> "Biometric hardware is unavailable."
    else -> "Biometric unlock failed. Try again."
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
