package com.example.swipecull

import android.app.Activity
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.produceState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import android.media.MediaMetadataRetriever
import android.media.ExifInterface
import android.graphics.BitmapFactory
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val FavoriteGold = Color(0xFFFFC107)

// ---------------------------------------------------------------------------- root

@Composable
fun App(vm: SwipeViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory())
                else add(GifDecoder.Factory())
            }
            .build()
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.events.collect {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(it)
        }
    }

    // The system "add to favorites?" confirmation.
    val favLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.favoriteResult(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(Unit) {
        vm.favRequests.collect { favLauncher.launch(IntentSenderRequest.Builder(it).build()) }
    }

    // The system "move these to the bin?" confirmation.
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.trashResult(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(Unit) {
        vm.trashRequests.collect { trashLauncher.launch(IntentSenderRequest.Builder(it).build()) }
    }

    val startHint = remember {
        DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:DCIM")
    }
    val pickSource = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setSource(uri)
    }
    val pickDest = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setDest(uri)
    }
    val pickKeep = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setKeep(uri)
    }

    BackHandler(enabled = state.showTags) { vm.closeTags() }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.showTags -> TagsScreen(state, vm)
                !state.sessionActive -> SetupScreen(
                    state = state,
                    vm = vm,
                    onPickSource = { pickSource.launch(startHint) },
                    onPickDest = { pickDest.launch(startHint) },
                    onPickKeep = { pickKeep.launch(startHint) },
                )
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.current == null -> DoneScreen(state, vm)
                else -> ReviewScreen(state, vm, imageLoader)
            }
        }
    }

    if (state.showBinDialog) {
        BinQueueDialog(
            count = state.binCount,
            onSend = vm::sendBinToTrash,
            onForget = vm::forgetBinQueue,
            onDismiss = vm::closeBinDialog,
        )
    }
}

