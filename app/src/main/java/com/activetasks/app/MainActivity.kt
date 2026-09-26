// Copyright (c) 2026 Vern McGeorge. All rights reserved.
// Updated 2026-09-26, after version v0.2.0-29 main 2026-09-26
package com.activetasks.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        TaskStore.ensureLoaded(this)
        setContent {
            MaterialTheme(colorScheme = activeTasksColorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ActiveTasksApp(
                        initialSheetUrl = preferences.getString(TaskStore.KEY_SHEET_URL, "") ?: "",
                        initialAppsScriptUrl = preferences.getString(TaskStore.KEY_APPS_SCRIPT_URL, "") ?: "",
                        initialImportanceWeight = preferences.getFloat("importance_weight", DEFAULT_IMPORTANCE_WEIGHT),
                        initialTopN = preferences.getInt("top_n", 5),
                        initialLastList = preferences.getString(TaskStore.KEY_LAST_LIST, null),
                        initialExcludedCategories = readStringList(preferences.getString(KEY_EXCLUDED_CATEGORIES, "[]") ?: "[]").toSet(),
                        onSheetUrlSaved = { url -> preferences.edit().putString(TaskStore.KEY_SHEET_URL, url).apply() },
                        onAppsScriptUrlSaved = { url -> preferences.edit().putString(TaskStore.KEY_APPS_SCRIPT_URL, url).apply() },
                        onImportanceWeightSaved = { weight -> preferences.edit().putFloat("importance_weight", weight).apply() },
                        onTopNSaved = { count -> preferences.edit().putInt("top_n", count).apply() },
                        onLastListSaved = { list -> preferences.edit().putString(TaskStore.KEY_LAST_LIST, list).apply() },
                        onExcludedCategoriesSaved = { excluded ->
                            preferences.edit().putString(KEY_EXCLUDED_CATEGORIES, writeStringList(excluded.sorted())).apply()
                        }
                    )
                }
            }
        }
    }

    companion object {
        const val PREFS_NAME = "activetasks_settings"

        // Tabs the user unchecked under Settings > Task Categories. Stored as the *excluded* set so
        // a tab added to the Sheet later is included by default, matching a fresh install (all in).
        const val KEY_EXCLUDED_CATEGORIES = "find_task_excluded_categories"
    }
}

private enum class Screen { CAROUSEL, SETTINGS, QR_SCANNER, FIND_TASK }

/**
 * Settings' unsaved edits. Hoisted above SettingsScreen (rather than local `remember`s there) so a
 * QR scan - which leaves Settings for the scanner screen and comes back - lands in the draft
 * without losing anything else typed so far, and Cancel still backs out of the scan too.
 */
data class SettingsDraft(
    val sheetUrl: String,
    val appsScriptUrl: String,
    // The slider's own value: -2 (full left, "Importance" biggest) .. +2 (full right, "Urgency"
    // biggest), 0 centered = equal. Converted to/from the persisted importanceWeight float only at
    // the edges (opening Settings, and Save) - see priorityTiltFromWeight/weightFromPriorityTilt.
    val priorityTilt: Float,
    val topN: Int,
    // Tabs Find Task's header button leaves out (Settings > Task Categories, unchecked ones).
    val excludedCategories: Set<String>
)

