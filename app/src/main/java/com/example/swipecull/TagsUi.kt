package com.example.swipecull

import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Chips under the video/photo: tap to put a tag on (or take it off) the current file. */
@Composable
fun TagRow(state: UiState, vm: SwipeViewModel, modifier: Modifier = Modifier) {
    val item = state.current ?: return
    val current = state.tagAssign[item.uri.toString()] ?: emptySet()
    var showNew by remember { mutableStateOf(false) }

    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.tagNames.forEach { name ->
            FilterChip(
                selected = name in current,
                onClick = { vm.toggleTag(name) },
                label = { Text(name) },
            )
        }
        AssistChip(onClick = { showNew = true }, label = { Text("+ Tag") })
    }

    if (showNew) {
        NewTagDialog(
            onCreate = { vm.addTagToCurrent(it); showNew = false },
            onDismiss = { showNew = false },
        )
    }
}

@Composable
private fun NewTagDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New tag") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(30) },
                singleLine = true,
                label = { Text("Tag name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(text) }, enabled = text.isNotBlank()) { Text("Add to this file") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Lists every tag with its file count; pick a tag to bulk-move its files to a folder. */
@Composable
fun TagsScreen(state: UiState, vm: SwipeViewModel) {
    var pendingTag by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    val startHint = remember {
        DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:DCIM")
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val tag = pendingTag
        pendingTag = null
        if (uri != null && tag != null) vm.moveTagged(tag, uri)
    }

    val counts = remember(state.tagAssign) {
        val m = HashMap<String, Int>()
        for (set in state.tagAssign.values) for (t in set) m[t] = (m[t] ?: 0) + 1
        m
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TextButton(onClick = { vm.closeTags() }) { Text("‹ Back") }
        Text("Tagged files", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Tags are labels kept inside SwipeCull; your files aren't changed until you move them. " +
                "Pick a tag, choose a folder, and every file with that tag is moved there.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.bulkBusy) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (state.tagNames.isEmpty()) {
            Text(
                "No tags yet. While swiping, tap \"+ Tag\" under the photo or video to make one.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        state.tagNames.forEach { name ->
            val n = counts[name] ?: 0
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "$n file${if (n == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { pendingTag = name; picker.launch(startHint) },
                            enabled = n > 0 && !state.bulkBusy,
                        ) { Text("Move to folder…") }
                        TextButton(onClick = { confirmDelete = name }, enabled = !state.bulkBusy) { Text("Delete tag") }
                    }
                }
            }
        }
    }

    confirmDelete?.let { tag ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete tag \"$tag\"?") },
            text = { Text("The tag is removed from all files. The files themselves aren't touched.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteTag(tag); confirmDelete = null }) { Text("Delete", color = DeleteRed) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}