@Composable
private fun BinQueueDialog(count: Int, onSend: () -> Unit, onForget: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.DeleteForever, contentDescription = null, tint = DeleteRed) },
        title = { Text("$count file${if (count == 1) "" else "s"} waiting for the bin") },
        text = {
            Text(
                "Android will ask you to confirm once, then move them to the recycle bin " +
                    "(usually kept for 30 days before they're deleted for good). " +
                    "\"Un-queue\" leaves the files alone and lets you review them again.",
            )
        },
        confirmButton = { TextButton(onClick = onSend) { Text("Send to bin", color = DeleteRed) } },
        dismissButton = {
            Row {
                TextButton(onClick = onForget) { Text("Un-queue") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

// ---------------------------------------------------------------------------- setup

@Composable
private fun SetupScreen(
    state: UiState,
    vm: SwipeViewModel,
    onPickSource: () -> Unit,
    onPickDest: () -> Unit,
    onPickKeep: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 24.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("SwipeCull", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(
                "Swipe left to move a photo or video to your delete folder. " +
                    "Swipe right to keep it and move on.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.binCount > 0) {
                OutlinedCard(onClick = { vm.openBinDialog() }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.DeleteForever, contentDescription = null, tint = DeleteRed, modifier = Modifier.size(24.dp))
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(
                                "${state.binCount} file${if (state.binCount == 1) "" else "s"} waiting for the bin",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                "Tap to move them to the recycle bin",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            SectionLabel("Review")
            FolderCard(
                icon = AppIcons.Folder,
                title = "Folder to review",
                value = state.sourceName ?: "Tap to choose a folder",
                isPlaceholder = state.sourceName == null,
                note = "Only files directly inside it are shown, not subfolders.",
                onClick = onPickSource,
            )

            SectionLabel("Where files go")
            FolderCard(
                icon = AppIcons.Delete,
                tint = DeleteRed,
                title = "Swipe left: delete folder",
                value = state.destName ?: DEFAULT_DEST_FOLDER,
                note = if (state.destUri == null) "Default: created inside the folder you review." else null,
                onClick = onPickDest,
                onReset = if (state.destUri != null) ({ vm.setDest(null) }) else null,
                resetDescription = "Use the default delete folder",
            )
            FolderCard(
                icon = AppIcons.Check,
                tint = KeepGreen,
                title = "Swipe right: keep folder",
                value = state.keepName ?: "Not set",
                isPlaceholder = state.keepName == null,
                note = if (state.keepUri == null) "Optional. Kept files stay where they are unless you pick one." else null,
                onClick = onPickKeep,
                onReset = if (state.keepUri != null) ({ vm.setKeep(null) }) else null,
                resetDescription = "Don't move kept files",
            )
            if (state.keepUri != null) {
                SwitchRow(
                    title = "Only move kept videos",
                    subtitle = if (state.keepVideosOnly) "Kept photos stay where they are" else "Kept photos are moved too",
                    checked = state.keepVideosOnly,
                    onChange = vm::setKeepVideosOnly,
                )
            }

            SectionLabel("Options")
            SwitchRow(
                icon = AppIcons.Shuffle,
                title = "Shuffle",
                subtitle = "Go through the files in random order",
                checked = state.shuffle,
                onChange = { vm.toggleShuffle() },
            )
            SwitchRow(
                icon = AppIcons.VolumeUp,
                title = "Even out video volume",
                subtitle = "Boost quiet videos and tame loud ones",
                checked = state.levelVolume,
                onChange = { vm.toggleLevelVolume() },
            )
            SwitchRow(
                icon = AppIcons.SkipNext,
                title = "Swipe to browse",
                subtitle = if (state.swipeBrowse) "Swipe left for next, right for previous. Use the buttons to delete and keep."
                else "Swipe left to delete, right to keep",
                checked = state.swipeBrowse,
                onChange = { vm.toggleSwipeBrowse() },
            )
            SwitchRow(
                icon = AppIcons.Folder,
                title = "Group similar photos",
                subtitle = "Bursts and near-duplicates appear next to each other. Adds a short wait when starting.",
                checked = state.groupSimilar,
                onChange = { vm.toggleGroupSimilar() },
            )
            ChoiceRow(
                title = "Double-tap a video to skip",
                options = listOf("Off" to 0, "5 s" to 5, "10 s" to 10, "15 s" to 15, "30 s" to 30),
                selected = state.seekSeconds,
                onSelect = vm::setSeekSeconds,
            )

            SectionLabel("Order and filters")
            ChoiceRow(
                title = "Order",
                options = SortOrder.entries.map { it.label to it },
                selected = state.sortOrder,
                onSelect = vm::setSort,
            )
            ChoiceRow(
                title = "Show",
                options = TypeFilter.entries.map { it.label to it },
                selected = state.typeFilter,
                onSelect = vm::setTypeFilter,
            )
            ChoiceRow(
                title = "Only files at least",
                options = listOf("Any size" to 0, "10 MB" to 10, "50 MB" to 50, "100 MB" to 100, "500 MB" to 500),
                selected = state.minSizeMb,
                onSelect = vm::setMinSizeMb,
            )
            ChoiceRow(
                title = "Only files older than",
                options = listOf("Any age" to 0, "1 month" to 30, "6 months" to 180, "1 year" to 365, "2 years" to 730),
                selected = state.olderThanDays,
                onSelect = vm::setOlderThanDays,
            )

            Text(
                "Android doesn't let apps open a storage root or the top-level Download folder, " +
                    "so pick a subfolder such as DCIM/Camera or Pictures/Screenshots.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Surface(tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { vm.openTags() }) {
                    Icon(AppIcons.Label, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.tagAssign.isEmpty()) "Tags" else "Tags (${state.tagAssign.size})")
                }
                Button(
                    onClick = { vm.start() },
                    enabled = state.sourceUri != null,
                    modifier = Modifier.weight(1f),
                ) { Text("Start swiping") }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun FolderCard(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    isPlaceholder: Boolean = false,
    note: String? = null,
    onReset: (() -> Unit)? = null,
    resetDescription: String = "Reset",
) {
    OutlinedCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isPlaceholder) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                if (note != null) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onReset != null) {
                IconButton(onClick = onReset) {
                    Icon(AppIcons.Close, contentDescription = resetDescription)
                }
            }
        }
    }
}

/** A row of single-choice pills (selected = colour change only, no checkmark). */
@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (label, value) ->
                val on = value == selected
                val shape = RoundedCornerShape(50)
                Box(
                    Modifier
                        .height(34.dp)
                        .clip(shape)
                        .background(if (on) scheme.primaryContainer else Color.Transparent)
                        .border(1.dp, if (on) scheme.primary else scheme.outline, shape)
                        .clickable { onSelect(value) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) scheme.onPrimaryContainer else scheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 16.dp).size(24.dp))
        }
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---------------------------------------------------------------------------- review

