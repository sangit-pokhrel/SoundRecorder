package com.vocalis.recorder.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.vocalis.recorder.data.RecordingNames
import com.vocalis.recorder.ui.theme.Vc

/**
 * Text field where "Vocalis " is a fixed, non-editable prefix and only the rest can be typed.
 * [value] is the editable part only.
 */
@Composable
fun VocalisNameField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    error: String?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(MAX_NAME_LENGTH)) },
        prefix = { Text("${RecordingNames.PREFIX} ", color = Vc.Red, fontWeight = FontWeight.SemiBold) },
        placeholder = { Text(placeholder, color = Vc.TextMuted) },
        singleLine = true,
        isError = error != null,
        supportingText = { Text(error ?: "Saved as \"${RecordingNames.PREFIX} ${value.ifBlank { placeholder }.trim()}\"") },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Vc.Red, cursorColor = Vc.Red),
        modifier = modifier.fillMaxWidth().focusRequester(focus),
    )
}

/**
 * Shown right after a recording is saved. It can't be dismissed by tapping outside or pressing
 * back: the take has to be named (or deliberately discarded) before a new recording can start.
 */
@Composable
fun NameNewRecordingDialog(
    defaultName: String,
    error: String?,
    onTyping: () -> Unit,
    onSave: (String) -> Unit,
    onDiscard: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val placeholder = RecordingNames.editablePart(defaultName)
    val locked = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = {},
            properties = locked,
            containerColor = Vc.SurfaceHigh,
            title = { Text("Discard this recording?") },
            text = { Text("The take and its original will be permanently deleted.") },
            confirmButton = { TextButton(onClick = onDiscard) { Text("Discard", color = Vc.Red) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep it", color = Vc.TextSecondary) } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = {},
        properties = locked,
        containerColor = Vc.SurfaceHigh,
        title = { Text("Name your recording") },
        text = {
            Column {
                Text("Give it a name you'll recognise later.", color = Vc.TextSecondary)
                Spacer(Modifier.height(16.dp))
                VocalisNameField(
                    value = text,
                    onValueChange = { text = it; onTyping() },
                    placeholder = placeholder,
                    error = error,
                    onDone = { onSave(text) },
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(text) }, colors = ButtonDefaults.buttonColors(containerColor = Vc.Red)) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { confirmDiscard = true }) { Text("Discard", color = Vc.TextSecondary) } },
    )
}

private const val MAX_NAME_LENGTH = 60