@Composable
fun ActiveTasksApp(
    initialSheetUrl: String,
    initialAppsScriptUrl: String,
    initialImportanceWeight: Float,
    initialTopN: Int,
    initialLastList: String?,
    initialExcludedCategories: Set<String>,
    onSheetUrlSaved: (String) -> Unit,
    onAppsScriptUrlSaved: (String) -> Unit,
    onImportanceWeightSaved: (Float) -> Unit,
    onTopNSaved: (Int) -> Unit,
    onLastListSaved: (String) -> Unit,
    onExcludedCategoriesSaved: (Set<String>) -> Unit
) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf(if (initialSheetUrl.isBlank()) Screen.SETTINGS else Screen.CAROUSEL) }
    // Hoisted above SettingsScreen so it survives the round trip through the QR scanner screen
    // instead of collapsing back to its default in between. See SPEC.md/PUNCH_LIST.md "Fix the
    // Google Sheet Connection section auto-collapsing".
    var openSettingsSection by remember { mutableStateOf(if (initialSheetUrl.isBlank()) "Google Sheet Connection" else "") }
    var sheetUrl by remember { mutableStateOf(initialSheetUrl) }
    var appsScriptUrl by remember { mutableStateOf(initialAppsScriptUrl) }
    var importanceWeight by remember { mutableStateOf(initialImportanceWeight) }
    var topN by remember { mutableStateOf(initialTopN) }
    var lastList by remember { mutableStateOf(initialLastList) }
    var excludedCategories by remember { mutableStateOf(initialExcludedCategories) }
    var editingItemId by remember { mutableStateOf<String?>(null) }
    // Find Task: which list's page it was opened from (null = the header button, every selected
    // category), and the not-yet-activated task whose priority dialog is open (with its draft values).
    var findTaskList by remember { mutableStateOf<String?>(null) }
    var activatingItem by remember { mutableStateOf<ToDoItem?>(null) }
    fun savedSettingsDraft() = SettingsDraft(sheetUrl, appsScriptUrl, priorityTiltFromWeight(importanceWeight), topN, excludedCategories)
    var settingsDraft by remember { mutableStateOf(savedSettingsDraft()) }
    // True while Save Settings is waiting on the sync its connection change started.
    var savingSync by remember { mutableStateOf(false) }
    // Shown under the connection fields: the Save-triggered sync's outcome, or a note about a scan.
    var settingsMessage by remember { mutableStateOf("") }
    val items by TaskStore.items.collectAsState()
    val lists by TaskStore.lists.collectAsState()
    val stuckCount by TaskStore.stuckCount.collectAsState()
    val lastSyncMessage by TaskStore.lastSyncMessage.collectAsState()
    val candidates by TaskStore.candidates.collectAsState()
    val syncing by TaskStore.syncing.collectAsState()
    val lastSyncFailed by TaskStore.lastSyncFailed.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    // A tab with nothing currently referred to it doesn't get a carousel page at all - only lists
    // that actually have a live item show up, so swiping only ever lands on something. `lists`
    // itself (every known Sheet tab) still distinguishes "never synced" from "synced, nothing
    // referred" for the empty-state message.
    val visibleLists = computeVisibleLists(lists, items)

    // Sync on every foreground entry - a cold launch, returning from MicroTasking or any other
    // app, unlocking the screen - showing the saved copy until the read corrects it.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) TaskStore.requestSync(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun openSettings() {
        settingsDraft = savedSettingsDraft()
        settingsMessage = ""
        screen = Screen.SETTINGS
    }

    // Find Task shows what the last sync saw and refreshes it: the Sheet may have changed since.
    fun openFindTask(list: String?) {
        findTaskList = list
        screen = Screen.FIND_TASK
        TaskStore.requestSync(context)
    }

    BackHandler(enabled = screen == Screen.FIND_TASK) { screen = Screen.CAROUSEL }

    // Saves everything; a changed Sheet or Web App URL also resyncs (a different Sheet first
    // discards everything local to the old one), staying on Settings with the error if that fails.
    fun saveSettings(draft: SettingsDraft) {
        val newSheetUrl = draft.sheetUrl.trim()
        val newAppsScriptUrl = draft.appsScriptUrl.trim()
        val sheetSwitched = isDifferentSheet(sheetUrl, newSheetUrl)
        val connectionChanged = newSheetUrl != sheetUrl || newAppsScriptUrl != appsScriptUrl
        importanceWeight = weightFromPriorityTilt(draft.priorityTilt)
        onImportanceWeightSaved(importanceWeight)
        topN = draft.topN
        onTopNSaved(topN)
        excludedCategories = draft.excludedCategories
        onExcludedCategoriesSaved(excludedCategories)
        sheetUrl = newSheetUrl
        onSheetUrlSaved(newSheetUrl)
        appsScriptUrl = newAppsScriptUrl
        onAppsScriptUrlSaved(newAppsScriptUrl)
        if (!connectionChanged) {
            screen = Screen.CAROUSEL
            return
        }
        savingSync = true
        settingsMessage = ""
        coroutineScope.launch {
            if (sheetSwitched) {
                TaskStore.switchSheet(context)
                lastList = null
            }
            val result = TaskStore.syncAndWait(context)
            savingSync = false
            settingsMessage = result.message
            if (result is SyncResult.Success) screen = Screen.CAROUSEL
        }
    }

    when (screen) {
        Screen.QR_SCANNER -> QrScannerScreen(
            onResult = { scanned ->
                screen = Screen.SETTINGS
                // Fills the draft only - nothing is saved or synced until Save Settings, so Cancel
                // still backs out. Each scanned line is classified by what it looks like, and a
                // field the scan didn't carry is left as it was.
                val payload = parseSetupQr(scanned)
                settingsDraft = settingsDraft.copy(
                    sheetUrl = payload.sheetUrl ?: settingsDraft.sheetUrl,
                    appsScriptUrl = payload.webAppUrl ?: settingsDraft.appsScriptUrl
                )
                settingsMessage = if (payload.sheetUrl == null && payload.webAppUrl == null) {
                    "That QR code was empty - nothing was changed."
                } else {
                    "Scanned. Press Save Settings to connect."
                }
            },
            onCancel = { screen = Screen.SETTINGS }
        )
        Screen.SETTINGS -> SettingsScreen(
            draft = settingsDraft,
            onDraftChange = { settingsDraft = it },
            openSection = openSettingsSection,
            onOpenSectionChange = { openSettingsSection = it },
            onScanQr = { screen = Screen.QR_SCANNER },
            saving = savingSync,
            statusMessage = when {
                savingSync -> "Syncing…"
                settingsMessage.isNotBlank() -> settingsMessage
                else -> lastSyncMessage
            },
            canGoBack = lists.isNotEmpty(),
            onCancel = { screen = Screen.CAROUSEL },
            onSave = { saveSettings(it) },
            availableCategories = lists
        )
        Screen.FIND_TASK -> FindTaskScreen(
            listName = findTaskList,
            candidates = findTaskCandidates(candidates, findTaskList, excludedCategories),
            hasSyncedLists = lists.isNotEmpty(),
            allCategoriesExcluded = lists.isNotEmpty() && lists.all { it in excludedCategories },
            syncing = syncing,
            syncError = if (lastSyncFailed) lastSyncMessage else "",
            onBack = { screen = Screen.CAROUSEL },
            // Starts the same Priority & progress dialog an item gets, with the marker at the
            // center; nothing is activated until that dialog's Activate button.
            onActivate = { activatingItem = it.copy(importance = 0.5f, urgency = 0.5f) }
        )
        Screen.CAROUSEL -> CarouselScreen(
            hasSyncedLists = lists.isNotEmpty(),
            visibleLists = visibleLists,
            items = items,
            importanceWeight = importanceWeight,
            topN = topN,
            initialList = initialListName(visibleLists, items, importanceWeight, lastList),
            waitingChanges = stuckCount,
            onListOpened = { list ->
                lastList = list
                onLastListSaved(list)
            },
            onOpenSettings = { openSettings() },
            onFindTask = { openFindTask(it) },
            onAdjustItem = { editingItemId = it },
            onCompleteForNow = { item -> TaskStore.completeItem(context, item, fully = false) },
            onFullyComplete = { item -> TaskStore.completeItem(context, item, fully = true) }
        )
    }

    activatingItem?.let { draft ->
        ItemDetailDialog(
            item = draft,
            confirmLabel = "Activate",
            onCancel = { activatingItem = null },
            onDismiss = {
                TaskStore.activateItem(context, draft)
                activatingItem = null
            },
            onPriorityChange = { importance, urgency -> activatingItem = draft.copy(importance = importance, urgency = urgency) },
            onProgressChange = { progress -> activatingItem = draft.copy(progress = progress) }
        )
    }

    val editingItem = items.find { it.id == editingItemId }
    if (editingItem != null) {
        // The priority as the dialog opened, so closing it only queues a Sheet write if the
        // matrix actually moved - not one per drag event.
        val openedPriority = remember(editingItem.id) { editingItem.importance to editingItem.urgency }
        ItemDetailDialog(
            item = editingItem,
            onDismiss = {
                if ((editingItem.importance to editingItem.urgency) != openedPriority) {
                    TaskStore.commitPriority(context, editingItem.id)
                }
                editingItemId = null
            },
            onPriorityChange = { importance, urgency ->
                TaskStore.updateItemLocally(context, editingItem.id) { it.copy(importance = importance, urgency = urgency) }
            },
            onProgressChange = { progress ->
                TaskStore.updateItemLocally(context, editingItem.id) { it.copy(progress = progress) }
            }
        )
    }
}

