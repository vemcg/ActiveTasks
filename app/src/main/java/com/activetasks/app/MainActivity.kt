// Copyright (c) 2026 Vern McGeorge. All rights reserved.
package com.activetasks.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val storedItems = readToDoItems(preferences.getString("todo_items", "[]") ?: "[]")
        val loadedItems = itemsToLoad(preferences.getInt("items_schema", 1), storedItems)
        if (loadedItems != storedItems || preferences.getInt("items_schema", 1) < ITEMS_SCHEMA_VERSION) {
            preferences.edit()
                .putString("todo_items", writeToDoItems(loadedItems))
                .putInt("items_schema", ITEMS_SCHEMA_VERSION)
                .apply()
        }
        setContent {
            MaterialTheme(colorScheme = activeTasksColorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ActiveTasksApp(
                        initialSheetUrl = preferences.getString("sheet_url", "") ?: "",
                        initialAppsScriptUrl = preferences.getString("apps_script_url", "") ?: "",
                        initialImportanceWeight = preferences.getFloat("importance_weight", DEFAULT_IMPORTANCE_WEIGHT),
                        initialTopN = preferences.getInt("top_n", 5),
                        initialItems = loadedItems,
                        initialLists = readStringList(preferences.getString("known_lists", "[]") ?: "[]"),
                        initialLastList = preferences.getString("last_list", null),
                        onSheetUrlSaved = { url -> preferences.edit().putString("sheet_url", url).apply() },
                        onAppsScriptUrlSaved = { url -> preferences.edit().putString("apps_script_url", url).apply() },
                        onImportanceWeightSaved = { weight -> preferences.edit().putFloat("importance_weight", weight).apply() },
                        onTopNSaved = { count -> preferences.edit().putInt("top_n", count).apply() },
                        onItemsSaved = { items -> preferences.edit().putString("todo_items", writeToDoItems(items)).apply() },
                        onListsSaved = { lists -> preferences.edit().putString("known_lists", writeStringList(lists)).apply() },
                        onLastListSaved = { list -> preferences.edit().putString("last_list", list).apply() }
                    )
                }
            }
        }
    }

    companion object {
        const val PREFS_NAME = "activetasks_settings"
    }
}

private enum class Screen { CAROUSEL, SETTINGS, QR_SCANNER }

