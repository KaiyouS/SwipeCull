package com.example.swipecull

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
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
        vm.events.collect { snackbar.showSnackbar(it) }
    }

    // The system "add to favorites?" confirmation.
    val favLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.favoriteResult(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(Unit) {
        vm.favRequests.collect { favLauncher.launch(IntentSenderRequest.Builder(it).build()) }
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
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("SwipeCull", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(
            "Go through the photos and videos in a folder. Swipe left to move one into your " +
                "\"marked for deletion\" folder, swipe right to keep it and see the next one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FolderCard(
            title = "Folder to review",
            value = state.sourceName ?: "Not chosen yet",
            action = if (state.sourceUri == null) "Choose" else "Change",
            onClick = onPickSource,
        )
        FolderCard(
            title = "Swipe-left files are moved to",
            value = state.destName ?: "$DEFAULT_DEST_FOLDER  (a subfolder of the folder above)",
            action = "Change",
            onClick = onPickDest,
        )
        if (state.destUri != null) {
            TextButton(onClick = { vm.setDest(null) }) { Text("Use the default subfolder instead") }
        }

        FolderCard(
            title = "Swipe-right (kept) files are moved to",
            value = state.keepName ?: "Nowhere – kept files stay where they are",
            action = if (state.keepUri == null) "Choose" else "Change",
            onClick = onPickKeep,
        )
        if (state.keepUri != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Only move kept videos", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (state.keepVideosOnly) "Kept photos stay where they are" else "Kept photos are moved too",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.keepVideosOnly, onCheckedChange = vm::setKeepVideosOnly)
            }
            TextButton(onClick = { vm.setKeep(null) }) { Text("Don't move kept files") }
        }

        Button(
            onClick = { vm.start() },
            enabled = state.sourceUri != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start swiping") }

        OutlinedButton(onClick = { vm.openTags() }, modifier = Modifier.fillMaxWidth()) {
            Text("Tagged files (${state.tagAssign.size})")
        }

        Text(
            "Tip: Android won't let apps open a storage root or the top-level Download folder. " +
                "Pick a subfolder such as DCIM/Camera, Pictures/Screenshots or Download/<something>. " +
                "Only files directly inside the folder are shown (not subfolders).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FolderCard(title: String, value: String, action: String, onClick: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
            FilledTonalButton(onClick = onClick) { Text(action) }
        }
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
    LaunchedEffect(item.uri) { vm.loadFavoriteStatus() }

    val swipeLeft: suspend () -> Unit = {
        if (cardState.flingOut(-1)) vm.markForDeletion()
    }
    val swipeRight: suspend () -> Unit = {
        if (cardState.flingOut(1)) vm.keep()
    }

    Column(Modifier.fillMaxSize()) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("${state.index + 1} / ${state.items.size}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${state.movedCount} marked · ${state.keptCount} kept",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Undo last swipe") },
                        enabled = state.canUndo && !state.undoing,
                        onClick = { menuOpen = false; vm.undo() },
                    )
                    if (state.destUri == null) {
                        val n = state.markedInFolder
                        DropdownMenuItem(
                            text = { Text(if (n != null) "Delete marked files ($n)…" else "Delete marked files…") },
                            enabled = (n ?: 0) > 0,
                            onClick = { menuOpen = false; confirmDelete = true },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Tagged files…") },
                        onClick = { menuOpen = false; vm.openTags() },
                    )
                    DropdownMenuItem(
                        text = { Text("Change folder") },
                        onClick = { menuOpen = false; vm.endSession() },
                    )
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
            onSwiped = { dir -> if (dir < 0) vm.markForDeletion() else vm.keep() },
            onTap = { if (item.isVideo) paused = !paused },
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            key(item.uri) {
                if (item.isVideo) {
                    VideoPlayer(
                        uri = item.uri,
                        muted = muted,
                        paused = paused,
                        onPlayer = { playerHolder.value = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    val request = remember(item.uri) { imageRequest(context, item.uri) }
                    AsyncImage(
                        model = request,
                        imageLoader = imageLoader,
                        contentDescription = item.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (item.isVideo) {
                if (paused) {
                    Text("⏸", fontSize = 64.sp, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.align(Alignment.Center))
                }
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                        .clickable { muted = !muted }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) { Text(if (muted) "🔇" else "🔊", fontSize = 20.sp) }
            }
            val isFav = item.documentId in state.favorites
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                    .clickable { vm.toggleFavorite() }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    if (isFav) "★" else "☆",
                    fontSize = 26.sp,
                    color = if (isFav) Color(0xFFFFC107) else Color.White,
                )
            }
        }

        if (item.isVideo) {
            VideoSeekBar(
                player = playerHolder.value,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }

        TagRow(state, vm, Modifier.fillMaxWidth().padding(horizontal = 16.dp))

        // File info
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                fileMeta(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Buttons
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(
                onClick = { scope.launch { swipeLeft() } },
                enabled = !state.undoing,
                modifier = Modifier.size(68.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = DeleteRed, contentColor = Color.White),
            ) { Icon(Icons.Default.Delete, contentDescription = "Move to marked-for-deletion folder", modifier = Modifier.size(32.dp)) }

            OutlinedButton(onClick = { vm.undo() }, enabled = state.canUndo && !state.undoing) { Text("Undo") }

            FilledIconButton(
                onClick = { scope.launch { swipeRight() } },
                enabled = !state.undoing,
                modifier = Modifier.size(68.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = KeepGreen, contentColor = Color.White),
            ) { Icon(Icons.Default.Check, contentDescription = "Keep", modifier = Modifier.size(32.dp)) }
        }
    }

    if (confirmDelete) {
        ConfirmDeleteDialog(
            count = state.markedInFolder ?: 0,
            onConfirm = { confirmDelete = false; vm.deleteMarkedPermanently() },
            onDismiss = { confirmDelete = false },
        )
    }
}

private fun imageRequest(context: Context, uri: Uri): ImageRequest =
    ImageRequest.Builder(context).data(uri).size(1600).build()

private fun fileMeta(f: MediaFile): String {
    val parts = ArrayList<String>()
    if (f.lastModified > 0) {
        parts += DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(f.lastModified))
    }
    if (f.size > 0) parts += formatSize(f.size)
    if (f.isVideo) parts += "video"
    return parts.joinToString(" · ")
}

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
    androidx.compose.runtime.DisposableEffect(player) {
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC) // fast scrubbing on big videos
        onPlayer(player)
        onDispose { player.release() }
    }
    LaunchedEffect(player, muted) { player.volume = if (muted) 0f else 1f }
    LaunchedEffect(player, paused) { player.playWhenReady = !paused }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { player.pause() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { player.playWhenReady = !paused }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                this.player = player
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

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Text("All caught up", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            if (state.items.isEmpty()) "Nothing new to review in this folder."
            else "${state.movedCount} marked for deletion · ${state.keptCount} kept",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )

        val marked = state.markedInFolder ?: 0
        if (state.destUri == null && marked > 0) {
            Button(
                onClick = { confirmDelete = true },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = DeleteRed, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Delete $marked marked files permanently") }
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

        OutlinedButton(
            onClick = { vm.undo() },
            enabled = state.canUndo && !state.undoing,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Undo last swipe") }
        OutlinedButton(onClick = { vm.resetKept() }, modifier = Modifier.fillMaxWidth()) {
            Text("Review the kept files again")
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
private fun ConfirmDeleteDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
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