@Composable
private fun ReviewScreen(state: UiState, vm: SwipeViewModel, imageLoader: ImageLoader) {
    val item = state.current ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val cardState = rememberSwipeCardState(item.uri)
    var muted by rememberSaveable { mutableStateOf(true) }
    var paused by remember(item.uri) { mutableStateOf(false) }
    var speed by rememberSaveable { mutableStateOf(1f) }
    var seekFlash by remember(item.uri) { mutableStateOf<Pair<String, Int>?>(null) }
    LaunchedEffect(seekFlash) {
        if (seekFlash != null) {
            delay(700)
            seekFlash = null
        }
    }
    val cc = vm.captions
    val ccState by cc.state.collectAsStateWithLifecycle()
    var ccDialog by remember { mutableStateOf(false) }
    LaunchedEffect(item.uri) { if (item.isVideo) cc.loadFor(item) else cc.clear() }
    val details by produceState<String?>(null, item.uri) {
        value = withContext(Dispatchers.IO) { mediaDetails(context, item) }
    }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Filled in by the video player so the seek bar (outside the swipe card) can drive it.
    val playerHolder = remember(item.uri) { mutableStateOf<ExoPlayer?>(null) }

    // Warm the cache for the next couple of photos so swiping feels instant.
    LaunchedEffect(state.index) {
        for (i in 1..2) {
            state.items.getOrNull(state.index + i)
                ?.takeIf { !it.isVideo }
                ?.let { imageLoader.enqueue(imageRequest(context, it.uri)) }
        }
    }
    LaunchedEffect(menuOpen) { if (menuOpen) vm.refreshMarkedCount() }
    val doubleTap: ((Float) -> Unit)? = if (item.isVideo && state.seekSeconds > 0) {
        { frac ->
            playerHolder.value?.let { p ->
                val dir = if (frac < 0.5f) -1 else 1
                val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
                p.seekTo((p.currentPosition + dir * state.seekSeconds * 1000L).coerceIn(0L, dur))
                seekFlash = (if (dir < 0) "-${state.seekSeconds} s" else "+${state.seekSeconds} s") to
                    ((seekFlash?.second ?: 0) + 1)
            }
        }
    } else null
    LaunchedEffect(item.uri) { vm.loadFavoriteStatus() }

    Column(Modifier.fillMaxSize()) {
        // Top bar: position + counts, overflow menu
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("${state.index + 1} / ${state.items.size}", style = MaterialTheme.typography.titleMedium)
                Text(
                    statusLine(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(AppIcons.MoreVert, contentDescription = "Menu")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Undo last swipe") },
                        leadingIcon = { Icon(AppIcons.Undo, contentDescription = null) },
                        enabled = state.canUndo && !state.undoing,
                        onClick = { menuOpen = false; vm.undo() },
                    )
                    DropdownMenuItem(
                        text = { Text("Previous file") },
                        leadingIcon = { Icon(AppIcons.ArrowBack, contentDescription = null) },
                        enabled = state.previousIndex != null && !state.undoing,
                        onClick = { menuOpen = false; vm.goPrevious() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (state.swipeBrowse) "Swipe: browse" else "Swipe: delete / keep") },
                        leadingIcon = { Icon(AppIcons.SkipNext, contentDescription = null) },
                        onClick = { menuOpen = false; vm.toggleSwipeBrowse() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (state.shuffle) "Shuffle: on" else "Shuffle: off") },
                        leadingIcon = { Icon(AppIcons.Shuffle, contentDescription = null) },
                        onClick = { menuOpen = false; vm.toggleShuffle() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (state.levelVolume) "Even volume: on" else "Even volume: off") },
                        leadingIcon = { Icon(AppIcons.VolumeUp, contentDescription = null) },
                        onClick = { menuOpen = false; vm.toggleLevelVolume() },
                    )
                    DropdownMenuItem(
                        text = { Text("Tags") },
                        leadingIcon = { Icon(AppIcons.Label, contentDescription = null) },
                        onClick = { menuOpen = false; vm.openTags() },
                    )
                    if (state.binCount > 0) {
                        DropdownMenuItem(
                            text = { Text("Bin: ${state.binCount} waiting", color = DeleteRed) },
                            leadingIcon = { Icon(AppIcons.DeleteForever, contentDescription = null, tint = DeleteRed) },
                            onClick = { menuOpen = false; vm.openBinDialog() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Change folder") },
                        leadingIcon = { Icon(AppIcons.Folder, contentDescription = null) },
                        onClick = { menuOpen = false; vm.endSession() },
                    )
                    if (state.destUri == null) {
                        val n = state.markedInFolder
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (n != null) "Delete marked files ($n)" else "Delete marked files",
                                    color = if ((n ?: 0) > 0) DeleteRed else Color.Unspecified,
                                )
                            },
                            leadingIcon = {
                                if ((n ?: 0) > 0) {
                                    Icon(AppIcons.DeleteForever, contentDescription = null, tint = DeleteRed)
                                } else {
                                    Icon(AppIcons.DeleteForever, contentDescription = null)
                                }
                            },
                            enabled = (n ?: 0) > 0,
                            onClick = { menuOpen = false; confirmDelete = true },
                        )
                    }
                }
            }
        }
        LinearProgressIndicator(
            progress = { state.index.toFloat() / state.items.size.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )

        // The card
        SwipeCard(
            state = cardState,
            enabled = !state.undoing,
            onSwiped = { dir ->
                if (state.swipeBrowse) {
                    if (dir < 0) vm.goNext() else vm.goPrevious()
                } else if (dir < 0) vm.markForDeletion() else vm.keep()
            },
            onTap = { if (item.isVideo) paused = !paused },
            browse = state.swipeBrowse,
            canSwipe = { dir -> !state.swipeBrowse || dir < 0 || state.previousIndex != null },
            onDoubleTap = doubleTap,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            key(item.uri) {
                if (item.isVideo) {
                    VideoPlayer(
                        uri = item.uri,
                        muted = muted,
                        leveled = state.levelVolume,
                        speed = speed,
                        paused = paused,
                        onPlayer = { playerHolder.value = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    val request = remember(item.uri) { imageRequest(context, item.uri) }
                    ZoomableBox(Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = request,
                            imageLoader = imageLoader,
                            contentDescription = item.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            if (item.isVideo && paused) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(72.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AppIcons.PlayArrow, contentDescription = "Paused", tint = Color.White, modifier = Modifier.size(44.dp))
                }
            }
            if (item.isVideo) {
                ScrimIconButton(
                    icon = if (muted) AppIcons.VolumeOff else AppIcons.VolumeUp,
                    description = if (muted) "Unmute" else "Mute",
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                    onClick = { muted = !muted },
                )
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 64.dp, bottom = 12.dp)
                        .height(44.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable {
                            speed = when (speed) { 1f -> 1.5f; 1.5f -> 2f; 2f -> 0.5f; else -> 1f }
                        }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when (speed) { 0.5f -> "0.5x"; 1f -> "1x"; 1.5f -> "1.5x"; else -> "2x" },
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                val ccOn = ccState.cuesFor == item.uri.toString() && ccState.cues.isNotEmpty() && ccState.show
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 128.dp, bottom = 12.dp)
                        .height(44.dp)
                        .clip(CircleShape)
                        .background(if (ccOn) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.55f))
                        .clickable { ccDialog = true }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "CC",
                        color = if (ccOn) MaterialTheme.colorScheme.onPrimary else Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                CaptionOverlay(ccState, item.uri.toString(), playerHolder.value)
            }
            seekFlash?.let { (text, _) ->
                Box(
                    Modifier
                        .align(if (text.startsWith("-")) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(28.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) { Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium) }
            }
            val isFav = item.documentId in state.favorites
            ScrimIconButton(
                icon = if (isFav) AppIcons.Star else AppIcons.StarBorder,
                description = if (isFav) "Remove from favorites" else "Add to favorites",
                tint = if (isFav) FavoriteGold else Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                onClick = { vm.toggleFavorite() },
            )
        }

        if (item.isVideo) {
            VideoSeekBar(
                player = playerHolder.value,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }

        TagRow(state, vm, Modifier.fillMaxWidth().padding(horizontal = 16.dp))

        // File info
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 6.dp)) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                fileMeta(item, details),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.similar[item.documentId]?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }

        // Action buttons (left = destructive, middle = neutral, right = keep)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            ActionSlot("Bin") {
                FilledTonalIconButton(
                    onClick = { scope.launch { if (cardState.flingOut(-1)) vm.binCurrent() } },
                    enabled = !state.undoing,
                    modifier = Modifier.size(48.dp),
                ) { Icon(AppIcons.DeleteForever, contentDescription = "Send to the bin (recycle bin)", tint = DeleteRed) }
            }
            ActionSlot("Delete") {
                FilledIconButton(
                    onClick = { scope.launch { if (cardState.flingOut(-1)) vm.markForDeletion() } },
                    enabled = !state.undoing,
                    modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = DeleteRed, contentColor = Color.White),
                ) { Icon(AppIcons.Delete, contentDescription = "Move to the delete folder", modifier = Modifier.size(30.dp)) }
            }
            ActionSlot("Undo") {
                FilledTonalIconButton(
                    onClick = { vm.undo() },
                    enabled = state.canUndo && !state.undoing,
                    modifier = Modifier.size(48.dp),
                ) { Icon(AppIcons.Undo, contentDescription = "Undo last swipe") }
            }
            ActionSlot("Skip") {
                FilledTonalIconButton(
                    onClick = { if (!cardState.locked) vm.skip() },
                    enabled = !state.undoing,
                    modifier = Modifier.size(48.dp),
                ) { Icon(AppIcons.SkipNext, contentDescription = "Skip to the next file without keeping or deleting") }
            }
            ActionSlot("Keep") {
                FilledIconButton(
                    onClick = { scope.launch { if (cardState.flingOut(1)) vm.keep() } },
                    enabled = !state.undoing,
                    modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = KeepGreen, contentColor = Color.White),
                ) { Icon(AppIcons.Check, contentDescription = "Keep", modifier = Modifier.size(30.dp)) }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDeleteDialog(
            count = state.markedInFolder ?: 0,
            onConfirm = { confirmDelete = false; vm.deleteMarkedPermanently() },
            onDismiss = { confirmDelete = false },
        )
    }
    if (ccDialog && item.isVideo) {
        CaptionDialog(item, cc, ccState, onDismiss = { ccDialog = false })
    }
}