@Composable
fun ActiveTasksApp(
    initialSheetUrl: String,
    initialAppsScriptUrl: String,
    initialImportanceWeight: Float,
    initialTopN: Int,
    initialItems: List<ToDoItem>,
    initialLists: List<String>,
    initialLastList: String?,
    onSheetUrlSaved: (String) -> Unit,
    onAppsScriptUrlSaved: (String) -> Unit,
    onImportanceWeightSaved: (Float) -> Unit,
    onTopNSaved: (Int) -> Unit,
    onItemsSaved: (List<ToDoItem>) -> Unit,
    onListsSaved: (List<String>) -> Unit,
    onLastListSaved: (String) -> Unit
) {
    var screen by remember { mutableStateOf(if (initialSheetUrl.isBlank()) Screen.SETTINGS else Screen.CAROUSEL) }
    var sheetUrl by remember { mutableStateOf(initialSheetUrl) }
    var appsScriptUrl by remember { mutableStateOf(initialAppsScriptUrl) }
    var importanceWeight by remember { mutableStateOf(initialImportanceWeight) }
    var topN by remember { mutableStateOf(initialTopN) }
    var items by remember { mutableStateOf(initialItems) }
    var lists by remember { mutableStateOf(initialLists) }
    var lastList by remember { mutableStateOf(initialLastList) }
    var editingItemId by remember { mutableStateOf<String?>(null) }
    var syncMessage by remember { mutableStateOf("") }
    var actionError by remember { mutableStateOf("") }
    var syncing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    fun persistItems(newItems: List<ToDoItem>) {
        items = newItems
        onItemsSaved(newItems)
    }

    fun persistLists(newLists: List<String>) {
        lists = newLists
        onListsSaved(newLists)
    }

    // A sync already in flight was fetched before whatever change just happened, so its result is
    // stale: [resyncPending] queues one more sync behind it, and [removedDuringSync] keeps the
    // stale result from re-adding an item completed while it was running.
    var resyncPending by remember { mutableStateOf(false) }
    val removedDuringSync = remember { mutableSetOf<String>() }

    fun runSync() {
        if (sheetUrl.isBlank()) return
        if (appsScriptUrl.isBlank()) {
            syncMessage = "Set the Apps Script Web App URL above to sync referred items."
            return
        }
        if (syncing) {
            resyncPending = true
            return
        }
        syncing = true
        removedDuringSync.clear()
        coroutineScope.launch {
            val tabs = withContext(Dispatchers.IO) { fetchSheetTabs(sheetUrl) }
            if (tabs.isEmpty()) {
                syncing = false
                resyncPending = false
                syncMessage = "Couldn't read any tabs from this Sheet. Check the URL and that " +
                    "sharing is \"Anyone with the link can view\"."
                return@launch
            }
            val imported = withContext(Dispatchers.IO) {
                val prioritiesByTab = fetchAllPriorities(appsScriptUrl)
                    .groupBy { it.category }
                    .mapValues { (_, rows) -> rows.associate { it.description to it.priority } }
                tabs.flatMap { tab ->
                    toDoItemsFromReferredRows(tab.csv, tab.tabName, prioritiesByTab[tab.tabName].orEmpty())
                }
            }
            syncing = false
            persistItems(mergeImportedToDoItems(imported.filterNot { it.id in removedDuringSync }, items))
            persistLists(tabs.map { it.tabName })
            syncMessage = "Synced ${tabs.size} list(s)."
            if (resyncPending) {
                resyncPending = false
                runSync()
            }
        }
    }

    // Sync on every foreground entry - a cold launch, or coming back from MicroTasking after a
    // referral - so this list never waits on a manual "Sync Lists" to catch up.
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestRunSync by rememberUpdatedState(::runSync)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) latestRunSync()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun completeForNow(item: ToDoItem) {
        busy = true
        actionError = ""
        coroutineScope.launch {
            val ok = withContext(Dispatchers.IO) { clearSheetPriority(appsScriptUrl, item.list, item.description) }
            busy = false
            if (ok) {
                removedDuringSync += item.id
                persistItems(items.filterNot { it.id == item.id })
                runSync()
            } else {
                actionError = "Couldn't reach the Sheet to clear this item's priority - check your connection and try again."
            }
        }
    }

    fun fullyComplete(item: ToDoItem) {
        busy = true
        actionError = ""
        coroutineScope.launch {
            val ok = withContext(Dispatchers.IO) { deleteSheetRow(appsScriptUrl, item.list, item.description) }
            busy = false
            if (ok) {
                removedDuringSync += item.id
                persistItems(items.filterNot { it.id == item.id })
                runSync()
            } else {
                actionError = "Couldn't reach the Sheet to remove this row - check your connection and try again."
            }
        }
    }

    when (screen) {
        Screen.QR_SCANNER -> QrScannerScreen(
            onResult = { scanned ->
                screen = Screen.SETTINGS
                // The onboarding page makes separate Sheet-URL and Web App URL QR codes (older
                // ones carried both, newline-separated). Each line is classified by what it looks
                // like, and a setting the scan didn't carry is left as it was.
                val payload = parseSetupQr(scanned)
                payload.webAppUrl?.let {
                    appsScriptUrl = it
                    onAppsScriptUrlSaved(it)
                }
                val scannedSheetUrl = payload.sheetUrl
                if (scannedSheetUrl != null) {
                    sheetUrl = scannedSheetUrl
                    onSheetUrlSaved(scannedSheetUrl)
                    runSync()
                } else if (payload.webAppUrl != null) {
                    syncMessage = "Web App URL saved."
                } else {
                    syncMessage = "That QR code was empty - nothing was changed."
                }
            },
            onCancel = { screen = Screen.SETTINGS }
        )
        Screen.SETTINGS -> SettingsScreen(
            sheetUrl = sheetUrl,
            onSheetUrlChange = {
                sheetUrl = it
                onSheetUrlSaved(it)
            },
            appsScriptUrl = appsScriptUrl,
            onAppsScriptUrlChange = {
                appsScriptUrl = it
                onAppsScriptUrlSaved(it)
            },
            importanceWeight = importanceWeight,
            onImportanceWeightChange = {
                importanceWeight = it
                onImportanceWeightSaved(it)
            },
            topN = topN,
            onTopNChange = {
                topN = it
                onTopNSaved(it)
            },
            onScanQr = { screen = Screen.QR_SCANNER },
            onSync = ::runSync,
            syncing = syncing,
            syncMessage = syncMessage,
            canGoBack = lists.isNotEmpty(),
            onBack = { screen = Screen.CAROUSEL }
        )
        Screen.CAROUSEL -> CarouselScreen(
            lists = lists,
            items = items,
            importanceWeight = importanceWeight,
            topN = topN,
            initialList = initialListName(lists, items, importanceWeight, lastList),
            busy = busy,
            actionError = actionError,
            onListOpened = { list ->
                lastList = list
                onLastListSaved(list)
            },
            onOpenSettings = { screen = Screen.SETTINGS },
            onAdjustItem = { editingItemId = it },
            onCompleteForNow = { item -> completeForNow(item) },
            onFullyComplete = { item -> fullyComplete(item) }
        )
    }

    val editingItem = items.find { it.id == editingItemId }
    if (editingItem != null) {
        ItemDetailDialog(
            item = editingItem,
            onDismiss = { editingItemId = null },
            onPriorityChange = { importance, urgency ->
                persistItems(items.map { if (it.id == editingItem.id) it.copy(importance = importance, urgency = urgency) else it })
            },
            onProgressChange = { progress ->
                persistItems(items.map { if (it.id == editingItem.id) it.copy(progress = progress) else it })
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    sheetUrl: String,
    onSheetUrlChange: (String) -> Unit,
    appsScriptUrl: String,
    onAppsScriptUrlChange: (String) -> Unit,
    importanceWeight: Float,
    onImportanceWeightChange: (Float) -> Unit,
    topN: Int,
    onTopNChange: (Int) -> Unit,
    onScanQr: () -> Unit,
    onSync: () -> Unit,
    syncing: Boolean,
    syncMessage: String,
    canGoBack: Boolean,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("ActiveTasks Settings") },
            navigationIcon = {
                if (canGoBack) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            }
        )
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
            // Same title, order and wording as MicroTasking's "Google Sheet Connection" section
            // (only the "list" / what-the-Web-App-is-for words differ) - keep the two in sync.
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Google Sheet Connection", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Your tasks live in a Google Sheet you own. Paste its URL, or scan the Sheet " +
                            "QR code from the onboarding page, so the app can read it - each tab " +
                            "becomes a list.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    OutlinedTextField(
                        value = sheetUrl,
                        onValueChange = onSheetUrlChange,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        label = { Text("Google Sheet URL") }
                    )
                    OutlinedButton(onClick = onScanQr, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                        Text(" Scan Sheet QR Code")
                    }

                    Text(
                        "The Web App is a small script inside your Sheet that lets the app write back " +
                            "to it - completing and re-prioritizing items. Deploy it once from your " +
                            "Sheet (Extensions > Apps Script > Deploy > New deployment > Web app), " +
                            "then paste its URL or scan its QR code from the onboarding page.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 20.dp)
                    )
                    OutlinedTextField(
                        value = appsScriptUrl,
                        onValueChange = onAppsScriptUrlChange,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        label = { Text("Apps Script Web App URL") },
                        placeholder = { Text("https://script.google.com/macros/s/…/exec") }
                    )
                    // Both scan buttons open the same scanner; the result is routed by what the
                    // scanned text looks like (parseSetupQr), never by which button was pressed.
                    OutlinedButton(onClick = onScanQr, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                        Text(" Scan Web App QR Code")
                    }

                    Button(
                        onClick = onSync,
                        enabled = sheetUrl.isNotBlank() && !syncing,
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
                    ) {
                        Text(if (syncing) "Syncing…" else "Sync Lists")
                    }
                    if (syncMessage.isNotBlank()) {
                        Text(
                            syncMessage,
                            modifier = Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Text(
                "Importance weight: ${"%.1f".format(importanceWeight)}x",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 24.dp)
            )
            Text(
                "How much more an item's importance counts than its urgency when ranking your " +
                    "lists (score = importance × weight + urgency).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = importanceWeight,
                onValueChange = onImportanceWeightChange,
                valueRange = 0.5f..4f,
                steps = 6
            )

            Text("Items per list: $topN", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { if (topN > 1) onTopNChange(topN - 1) }) { Text("−") }
                Text("$topN", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { if (topN < 10) onTopNChange(topN + 1) }) { Text("+") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CarouselScreen(
    lists: List<String>,
    items: List<ToDoItem>,
    importanceWeight: Float,
    topN: Int,
    initialList: String?,
    busy: Boolean,
    actionError: String,
    onListOpened: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onAdjustItem: (String) -> Unit,
    onCompleteForNow: (ToDoItem) -> Unit,
    onFullyComplete: (ToDoItem) -> Unit
) {
    val pagerState = rememberPagerState(
        initialPage = lists.indexOf(initialList).coerceAtLeast(0),
        pageCount = { lists.size }
    )
    // Only a page the user actually swiped to counts as "last opened"; the initial page is just
    // where we started (possibly the highest-priority fallback), so it isn't recorded.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .drop(1)
            .collect { page -> lists.getOrNull(page)?.let(onListOpened) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(lists.getOrNull(pagerState.currentPage) ?: "ActiveTasks") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        if (lists.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "No lists yet. Open Settings and sync your Google Sheet to get started.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            return@Scaffold
        }
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (lists.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    lists.forEachIndexed { index, _ ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .background(
                                    if (index == pagerState.currentPage) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(2.dp)
                                )
                        )
                    }
                }
            }
            if (actionError.isNotBlank()) {
                Text(
                    actionError,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val listName = lists[page]
                val topItems = sortedForDisplay(items.filter { it.list == listName && !it.done }, importanceWeight).take(topN)
                if (topItems.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Nothing referred to \"$listName\" yet.", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(topItems, key = { it.id }) { toDoItem ->
                            ToDoItemRow(
                                item = toDoItem,
                                busy = busy,
                                onAdjust = { onAdjustItem(toDoItem.id) },
                                onCompleteForNow = { onCompleteForNow(toDoItem) },
                                onFullyComplete = { onFullyComplete(toDoItem) }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun quadrantColor(quadrant: Quadrant, colorScheme: androidx.compose.material3.ColorScheme): Color = when (quadrant) {
    Quadrant.DO_FIRST -> colorScheme.error
    Quadrant.SCHEDULE -> colorScheme.primary
    Quadrant.DELEGATE -> colorScheme.secondary
    Quadrant.ELIMINATE -> colorScheme.onSurfaceVariant
}

/** One referred task with its actions underneath, MicroTasking-style: no checkbox, no trash icon. */
@Composable
fun ToDoItemRow(
    item: ToDoItem,
    busy: Boolean,
    onAdjust: () -> Unit,
    onCompleteForNow: () -> Unit,
    onFullyComplete: () -> Unit
) {
    val context = LocalContext.current
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(item.description, style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                val quadrant = item.quadrant()
                Text(
                    quadrant.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = quadrantColor(quadrant, MaterialTheme.colorScheme),
                    modifier = Modifier.padding(end = 12.dp)
                )
                Text(
                    "${item.progress}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCompleteForNow, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("Complete (for now)")
                }
                Button(onClick = onFullyComplete, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("Fully complete")
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onAdjust, enabled = !busy) { Text("Priority & progress") }
                if (item.link.isNotBlank()) {
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.link))) }
                    }) { Text("Open link") }
                }
            }
        }
    }
}

