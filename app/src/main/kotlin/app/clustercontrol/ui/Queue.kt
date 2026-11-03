package app.clustercontrol.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.clustercontrol.Actions
import app.clustercontrol.UiState
import app.clustercontrol.core.ConfigCell
import app.clustercontrol.core.JobEntry
import app.clustercontrol.core.JobNames
import app.clustercontrol.core.Preset
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Stable lazy-list keys: the entry line, numbered when the same entry is queued twice. */
fun stableKeys(queue: List<JobEntry>): List<String> {
    val seen = HashMap<String, Int>()
    return queue.map { e -> e.toLine().let { l -> "$l#${seen.merge(l, 1, Int::plus)}" } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(ui: UiState, actions: Actions, snackbar: SnackbarHostState, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(ui.queue) }
    // the screen keeps its own copy so a drag never waits for the state to flow back
    LaunchedEffect(ui.queue) { items = ui.queue }
    LaunchedEffect(Unit) { actions.loadCatalog() }
    DisposableEffect(Unit) { onDispose { actions.queueFlush() } }

    var sheet by rememberSaveable { mutableStateOf<Int?>(null) }
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    val lazy = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(lazy) { from, to ->
        if (from.index < items.size && to.index < items.size) {
            items = items.toMutableList().also { it.add(to.index, it.removeAt(from.index)) }
            actions.queueMove(from.index, to.index)
        }
    }
    val keys = remember(items) { stableKeys(items) }

    Column(modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header("Coda") {
            LinkText("Preset") { sheet = 1 }
            LinkText(if (ui.snapshot?.stopped == true) "Riprendi" else "Pausa") { actions.pauseResume() }
            Box {
                var menu by remember { mutableStateOf(false) }
                IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Altro", tint = C.Accent) }
                DropdownMenu(menu, { menu = false }, containerColor = Color.White) {
                    DropdownMenuItem({ Text("Kill job attivo", color = C.Red) }, { menu = false; confirm = "kill" })
                    DropdownMenuItem({ Text("Svuota coda", color = C.Red) }, { menu = false; confirm = "clear" })
                }
            }
        }
        PullToRefreshBox(ui.refreshing, actions::refresh, Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), lazy, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(items, key = { i, _ -> keys[i] }) { index, entry ->
                    ReorderableItem(reorder, key = keys[index]) { dragging ->
                        val lift by animateDpAsState(if (dragging) 10.dp else 0.dp, label = "lift")
                        // the state is created once: read the current index, not the one of the first composition
                        val current by rememberUpdatedState(index)
                        val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = {
                            if (it == SwipeToDismissBoxValue.EndToStart) {
                                val i = current
                                val title = JobNames.of(items[i]).title
                                val removed = actions.queueRemove(i)
                                items = items.toMutableList().also { l -> l.removeAt(i) }
                                scope.launch {
                                    val r = snackbar.showSnackbar("Rimosso $title", "Annulla", duration = SnackbarDuration.Short)
                                    if (r == SnackbarResult.ActionPerformed) actions.queueUndo(i, removed) else actions.queueFlush()
                                }
                                true
                            } else {
                                false
                            }
                        })
                        SwipeToDismissBox(
                            dismiss,
                            backgroundContent = {
                                Box(Modifier.fillMaxSize().background(C.Red, RoundedCornerShape(12.dp)), contentAlignment = Alignment.CenterEnd) {
                                    Text("Elimina", Modifier.padding(end = 20.dp), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            },
                            enableDismissFromStartToEnd = false,
                        ) {
                            Surface(shape = RoundedCornerShape(12.dp), color = C.Card, shadowElevation = lift) {
                                QueueRow(entry, index == 0, Modifier.longPressDraggableHandle(onDragStopped = { actions.queueFlush() })) {
                                    Grip(Modifier.draggableHandle(onDragStopped = { actions.queueFlush() }))
                                }
                            }
                        }
                    }
                }
                item(key = "add") {
                    Surface(Modifier.fillMaxWidth().clickable { sheet = 0 }, shape = RoundedCornerShape(12.dp), color = C.Card) {
                        Row(Modifier.padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Add, null, Modifier.size(20.dp), tint = C.Accent)
                            Text(if (items.isEmpty()) "Coda vuota: aggiungi job" else "Aggiungi job", color = C.Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }

    sheet?.let { start ->
        AddSheet(ui, actions, start) { sheet = null }
    }
    confirm?.let { what ->
        val kill = what == "kill"
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (kill) "Kill job attivo?" else "Svuota coda?") },
            confirmButton = {
                TextButton({
                    confirm = null
                    if (kill) actions.kill() else actions.clearQueue()
                }) { Text(if (kill) "Kill" else "Svuota", color = C.Red) }
            },
            dismissButton = { TextButton({ confirm = null }) { Text("Annulla") } },
            containerColor = Color.White,
        )
    }
}

@Composable
private fun QueueRow(entry: JobEntry, next: Boolean, modifier: Modifier, grip: @Composable () -> Unit) {
    val t = JobNames.of(entry)
    Row(
        modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(t.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.subtitle.isNotEmpty()) Caption(t.subtitle, Modifier.padding(top = 1.dp))
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Chip(entry.type.uppercase(), if (entry.type == "train") Tone.BLUE else Tone.GREY)
            if (next) Chip("prossimo", Tone.GREEN)
        }
        grip()
    }
}

@Composable
private fun Grip(modifier: Modifier) {
    Canvas(modifier.size(width = 24.dp, height = 32.dp)) {
        val r = 1.8.dp.toPx()
        for (cx in listOf(size.width / 2 - 4.dp.toPx(), size.width / 2 + 4.dp.toPx())) {
            for (cy in listOf(size.height / 2 - 7.dp.toPx(), size.height / 2, size.height / 2 + 7.dp.toPx())) {
                drawCircle(Color(0xFFB4B8C0), r, androidx.compose.ui.geometry.Offset(cx, cy))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(ui: UiState, actions: Actions, start: Int, onClose: () -> Unit) {
    var tab by remember { mutableStateOf(start) }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = C.Bg,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.8f).navigationBarsPadding().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Segmented(listOf("Job", "Preset"), tab, { tab = it })
            when {
                ui.catalogLoading && ui.catalog.isEmpty() -> Caption("Caricamento...")
                ui.catalogError != null -> Caption(ui.catalogError)
                tab == 0 -> JobPicker(ui.catalog, actions, onClose)
                else -> PresetPicker(ui.presets, actions, onClose)
            }
        }
    }
}

@Composable
private fun ColumnScope.JobPicker(catalog: List<ConfigCell>, actions: Actions, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(catalog, query) {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        catalog.filter { c ->
            val t = JobNames.parse(c.name)
            val hay = "${t.title} ${t.subtitle} ${c.name}".lowercase()
            words.all { it in hay }
        }
    }
    OutlinedTextField(
        query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Cerca") }, singleLine = true,
        shape = RoundedCornerShape(12.dp),
    )
    LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(shown, key = { it.path }) { cell ->
            val t = JobNames.parse(cell.name)
            Surface(shape = RoundedCornerShape(12.dp), color = C.Card) {
                Row(Modifier.fillMaxWidth().padding(start = 13.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(t.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (t.subtitle.isNotEmpty()) Caption(t.subtitle, Modifier.padding(top = 1.dp))
                    }
                    SmallBtn("Train", true) { actions.addJob(cell, "train"); onClose() }
                    SmallBtn("Eval", false) { actions.addJob(cell, "eval"); onClose() }
                }
            }
        }
    }
}

@Composable
private fun SmallBtn(text: String, filled: Boolean, onClick: () -> Unit) {
    Text(
        text,
        Modifier.background(if (filled) C.Accent else C.AccentSoft, RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (filled) Color.White else C.Accent,
    )
}

@Composable
private fun ColumnScope.PresetPicker(presets: List<Preset>, actions: Actions, onClose: () -> Unit) {
    var chosen by remember { mutableStateOf(listOf<String>()) }
    var startNow by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(presets, key = { it.id }) { p ->
            val order = chosen.indexOf(p.id)
            Surface(
                Modifier.fillMaxWidth().clickable { chosen = if (order >= 0) chosen - p.id else chosen + p.id },
                shape = RoundedCornerShape(12.dp), color = C.Card,
            ) {
                Row(Modifier.padding(horizontal = 13.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(p.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Caption("${p.jobs.size} job" + p.description.takeIf { it.isNotEmpty() }?.let { " · ${it.lineSequence().first()}" }.orEmpty(), Modifier.padding(top = 1.dp))
                    }
                    Text(
                        if (order >= 0) "${order + 1}" else "",
                        Modifier.size(24.dp).background(if (order >= 0) C.Accent else C.Track, androidx.compose.foundation.shape.CircleShape).padding(top = 3.dp),
                        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Avvia subito", Modifier.weight(1f), fontSize = 14.sp)
        GreenSwitch(startNow) { startNow = it }
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val picked = chosen.mapNotNull { id -> presets.firstOrNull { it.id == id } }
        Btn("Aggiungi in coda", { actions.addPresets(picked, false, startNow); onClose() }, Modifier.weight(1f), enabled = picked.isNotEmpty())
        Btn("Sostituisci coda", { actions.addPresets(picked, true, startNow); onClose() }, Modifier.weight(1f), filled = false, enabled = picked.isNotEmpty())
    }
}
