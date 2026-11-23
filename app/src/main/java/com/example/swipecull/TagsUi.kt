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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

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
        TagChip(label = "Tag", selected = false, accent = true, showAddIcon = true, onClick = { showNew = true })
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
private fun TagChip(
    label: String,
    selected: Boolean,
    accent: Boolean = false,
    showAddIcon: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    val textColor = when {
        selected -> scheme.onPrimaryContainer
        accent -> scheme.primary
        else -> scheme.onSurface
    }
    Row(
        Modifier
            .height(TAG_CHIP_HEIGHT)
            .widthIn(max = 220.dp)
            .clip(shape)
            .background(if (selected) scheme.primaryContainer else Color.Transparent)
            .border(1.dp, if (selected) scheme.primary else scheme.outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showAddIcon) {
            Icon(AppIcons.Add, contentDescription = null, tint = textColor, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            label,
            color = textColor,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { focus.requestFocus() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(30) },
                singleLine = true,
                label = { Text("Tag name") },
                modifier = Modifier.focusRequester(focus),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onConfirm(text) }),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Lists every tag with its file count: review, bulk-move, rename, reorder or delete. */
@Composable
fun TagsScreen(state: UiState, vm: SwipeViewModel) {
    var pendingTag by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }

    val startHint = remember {
        DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:DCIM")
    }
    val appContext = androidx.compose.ui.platform.LocalContext.current
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) vm.startFavoritesReview()
        else vm.notify("Allow photos and videos access to read your favorites")
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

    Column(Modifier.fillMaxSize()) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 20.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.closeTags() }) {
                Icon(AppIcons.ArrowBack, contentDescription = "Back")
            }
            Text("Tags", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        if (state.bulkBusy) LinearProgressIndicator(Modifier.fillMaxWidth())

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Tags are private labels. Your files aren't changed until you move them. " +
                    "Review swipes through a tag's files again with your usual delete and keep " +
                    "folders; Move sends every file with a tag to one folder.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (Favorites.supported) {
                OutlinedButton(
                    onClick = {
                        if (Favorites.hasMediaAccess(appContext)) vm.startFavoritesReview()
                        else permLauncher.launch(Favorites.mediaPermissions)
                    },
                    enabled = !state.bulkBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(AppIcons.Star, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Review all favorites")
                }
            }

            if (state.tagAssign.isNotEmpty()) {
                Button(
                    onClick = { vm.startTagReview(null) },
                    enabled = !state.bulkBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Review all tagged files (${state.tagAssign.size})") }
            }

            if (state.tagNames.isEmpty()) {
                Text(
                    "No tags yet. While swiping, tap Tag under the photo or video to make one.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else if (state.tagNames.size > 1) {
                Text(
                    "Use the arrows to reorder. The chips under each photo follow this order.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.tagNames.forEachIndexed { index, name ->
                TagCard(
                    name = name,
                    count = counts[name] ?: 0,
                    canMoveUp = index > 0,
                    canMoveDown = index < state.tagNames.lastIndex,
                    busy = state.bulkBusy,
                    onMoveUp = { vm.moveTag(name, -1) },
                    onMoveDown = { vm.moveTag(name, 1) },
                    onReview = { vm.startTagReview(name) },
                    onMoveFiles = { pendingTag = name; picker.launch(startHint) },
                    onRename = { renaming = name },
                    onDelete = { confirmDelete = name },
                )
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

@Composable
private fun TagCard(
    name: String,
    count: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    busy: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onReview: () -> Unit,
    onMoveFiles: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Label, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "$count file${if (count == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(AppIcons.ArrowUpward, contentDescription = "Move $name up")
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(AppIcons.ArrowDownward, contentDescription = "Move $name down")
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(AppIcons.MoreVert, contentDescription = "More options for $name")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(AppIcons.Edit, contentDescription = null) },
                            onClick = { menuOpen = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete tag", color = DeleteRed) },
                            leadingIcon = { Icon(AppIcons.Delete, contentDescription = null, tint = DeleteRed) },
                            enabled = !busy,
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
            Row(
                Modifier.padding(start = 0.dp, end = 12.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onReview, enabled = count > 0 && !busy) {
                    Icon(AppIcons.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Review")
                }
                OutlinedButton(onClick = onMoveFiles, enabled = count > 0 && !busy) {
                    Icon(AppIcons.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Move to folder")
                }
            }
        }
    }
}