/** One action button in a fixed 64dp slot with a caption, so differently sized buttons line up. */
@Composable
private fun ActionSlot(label: String, button: @Composable () -> Unit) {
    Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) { button() }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Tag: travel · 3 marked · 2 kept · shuffled", showing only what's non-zero. */
private fun statusLine(state: UiState): String {
    val counts = listOf(
        state.movedCount to "marked",
        state.keptCount to "kept",
        state.skippedCount to "skipped",
        state.binnedCount to "binned",
    ).filter { it.first > 0 }.map { "${it.first} ${it.second}" }
    return listOfNotNull(
        state.reviewLabel?.let { if (it == "Favorites") it else "Tag: $it" },
        counts.takeIf { it.isNotEmpty() }?.joinToString(" · "),
        if (state.shuffle) "shuffled" else null,
        if (state.freedBytes > 0) "frees ${formatSize(state.freedBytes)}" else null,
    ).joinToString(" · ").ifEmpty { "Swipe or tap to begin" }
}

/** A 44dp round button with a dark backing, readable on top of any photo or video. */
@Composable
private fun ScrimIconButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(24.dp))
    }
}

private fun imageRequest(context: Context, uri: Uri): ImageRequest =
    ImageRequest.Builder(context).data(uri).size(2400).build()

private fun fileMeta(f: MediaFile, details: String?): String {
    val parts = ArrayList<String>()
    if (f.lastModified > 0) {
        parts += DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(f.lastModified))
    }
    if (details != null) parts += details else if (f.isVideo) parts += "video"
    if (f.size > 0) parts += formatSize(f.size)
    return parts.joinToString(" · ")
}

