package com.example.swipecull

import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

private val TAG_CHIP_HEIGHT = 34.dp
private val TAG_ROW_GAP = 6.dp

/**
 * Chips under the video/photo: tap to put a tag on (or take it off) the current file.
 * The chips wrap onto new lines; after two lines the block scrolls vertically.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagRow(state: UiState, vm: SwipeViewModel, modifier: Modifier = Modifier) {
    val item = state.current ?: return
    val current = state.tagAssign[item.uri.toString()] ?: emptySet()
    var showNew by remember { mutableStateOf(false) }

    FlowRow(
        modifier
            .heightIn(max = TAG_CHIP_HEIGHT * 2 + TAG_ROW_GAP)
            .verticalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(TAG_ROW_GAP),
    ) {
        state.tagNames.forEach { name ->
            TagChip(label = name, selected = name in current, onClick = { vm.toggleTag(name) })
        }
        TagChip(label = "+ Tag", selected = false, accent = true, onClick = { showNew = true })
    }

    if (showNew) {
        TagNameDialog(
            title = "New tag",
            initial = "",
            confirmLabel = "Add to this file",
            onConfirm = { vm.addTagToCurrent(it); showNew = false },
            onDismiss = { showNew = false },
        )
    }
}

/** A fixed-height pill, so exactly two lines of them fit the row's maximum height. */
@Composable
private fun TagChip(label: String, selected: Boolean, accent: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    val textColor = when {
        selected -> scheme.onPrimaryContainer
        accent -> scheme.primary
        else -> scheme.onSurface
    }
    Box(
        Modifier
            .height(TAG_CHIP_HEIGHT)
            .clip(shape)
            .background(if (selected) scheme.primaryContainer else Color.Transparent)
            .border(1.dp, if (selected) scheme.primary else scheme.outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (selected) "✓ $label" else label,
            color = textColor,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

@Composable
private fun TagNameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(30) },
                singleLine = true,
                label = { Text("Tag name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Lists every tag with its file count; pick a tag to bulk-move its files to a folder. */
@Composable
fun TagsScreen(state: UiState, vm: SwipeViewModel) {
    var pendingTag by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }

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
                "Review swipes through a tag's files again (same delete and keep folders). " +
                "Move sends every file with that tag to a folder in one go.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.tagAssign.isNotEmpty()) {
            Button(onClick = { vm.startTagReview(null) }, modifier = Modifier.fillMaxWidth()) {
                Text("Review all tagged files (${state.tagAssign.size})")
            }
        }
        if (state.bulkBusy) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (state.tagNames.isEmpty()) {
            Text(
                "No tags yet. While swiping, tap \"+ Tag\" under the photo or video to make one.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (state.tagNames.size > 1) {
            Text(
                "Use ▲ ▼ to reorder. The chips under each photo follow this order.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.tagNames.forEachIndexed { index, name ->
            val n = counts[name] ?: 0
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "$n file${if (n == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { vm.moveTag(name, -1) }, enabled = index > 0) { Text("▲") }
                        TextButton(onClick = { vm.moveTag(name, 1) }, enabled = index < state.tagNames.lastIndex) { Text("▼") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { vm.startTagReview(name) },
                            enabled = n > 0 && !state.bulkBusy,
                        ) { Text("Review") }
                        OutlinedButton(
                            onClick = { pendingTag = name; picker.launch(startHint) },
                            enabled = n > 0 && !state.bulkBusy,
                        ) { Text("Move to folder…") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { renaming = name }) { Text("Rename") }
                        TextButton(onClick = { confirmDelete = name }, enabled = !state.bulkBusy) { Text("Delete") }
                    }
                }
            }
        }
    }

    renaming?.let { tag ->
        TagNameDialog(
            title = "Rename tag",
            initial = tag,
            confirmLabel = "Rename",
            onConfirm = { vm.renameTag(tag, it); renaming = null },
            onDismiss = { renaming = null },
        )
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