// The Priority & Lists slider's five stops, step -2 (full "Importance" side) .. +2 (full
// "Urgency" side), each just doubling/halving the previous stop's weight - 0 is exactly 1f, so
// centering the slider always means "equal", regardless of how DEFAULT_IMPORTANCE_WEIGHT is set.
private val PRIORITY_TILT_WEIGHTS = mapOf(-2 to 4f, -1 to 2f, 0 to 1f, 1 to 0.5f, 2 to 0.25f)

/** Snaps a persisted (possibly legacy/continuous) importanceWeight to the nearest slider stop. */
private fun priorityTiltFromWeight(weight: Float): Float =
    PRIORITY_TILT_WEIGHTS.entries.minByOrNull { (_, stopWeight) -> kotlin.math.abs(stopWeight - weight) }!!.key.toFloat()

private fun weightFromPriorityTilt(tilt: Float): Float =
    PRIORITY_TILT_WEIGHTS.getValue(tilt.roundToInt().coerceIn(-2, 2))

/** Font size (sp) for one side's label at slider step [step] (-2..2) - biggest at -2, smallest at +2. */
private fun priorityTiltFontSize(step: Int): Int = 22 - step.coerceIn(-2, 2) * 4

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    draft: SettingsDraft,
    onDraftChange: (SettingsDraft) -> Unit,
    openSection: String,
    onOpenSectionChange: (String) -> Unit,
    onScanQr: () -> Unit,
    saving: Boolean,
    statusMessage: String,
    canGoBack: Boolean,
    onCancel: () -> Unit,
    onSave: (SettingsDraft) -> Unit,
    availableCategories: List<String>
) {
    // Every field edits [draft], owned by the caller: nothing is saved until Save Settings (Cancel
    // just discards it), and a QR scan fills the same draft. Saving with changed connection
    // details syncs - there is no separate Sync button (SPEC.md "Synchronization").
    // Accordion: at most one section open at a time. "" means all collapsed. Same pattern as
    // MicroTasking's SettingsScreen - keep the two in sync stylistically. Hoisted up to
    // ActiveTasksApp (not a local `remember` here) so it survives the round trip through the QR
    // scanner screen instead of collapsing back to its default in between the two QR scans - see
    // the call site. Google Sheet Connection only opens by default for a not-yet-connected setup;
    // once connected, it's not the section people usually want, so nothing pre-opens, but once the
    // user opens (or closes) a section themselves that choice sticks until they change it again.

    @Composable
    fun sectionHeader(title: String) {
        val expanded = openSection == title
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenSectionChange(if (expanded) "" else title) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand"
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                if (canGoBack) {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            }
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        sectionHeader("Task Categories")
                        if (openSection == "Task Categories") {
                            Text(
                                "Choose which task categories the Find Task button in the header searches " +
                                    "for tasks to activate. This is separate from MicroTasking's own choice - " +
                                    "each app looks at the categories you pick in it. The Find Task button on " +
                                    "a list's own page always searches that list.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (availableCategories.isEmpty()) {
                                Text(
                                    "No categories yet. Connect your Google Sheet below to add categories (one per tab).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // Bounded + independently scrollable so a large category list never
                            // pushes the rest of the screen off-screen (same as MicroTasking's).
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 320.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                availableCategories.forEach { category ->
                                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = category !in draft.excludedCategories,
                                            onCheckedChange = { checked ->
                                                onDraftChange(
                                                    draft.copy(
                                                        excludedCategories =
                                                            if (checked) draft.excludedCategories - category
                                                            else draft.excludedCategories + category
                                                    )
                                                )
                                            }
                                        )
                                        Text(category)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        sectionHeader("Priority & Lists")
                        if (openSection == "Priority & Lists") {
                            Text(
                                "How your lists weigh importance against urgency. Slide toward " +
                                    "whichever should count for more - the middle weighs them the same.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // No numbers, no formula - which word is bigger IS the setting.
                            val tiltStep = draft.priorityTilt.roundToInt().coerceIn(-2, 2)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Bottom
                            ) {
                                Text("Importance", fontSize = priorityTiltFontSize(tiltStep).sp)
                                Text("Urgency", fontSize = priorityTiltFontSize(-tiltStep).sp)
                            }
                            Slider(
                                value = draft.priorityTilt,
                                onValueChange = { onDraftChange(draft.copy(priorityTilt = it)) },
                                valueRange = -2f..2f,
                                steps = 3
                            )
                            Text("Items per list: ${draft.topN}", style = MaterialTheme.typography.labelLarge)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(onClick = { if (draft.topN > 1) onDraftChange(draft.copy(topN = draft.topN - 1)) }) { Text("−") }
                                Text("${draft.topN}", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
                                OutlinedButton(onClick = { if (draft.topN < 10) onDraftChange(draft.copy(topN = draft.topN + 1)) }) { Text("+") }
                            }
                        }
                    }
                }
            }

            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        sectionHeader("Google Sheet Connection")
                        if (openSection == "Google Sheet Connection") {
                            Text(
                                "Your tasks live in a Google Sheet you own. Paste its URL, or scan the Sheet " +
                                    "QR code from the onboarding page, so the app can read it - each tab " +
                                    "becomes a list.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            ConnectionUrlField(
                                value = draft.sheetUrl,
                                onValueChange = { onDraftChange(draft.copy(sheetUrl = it)) },
                                label = "Google Sheet URL",
                                placeholder = "https://docs.google.com/spreadsheets/d/...",
                                check = ::checkSheetUrl
                            )
                            Button(onClick = onScanQr, modifier = Modifier.fillMaxWidth()) {
                                Text("Scan Sheet QR Code")
                            }

                            Text(
                                "The Web App is a small script inside your Sheet that lets the app write back " +
                                    "to it - activating, completing and re-prioritizing tasks. Deploy it once from your " +
                                    "Sheet (Extensions > Apps Script > Deploy > New deployment > Web app), " +
                                    "then paste its URL or scan its QR code from the onboarding page.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            ConnectionUrlField(
                                value = draft.appsScriptUrl,
                                onValueChange = { onDraftChange(draft.copy(appsScriptUrl = it)) },
                                label = "Apps Script Web App URL",
                                placeholder = "https://script.google.com/macros/s/.../exec",
                                check = ::checkWebAppUrl
                            )
                            // Both scan buttons open the same scanner; the result is routed by what the
                            // scanned text looks like (parseSetupQr), never by which button was pressed.
                            Button(onClick = onScanQr, modifier = Modifier.fillMaxWidth()) {
                                Text("Scan Web App QR Code")
                            }

                            if (statusMessage.isNotBlank()) {
                                Text(
                                    statusMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        sectionHeader("About")
                        if (openSection == "About") {
                            Text(
                                "v${BuildConfig.VERSION_BASE}-${BuildConfig.BUILD_NUMBER}",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                "${BuildConfig.BUILD_TIMESTAMP} - ${BuildConfig.GIT_SHORT_SHA} - ${BuildConfig.GIT_BRANCH}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
        Surface(shadowElevation = 4.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .navigationBarsPadding(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(modifier = Modifier.weight(1f), enabled = canGoBack && !saving, onClick = onCancel) {
                    Text("Cancel")
                }
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !saving,
                    onClick = { onSave(draft) }
                ) {
                    Text(if (saving) "Syncing…" else "Save Settings")
                }
            }
        }
    }
}

/**
 * One Settings > Google Sheet Connection URL box, same in MicroTasking. Collapsed it is a single
 * line; hovering it (mouse/stylus/ChromeOS) or focusing it expands it to show the entire URL, and
 * focusing (a tap or click) also selects all of it. Under it: what [check] says - an error for a
 * wrong kind of URL, or a link showing just the URL's hash portion that opens the complete URL.
 * Advisory only - it never blocks saving.
 */
@Composable
private fun ConnectionUrlField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    check: (String) -> UrlCheck
) {
    val context = LocalContext.current
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    // The text is the caller's; only the selection lives here, so select-all can be applied. A
    // change from outside (a QR scan filling the draft) shows up as a fresh value, caret at the end.
    var fieldValue by remember { mutableStateOf(TextFieldValue(value)) }
    val shown = if (fieldValue.text == value) fieldValue else TextFieldValue(value, TextRange(value.length))
    val expanded = hovered || focused
    // Selected a moment after focus arrives: the tap that focuses the field also places the caret,
    // and would otherwise land after (and undo) the selection.
    LaunchedEffect(focused) {
        if (focused) {
            delay(80)
            fieldValue = TextFieldValue(value, TextRange(0, value.length))
        }
    }
    val result = check(value)
    val supporting: (@Composable () -> Unit)? = when (result) {
        UrlCheck.Blank -> null
        is UrlCheck.Invalid -> ({ Text(result.message, style = MaterialTheme.typography.bodySmall) })
        is UrlCheck.Valid -> ({
            Text(
                result.id,
                modifier = Modifier.clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.target))) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        })
    }
    OutlinedTextField(
        value = shown,
        onValueChange = {
            // A URL has no line breaks: strip any (a paste can carry one) rather than let Enter add one.
            fieldValue = if (it.text.any { c -> c == '\n' || c == '\r' }) {
                val text = it.text.filterNot { c -> c == '\n' || c == '\r' }
                TextFieldValue(text, TextRange(text.length))
            } else it
            onValueChange(fieldValue.text)
        },
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(hoverSource)
            .onFocusChanged { focused = it.hasFocus },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        // Not singleLine, so the expanded state can wrap to show the whole URL; collapsed, one line.
        singleLine = false,
        maxLines = if (expanded) 8 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        isError = result is UrlCheck.Invalid,
        supportingText = supporting
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CarouselScreen(
    hasSyncedLists: Boolean,
    visibleLists: List<String>,
    items: List<ToDoItem>,
    importanceWeight: Float,
    topN: Int,
    initialList: String?,
    waitingChanges: Int,
    onListOpened: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onFindTask: (list: String?) -> Unit,
    onAdjustItem: (String) -> Unit,
    onCompleteForNow: (ToDoItem) -> Unit,
    onFullyComplete: (ToDoItem) -> Unit
) {
    val pagerState = rememberPagerState(
        initialPage = visibleLists.indexOf(initialList).coerceAtLeast(0),
        pageCount = { visibleLists.size }
    )
    // Only a page the user actually swiped to counts as "last opened"; the initial page is just
    // where we started (possibly the highest-priority fallback), so it isn't recorded.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .drop(1)
            .collect { page -> visibleLists.getOrNull(page)?.let(onListOpened) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ActiveTasks") },
                actions = {
                    // Find Task across every category ticked in Settings > Task Categories.
                    IconButton(onClick = { onFindTask(null) }) {
                        Icon(Icons.Filled.AddCircle, contentDescription = "Find task", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        // A list with nothing currently referred to it gets no page at all (see visibleLists in
        // ActiveTasksApp) - only "never synced" vs "synced, but nothing referred anywhere" needs
        // distinguishing here, since either way there's nothing to swipe through.
        if (visibleLists.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (hasSyncedLists) "No active tasks yet. Tap the + button above to find one."
                    else "No lists yet. Open Settings and connect your Google Sheet to get started.",
                    style = MaterialTheme.typography.bodyMedium
                )
                WaitingChangesNote(waitingChanges, Modifier.padding(top = 8.dp))
            }
            return@Scaffold
        }
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Sub-header under the "ActiveTasks" app-bar title: which list is currently showing
            // (e.g. "Must Do", "Alice"), MicroTasking-style page heading.
            Text(
                visibleLists.getOrNull(pagerState.currentPage) ?: "",
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp),
                style = MaterialTheme.typography.headlineMedium
            )
            if (visibleLists.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    visibleLists.forEachIndexed { index, _ ->
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
            WaitingChangesNote(waitingChanges, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val listName = visibleLists[page]
                // distinctBy is cheap insurance against a duplicate id (two Sheet rows with
                // identical description text and no Task ID yet) hard-crashing this LazyColumn's
                // key-by-id below - see reconcileWithSheet, which is the primary place this is
                // supposed to already be prevented.
                val topItems = sortedForDisplay(items.filter { it.list == listName && !it.done }.distinctBy { it.id }, importanceWeight).take(topN)
                Box(modifier = Modifier.fillMaxSize()) {
                if (topItems.isEmpty()) {
                    // Reachable mid-session: completing this list's last item removes it from
                    // visibleLists on the next recomposition, but the pager can briefly still be
                    // sitting on this now-empty page in between.
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Nothing active in \"$listName\" yet.", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        // Room below the last card for the Find Task button floating over it.
                        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(topItems, key = { it.id }) { toDoItem ->
                            ToDoItemRow(
                                item = toDoItem,
                                onAdjust = { onAdjustItem(toDoItem.id) },
                                onCompleteForNow = { onCompleteForNow(toDoItem) },
                                onFullyComplete = { onFullyComplete(toDoItem) }
                            )
                        }
                    }
                }
                // Find Task for just this list (its tab), bottom right of each mini list screen.
                FloatingActionButton(
                    onClick = { onFindTask(listName) },
                    shape = CircleShape,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Find task in $listName")
                }
                }
            }
        }
    }
}

/**
 * Find Task: the enabled-but-not-yet-activated Sheet rows (importance/urgency still empty) for one
 * list, or for every category ticked in Settings when [listName] is null, each with an Activate
 * button that opens the Priority & progress dialog. Activating writes the priority to the Sheet's
 * hidden columns, so it is independent of what MicroTasking does with the same row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindTaskScreen(
    listName: String?,
    candidates: List<ToDoItem>,
    hasSyncedLists: Boolean,
    allCategoriesExcluded: Boolean,
    syncing: Boolean,
    syncError: String,
    onBack: () -> Unit,
    onActivate: (ToDoItem) -> Unit
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Find Task") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                listName ?: "All selected categories",
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp),
                style = MaterialTheme.typography.headlineMedium
            )
            if (syncing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (syncError.isNotBlank()) {
                Text(
                    syncError,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (candidates.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        when {
                            !hasSyncedLists -> "No lists yet. Open Settings and connect your Google Sheet to get started."
                            listName == null && allCategoriesExcluded ->
                                "No task categories are selected. Choose some in Settings > Task Categories."
                            syncing -> "Looking…"
                            listName != null -> "Every enabled task in \"$listName\" is already active."
                            else -> "Every enabled task in your selected categories is already active."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(candidates, key = { it.id }) { candidate ->
                        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                if (listName == null) {
                                    Text(
                                        candidate.list,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(candidate.description, style = MaterialTheme.typography.bodyLarge)
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (candidate.link.isNotBlank()) {
                                        OutlinedButton(
                                            onClick = {
                                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(candidate.link))) }
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Open link") }
                                    }
                                    Button(onClick = { onActivate(candidate) }, modifier = Modifier.weight(1f)) {
                                        Text("Activate")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * "N changes waiting to reach your Sheet": shown only while the pending-changes queue still holds
 * something after a failed send (offline, Web App down) - otherwise sync stays invisible.
 */
@Composable
private fun WaitingChangesNote(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Text(
        if (count == 1) "1 change waiting to reach your Sheet" else "$count changes waiting to reach your Sheet",
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
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
    onAdjust: () -> Unit,
    onCompleteForNow: () -> Unit,
    onFullyComplete: () -> Unit
) {
    val context = LocalContext.current
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(item.description, style = MaterialTheme.typography.bodyLarge)
            if (item.link.isNotBlank()) {
                OutlinedButton(
                    onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.link))) }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Open link") }
            }
            // The bottom two rows: "Progress: N%" beside its Priority & progress button, then
            // Complete (for now) beside Fully complete underneath.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Progress: ${item.progress}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = onAdjust) {
                    Text("Priority & progress")
                }
            }
            // Both take effect at once; the Sheet write is queued and retried until it lands.
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCompleteForNow, modifier = Modifier.weight(1f)) {
                    Text("Complete (for now)")
                }
                Button(onClick = onFullyComplete, modifier = Modifier.weight(1f)) {
                    Text("Fully complete")
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

/**
 * Re-triage (priority matrix) and progress for an item. The priority is written back to the Sheet
 * when the dialog closes (queued, see [TaskStore.commitPriority]); progress stays local.
 *
 * Also the activation dialog for Find Task: there [onCancel] is set, so the confirm button
 * ([confirmLabel], "Activate") is the only thing that calls [onDismiss] and a tap outside or
 * Cancel backs out without activating anything.
 */
@Composable
fun ItemDetailDialog(
    item: ToDoItem,
    onDismiss: () -> Unit,
    onPriorityChange: (importance: Float, urgency: Float) -> Unit,
    onProgressChange: (Int) -> Unit,
    confirmLabel: String = "Done",
    onCancel: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onCancel ?: onDismiss,
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
            Button(onClick = onDismiss) { Text(confirmLabel) }
        },
        dismissButton = onCancel?.let { cancel -> { OutlinedButton(onClick = cancel) { Text("Cancel") } } }
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