/**
 * The Eisenhower-matrix priority picker: a single square surface where the exact tap/drag
 * position becomes continuous importance/urgency values (top-left = most important+urgent = "Do
 * First", matching the visual layout of the old quadrant grid, just continuous now instead of
 * four fixed cells). Background tinting shows the four reference quadrants; the dot marks the
 * current value.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MatrixWidget(
    importance: Float,
    urgency: Float,
    onChange: (importance: Float, urgency: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("Urgent", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            Text("Not urgent", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxWidth.toPx() } // square widget

            fun applyOffset(x: Float, y: Float) {
                val newUrgency = (1f - (x / widthPx)).coerceIn(0f, 1f)
                val newImportance = (1f - (y / heightPx)).coerceIn(0f, 1f)
                onChange(newImportance, newUrgency)
            }

            Column(modifier = Modifier.fillMaxWidth().height(maxWidth)) {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight()
                            .background(quadrantColor(Quadrant.DO_FIRST, MaterialTheme.colorScheme).copy(alpha = 0.16f))
                    )
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight()
                            .background(quadrantColor(Quadrant.SCHEDULE, MaterialTheme.colorScheme).copy(alpha = 0.16f))
                    )
                }
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight()
                            .background(quadrantColor(Quadrant.DELEGATE, MaterialTheme.colorScheme).copy(alpha = 0.16f))
                    )
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight()
                            .background(quadrantColor(Quadrant.ELIMINATE, MaterialTheme.colorScheme).copy(alpha = 0.16f))
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(maxWidth)
                    .pointerInputMatrix { x, y -> applyOffset(x, y) }
            )
            val markerX = maxWidth * (1f - urgency)
            val markerY = maxWidth * (1f - importance)
            Box(
                modifier = Modifier
                    .padding(start = markerX - 8.dp, top = markerY - 8.dp)
                    .size(16.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("Important", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun Modifier.pointerInputMatrix(onOffset: (x: Float, y: Float) -> Unit): Modifier = this
    .pointerInput(Unit) {
        detectTapGestures { offset -> onOffset(offset.x, offset.y) }
    }
    .pointerInput(Unit) {
        detectDragGestures { change, _ ->
            change.consume()
            onOffset(change.position.x, change.position.y)
        }
    }

/** Re-triage (priority matrix) and progress for a referred item; local-only, never written to the Sheet. */
@Composable
fun ItemDetailDialog(
    item: ToDoItem,
    onDismiss: () -> Unit,
    onPriorityChange: (importance: Float, urgency: Float) -> Unit,
    onProgressChange: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.description) },
        text = {
            Column {
                MatrixWidget(
                    importance = item.importance,
                    urgency = item.urgency,
                    onChange = onPriorityChange,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Progress: ${item.progress}%",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Slider(
                    value = item.progress.toFloat(),
                    onValueChange = { onProgressChange(it.toInt()) },
                    valueRange = 0f..100f
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Done") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerScreen(onResult: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Scan Sheet QR Code") },
            navigationIcon = {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Cancel scan")
                }
            }
        )
        if (hasCameraPermission) {
            var hasScanned by remember { mutableStateOf(false) }
            var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
            DisposableEffect(Unit) {
                onDispose { cameraProvider?.unbindAll() }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val scanner = BarcodeScanning.getClient()
                    val executor = ContextCompat.getMainExecutor(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val provider = cameraProviderFuture.get()
                        cameraProvider = provider
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(executor) { imageProxy ->
                            val mediaImage = imageProxy.image
                            if (mediaImage != null && !hasScanned) {
                                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                scanner.process(image)
                                    .addOnSuccessListener { barcodes ->
                                        val value = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                                        if (!hasScanned && !value.isNullOrBlank()) {
                                            hasScanned = true
                                            onResult(value)
                                        }
                                    }
                                    .addOnCompleteListener { imageProxy.close() }
                            } else {
                                imageProxy.close()
                            }
                        }
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }, executor)
                    previewView
                }
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Camera permission is required to scan a QR code.", modifier = Modifier.padding(bottom = 12.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant camera permission")
                }
            }
        }
    }
}

// Same calm palette family as MicroTasking so ActiveTasks reads as a sibling app.
private val activeTasksColorScheme = lightColorScheme(
    primary = Color(0xFF2E7D6B),
    onPrimary = Color.White,
    secondary = Color(0xFF5FA88F),
    onSecondary = Color.White,
    background = Color(0xFFEFF2F1),
    surface = Color.White,
    error = Color(0xFFE2725B),
    onError = Color.White
)