/** "4032×3024 · 12.2 MP" for photos, "1920×1080 · 0:32" for videos (null if unreadable). */
private fun mediaDetails(context: Context, f: MediaFile): String? = runCatching {
    if (f.isVideo) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, f.uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            listOfNotNull(
                if (w != null && h != null) (if (rot == 90 || rot == 270) "${h}×${w}" else "${w}×${h}") else null,
                dur?.let { formatTime(it) },
            ).joinToString(" · ")
        } finally {
            r.release()
        }
    } else {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(f.uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var w = o.outWidth
        var h = o.outHeight
        if (w <= 0 || h <= 0) return@runCatching null
        val orientation = context.contentResolver.openInputStream(f.uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        } ?: 1
        if (orientation == 6 || orientation == 8) { val t = w; w = h; h = t }
        "${w}×${h} · %.1f MP".format(w.toLong() * h / 1_000_000.0)
    }
}.getOrNull()?.takeIf { it.isNotEmpty() }

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    else -> "${bytes / 1024} KB"
}

// ---------------------------------------------------------------------------- video

@Composable
private fun VideoPlayer(
    uri: Uri,
    muted: Boolean,
    leveled: Boolean,
    speed: Float,
    paused: Boolean,
    onPlayer: (ExoPlayer) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
        }
    }
    DisposableEffect(player) {
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC) // fast scrubbing on big videos
        onPlayer(player)
        onDispose { player.release() }
    }
    DisposableEffect(player, leveled) {
        val leveler = if (leveled) VolumeLeveler(player) else null
        onDispose { leveler?.release() }
    }
    LaunchedEffect(player, muted) { player.volume = if (muted) 0f else 1f }
    LaunchedEffect(player, speed) { player.setPlaybackSpeed(speed) }
    LaunchedEffect(player, paused) { player.playWhenReady = !paused }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { player.pause() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { player.playWhenReady = !paused }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // Inflated from XML so it can use a TextureView (see res/layout/player_view.xml).
            (LayoutInflater.from(ctx).inflate(R.layout.player_view, null) as PlayerView).also {
                it.player = player
            }
        },
    )
}

