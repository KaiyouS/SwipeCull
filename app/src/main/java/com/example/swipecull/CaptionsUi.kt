package com.example.swipecull

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

/** The subtitle line over the bottom of the video (and a small status pill while captioning). */
@Composable
fun BoxScope.CaptionOverlay(state: CaptionState, uri: String, player: ExoPlayer?) {
    var pos by remember(uri) { mutableLongStateOf(0L) }
    LaunchedEffect(player, uri) {
        if (player == null) return@LaunchedEffect
        while (true) {
            pos = player.currentPosition
            delay(120)
        }
    }

    if (state.workingOn == uri && state.status != null) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp, start = 16.dp, end = 16.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) { Text(state.status, color = Color.White, style = MaterialTheme.typography.labelMedium) }
    }

    if (state.show && state.cuesFor == uri) {
        val cue = state.cues.lastOrNull { pos >= it.startMs && pos < it.endMs + 150 }
        if (cue != null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 20.dp, end = 20.dp, bottom = 68.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    cue.text,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
fun CaptionDialog(file: MediaFile, cc: CaptionController, state: CaptionState, onDismiss: () -> Unit) {
    val uri = file.uri.toString()
    val busy = state.workingOn != null
    val hasCaptions = state.cuesFor == uri && state.cues.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Captions") },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (!state.modelReady) {
                    Text(
                        "Captions run on your phone with an offline speech model " +
                            "(${CaptionController.MODEL_LABEL}, downloaded once).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.downloading) {
                        LinearProgressIndicator(progress = { state.downloadProgress }, modifier = Modifier.fillMaxWidth())
                        Text("${(state.downloadProgress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                        OutlinedButton(onClick = { cc.cancelDownload() }) { Text("Cancel download") }
                    } else {
                        Button(onClick = { cc.downloadModel() }) { Text("Download model") }
                    }
                }

                LanguagePicker(
                    label = "Spoken language",
                    selected = state.sourceLang,
                    extraOption = "auto" to "Detect automatically",
                    enabled = !busy,
                    onSelect = { cc.setSource(it) },
                )
                LanguagePicker(
                    label = "Show captions in",
                    selected = state.targetLang,
                    extraOption = "original" to "Same as spoken",
                    enabled = !busy,
                    onSelect = { cc.setTarget(it) },
                )
                Text(
                    "Translation uses a small language pack that downloads the first time you use a language.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (hasCaptions) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Show captions", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = state.show, onCheckedChange = { cc.setShow(it) })
                    }
                    state.detectedLang?.let {
                        Text(
                            "Spoken language: ${languageName(it)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (busy && state.status != null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(state.status, style = MaterialTheme.typography.bodyMedium)
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                if (state.modelReady && !busy) {
                    TextButton(onClick = { cc.deleteModel() }) { Text("Delete speech model") }
                }
            }
        },
        confirmButton = {
            if (busy) {
                TextButton(onClick = { cc.cancel() }) { Text("Stop") }
            } else {
                TextButton(
                    onClick = { cc.generate(file); onDismiss() },
                    enabled = state.modelReady,
                ) { Text(if (hasCaptions) "Update captions" else "Generate captions") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun LanguagePicker(
    label: String,
    selected: String,
    extraOption: Pair<String, String>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val name = if (selected == extraOption.first) extraOption.second else languageName(selected)
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(name, Modifier.weight(1f), textAlign = TextAlign.Start)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                DropdownMenuItem(text = { Text(extraOption.second) }, onClick = { open = false; onSelect(extraOption.first) })
                CAPTION_LANGUAGES.forEach { (code, lang) ->
                    DropdownMenuItem(text = { Text(lang) }, onClick = { open = false; onSelect(code) })
                }
            }
        }
    }
}
