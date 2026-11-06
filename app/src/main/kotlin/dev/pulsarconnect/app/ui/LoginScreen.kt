package dev.pulsarconnect.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun LoginScreen(
    error: String?,
    busy: Boolean,
    onSignIn: (String, String) -> Unit,
) {
    var vin by rememberSaveable { mutableStateOf("") }
    var accessCode by rememberSaveable { mutableStateOf("") }
    var revealCode by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "PULSAR CONNECT",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        Text(
            text = "Your bike.\nYour data.",
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            lineHeight = 48.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Text(
            text = "Log in to keep a manual trip log and service reminders on this phone. Saved " +
                "records are available offline; Bluetooth is only used for the bike link.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = vin,
            onValueChange = { vin = it.uppercase() },
            label = { Text("Vehicle identification number") },
            placeholder = { Text("17 characters") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
            ),
        )

        OutlinedTextField(
            value = accessCode,
            onValueChange = { accessCode = it.trim() },
            label = { Text("Access code") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            visualTransformation = if (revealCode) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (!busy && vin.isNotBlank() && accessCode.isNotBlank()) {
                        onSignIn(vin, accessCode)
                    }
                },
            ),
            trailingIcon = {
                TextButton(onClick = { revealCode = !revealCode }) {
                    Text(if (revealCode) "Hide" else "Show")
                }
            },
        )

        if (error != null) {
            AlertCard(title = "Could not sign in", message = error)
        }

        Button(
            onClick = { onSignIn(vin, accessCode) },
            enabled = !busy && vin.isNotBlank() && accessCode.isNotBlank(),
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Log in", style = MaterialTheme.typography.titleMedium)
            }
        }

        Text(
            text = "Stored encrypted on this device and sent only to Bajaj to sign in.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