@Composable
private fun VideoSeekBar(player: ExoPlayer?, modifier: Modifier = Modifier) {
    var position by remember(player) { mutableLongStateOf(0L) }
    var duration by remember(player) { mutableLongStateOf(0L) }
    var dragging by remember(player) { mutableStateOf(false) }

    LaunchedEffect(player) {
        if (player == null) return@LaunchedEffect
        while (true) {
            val d = player.duration
            duration = if (d == C.TIME_UNSET || d < 0) 0L else d
            if (!dragging) position = player.currentPosition.coerceAtLeast(0L)
            delay(150)
        }
    }

    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(formatTime(position), style = MaterialTheme.typography.labelMedium)
        Slider(
            value = fraction,
            onValueChange = { f ->
                if (duration > 0) {
                    dragging = true
                    position = (f * duration).toLong()
                    player?.seekTo(position)
                }
            },
            onValueChangeFinished = { dragging = false },
            enabled = player != null && duration > 0,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text(formatTime(duration), style = MaterialTheme.typography.labelMedium)
    }
}

private fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

// ---------------------------------------------------------------------------- done

@Composable
private fun DoneScreen(state: UiState, vm: SwipeViewModel) {
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(state.movedCount) { vm.refreshMarkedCount() }
    val tagReview = state.reviewLabel != null
    val nothing = state.items.isEmpty()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Icon(
            AppIcons.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Text(
            if (nothing) (if (tagReview) "No files to review" else "Nothing new to review")
            else (if (tagReview) "Review finished" else "All caught up"),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        if (nothing) {
            Text(
                if (tagReview) "No matching files could be found."
                else "Every file in this folder has been swiped already, or your filters hide the rest.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else {
            if (state.freedBytes > 0) {
                Text(
                    "About ${formatSize(state.freedBytes)} will be freed once the marked and binned files are deleted.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                StatBlock(AppIcons.Delete, DeleteRed, state.movedCount, "marked")
                StatBlock(AppIcons.Check, KeepGreen, state.keptCount, "kept")
                if (state.skippedCount > 0) {
                    StatBlock(AppIcons.SkipNext, MaterialTheme.colorScheme.onSurfaceVariant, state.skippedCount, "skipped")
                }
                if (state.binnedCount > 0) {
                    StatBlock(AppIcons.DeleteForever, DeleteRed, state.binnedCount, "binned")
                }
            }
            if (state.skippedCount > 0) {
                Text(
                    "Skipped files will show up again next time you start.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (state.binCount > 0) {
            Button(onClick = { vm.openBinDialog() }, modifier = Modifier.fillMaxWidth()) {
                Icon(AppIcons.DeleteForever, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Send ${state.binCount} to the bin")
            }
        }

        val marked = state.markedInFolder ?: 0
        if (state.destUri == null && marked > 0) {
            Button(
                onClick = { confirmDelete = true },
                colors = ButtonDefaults.buttonColors(containerColor = DeleteRed, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(AppIcons.DeleteForever, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Delete $marked marked files")
            }
        }
        if (state.destUri != null && state.movedCount > 0) {
            Text(
                "Your marked files are in ${state.destName}. Delete them from the Files app when you're ready.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (state.keepUri != null && state.keptCount > 0) {
            Text(
                "Kept ${if (state.keepVideosOnly) "videos" else "files"} were moved to ${state.keepName}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            onClick = { vm.undo() },
            enabled = state.canUndo && !state.undoing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(AppIcons.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Undo last swipe")
        }
        if (!tagReview) {
            OutlinedButton(onClick = { vm.resetKept() }, modifier = Modifier.fillMaxWidth()) {
                Text("Review the kept files again")
            }
        } else {
            OutlinedButton(onClick = { vm.openTags() }, modifier = Modifier.fillMaxWidth()) {
                Icon(AppIcons.Label, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Back to tags")
            }
        }
        TextButton(onClick = { vm.endSession() }) { Text("Choose another folder") }
    }

    if (confirmDelete) {
        ConfirmDeleteDialog(
            count = state.markedInFolder ?: 0,
            onConfirm = { confirmDelete = false; vm.deleteMarkedPermanently() },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun StatBlock(icon: ImageVector, color: Color, count: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Text("$count", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConfirmDeleteDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.DeleteForever, contentDescription = null, tint = DeleteRed) },
        title = { Text("Delete $count files permanently?") },
        text = {
            Text(
                "This permanently deletes everything in the \"$DEFAULT_DEST_FOLDER\" folder. " +
                    "It can't be undone.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = DeleteRed) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
