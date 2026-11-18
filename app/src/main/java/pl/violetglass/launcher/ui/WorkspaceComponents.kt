package pl.violetglass.launcher.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.violetglass.launcher.settings.LabelColor
import pl.violetglass.launcher.settings.GlassTarget
import pl.violetglass.launcher.settings.LauncherPreferences
import pl.violetglass.launcher.R
import pl.violetglass.launcher.apps.*
import pl.violetglass.launcher.workspace.*
import pl.violetglass.launcher.pet.registerPlatformSurface

internal enum class WorkspaceLabelBackdrop { SURFACE, PHOTO, DARK }
internal val LocalWorkspaceLabelBackdrop = compositionLocalOf { WorkspaceLabelBackdrop.SURFACE }

@Composable
private fun workspaceLabelStyle(prefs: LauncherPreferences) = MaterialTheme.typography.bodyLarge.copy(
    fontSize = prefs.labelSize.sp,
    lineHeight = (prefs.labelSize * 1.35f).sp,
    platformStyle = PlatformTextStyle(includeFontPadding = true),
)

@Composable
internal fun workspaceLabelHeight(prefs: LauncherPreferences): androidx.compose.ui.unit.Dp {
    if(!prefs.labels) return 0.dp
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = workspaceLabelStyle(prefs)
    return remember(measurer, style, density) {
        with(density) { measurer.measure("ĄgÉy", style = style, maxLines = 1).size.height.toDp() } + 2.dp
    }
}

internal fun workspaceCellMinimumHeight(prefs: LauncherPreferences, width: androidx.compose.ui.unit.Dp,
    labelHeight: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp {
    val padding = (prefs.gap * 2 + 6).dp
    val icon = minOf(prefs.iconSize.dp, (width - padding).coerceAtLeast(1.dp))
    return maxOf(48.dp, icon + (if(prefs.labels) labelHeight + 3.dp else 0.dp) + padding)
}

@Composable
fun WorkspaceGrid(
    layout: LayoutState, page: Int, apps: Map<String, LauncherApp>, repository: AppsRepository,
    revision: Long, registry: DragRegistry, selected: Slot?, placing: Boolean,
    onSlot: (Slot) -> Unit, onLaunch: (LayoutItem) -> Unit, onMenu: (ItemPayload) -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefs = LocalLauncherPreferences.current
    BoxWithConstraints(modifier) {
        val cellWidth = maxWidth / layout.config.columns
        val labelHeight = workspaceLabelHeight(prefs)
        val gridHeight = maxOf(maxHeight, workspaceCellMinimumHeight(prefs, cellWidth, labelHeight) * layout.config.rows)
        val cellHeight = gridHeight / layout.config.rows
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(gridHeight)) {
                repeat(layout.config.capacity) { position ->
                    val slot = Slot(pageContainer(page), position)
                    val occupant = layout.at(slot.container, position)
                    val item = occupant?.takeIf { !it.isWidget && it.position == position }
                    WorkspaceCell(item, slot, layout, apps, repository, revision, registry,
                        selected == slot, placing, onSlot, onLaunch, onMenu,
                        Modifier.offset(cellWidth * (position % layout.config.columns), cellHeight * (position / layout.config.columns))
                            .size(cellWidth, cellHeight))
                }
                layout.items.filter { it.isWidget && it.container == pageContainer(page) }.forEach { widget ->
                    key(widget.id, widget.widgetId) {
                        HostedWidget(widget, registry, revision, onMenu,
                            Modifier.offset(cellWidth * (widget.position % layout.config.columns), cellHeight * (widget.position / layout.config.columns))
                                .size(cellWidth * widget.spanX, cellHeight * widget.spanY).padding(prefs.gap.dp)
                                .then(if(widget.widgetId == null) Modifier else Modifier.registerPlatformSurface(
                                    if(widget.provider?.contains("search", ignoreCase = true) == true) "search:${widget.id}" else "widget:${widget.id}")))
                    }
                }
            }
        }
    }
}

@Composable
fun WorkspaceDock(
    layout: LayoutState, apps: Map<String, LauncherApp>, repository: AppsRepository, revision: Long,
    registry: DragRegistry, selected: Slot?, placing: Boolean, onSlot: (Slot) -> Unit,
    onLaunch: (LayoutItem) -> Unit, onMenu: (ItemPayload) -> Unit, compact: Boolean = false,
) {
    val prefs = LocalLauncherPreferences.current
    val dockLabel = stringResource(R.string.dock_label)
    val labelHeight = workspaceLabelHeight(prefs)
    // Compact mode may shrink the icon, but must still reserve a complete label
    // line at the current system font scale instead of imposing a 68 dp ceiling.
    val minimumIcon = (if(compact) minOf(prefs.iconSize, 32) else prefs.iconSize).dp
    val minimumHeight = minimumIcon + (if(prefs.labels) labelHeight + 3.dp else 0.dp) + (prefs.gap * 2 + 6).dp
    val dockContent: @Composable () -> Unit = {
        val preferredHeight = (if(compact) minOf(prefs.dockHeight, 68) else prefs.dockHeight).dp
        Row(Modifier.fillMaxWidth().height(maxOf(preferredHeight, minimumHeight)).padding(horizontal = 4.dp)) {
            repeat(layout.config.dockSlots) { position ->
                val slot = Slot(DOCK, position)
                WorkspaceCell(layout.at(DOCK, position), slot, layout, apps, repository, revision,
                    registry, selected == slot, placing, onSlot, onLaunch, onMenu,
                    Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
    if (prefs.dockBackground) GlassSurface(GlassTarget.DOCK, Modifier.semantics { contentDescription = dockLabel }.registerPlatformSurface("dock:bottom"), content = dockContent)
    else Box(Modifier.semantics { contentDescription = dockLabel }.registerPlatformSurface("dock:bottom")) { dockContent() }
}

@Composable
fun WorkspaceSideDock(
    layout: LayoutState, apps: Map<String, LauncherApp>, repository: AppsRepository, revision: Long,
    registry: DragRegistry, selected: Slot?, placing: Boolean, onSlot: (Slot) -> Unit,
    onLaunch: (LayoutItem) -> Unit, onMenu: (ItemPayload) -> Unit,
) {
    val prefs = LocalLauncherPreferences.current
    val columns = if (layout.config.dockSlots > 4) 2 else 1
    val rows = (layout.config.dockSlots + columns - 1) / columns
    val dockLabel = stringResource(R.string.dock_label)
    val minimumRow = maxOf(48.dp, 32.dp + (prefs.gap * 2 + 6).dp +
        if(prefs.labels) workspaceLabelHeight(prefs) + 3.dp else 0.dp)
    val content: @Composable () -> Unit = {
        BoxWithConstraints(Modifier.width(if (columns == 2) 152.dp else 84.dp).fillMaxHeight().testTag("landscapeDock")) {
            val scrollable = minimumRow * rows > maxHeight
            val scroll = if(scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier
            Column(Modifier.fillMaxSize().then(scroll)) {
                repeat(rows) { row ->
                    val rowModifier = if(scrollable) Modifier.height(minimumRow) else Modifier.weight(1f)
                    Row(rowModifier.fillMaxWidth()) {
                        repeat(columns) { column ->
                            val position = row * columns + column
                            if (position < layout.config.dockSlots) {
                                val slot = Slot(DOCK, position)
                                WorkspaceCell(layout.at(DOCK, position), slot, layout, apps, repository, revision,
                                    registry, selected == slot, placing, onSlot, onLaunch, onMenu,
                                    Modifier.weight(1f).fillMaxHeight().registerPlatformSurface("dock:slot:$position"))
                            } else Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
    if (prefs.dockBackground) GlassSurface(GlassTarget.DOCK, Modifier.semantics { contentDescription = dockLabel }, content = content)
    else Box(Modifier.semantics { contentDescription = dockLabel }) { content() }
}

@Composable
private fun WorkspaceCell(
    item: LayoutItem?, slot: Slot, layout: LayoutState, apps: Map<String, LauncherApp>,
    repository: AppsRepository, revision: Long, registry: DragRegistry, highlighted: Boolean,
    placing: Boolean, onSlot: (Slot) -> Unit, onLaunch: (LayoutItem) -> Unit,
    onMenu: (ItemPayload) -> Unit, modifier: Modifier,
) {
    val label = stringResource(R.string.slot_label, slot.position + 1)
    Box(
        modifier.padding(LocalLauncherPreferences.current.gap.dp).then(Modifier.targetBounds(registry, slot))
            .border(if (highlighted || placing) 1.dp else 0.dp,
                if (highlighted) MaterialTheme.colorScheme.primary else if (placing) MaterialTheme.colorScheme.outline else Color.Transparent,
                RoundedCornerShape(18.dp))
            .testTag("slot:${slot.container}:${slot.position}")
            .then(if (placing || item == null) Modifier.clickable(enabled = placing) { onSlot(slot) }
                .semantics { contentDescription = label } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (item != null) ItemTile(item, layout, apps, repository, revision, registry,
            onClick = { if (placing) onSlot(slot) else onLaunch(item) }, onMenu = onMenu,
            modifier = Modifier.fillMaxSize())
        else if (placing) Text("·", color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
fun ItemTile(
    item: LayoutItem, layout: LayoutState, apps: Map<String, LauncherApp>, repository: AppsRepository,
    revision: Long, registry: DragRegistry, onClick: () -> Unit, onMenu: (ItemPayload) -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefs = LocalLauncherPreferences.current
    val payload = ItemPayload(item.id, item.appKey, item.title)
    val platform = if(item.container.startsWith("page:")) Modifier.registerPlatformSurface("icon:${item.id}") else Modifier
    val options = stringResource(R.string.item_options)
    val overWallpaper = item.container.startsWith("page:") || item.container == DOCK && !prefs.dockBackground
    val backdrop = if(overWallpaper) LocalWorkspaceLabelBackdrop.current else WorkspaceLabelBackdrop.SURFACE
    val photoScrim = backdrop == WorkspaceLabelBackdrop.PHOTO && prefs.labelColor == LabelColor.AUTO
    Layout(
        modifier = modifier.then(Modifier.sourceBounds(registry, "item:${item.id}", payload))
            .testTag("item:${item.id}").clickable(onClick = onClick)
            .semantics { contentDescription = item.title; onLongClick(options) { onMenu(payload); true } }
            .padding(3.dp),
        content = {
            BoxWithConstraints(contentAlignment = Alignment.Center) {
                val iconSize = minOf(prefs.iconSize.dp, maxWidth, maxHeight)
                if(iconSize > 0.dp) {
                    if(item.isFolder) {
                        val children = layout.children(item.id).take(4)
                        val counts = LocalBadgeCounts.current
                        val count = layout.children(item.id).mapNotNull { apps[it.appKey]?.component?.packageName }
                            .distinct().sumOf { counts[it] ?: 0 }
                        WithNotificationBadge(count, Modifier.size(iconSize).then(platform)) {
                            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.size(iconSize)) {
                                Column(Modifier.padding(3.dp)) {
                                    repeat(2) { row ->
                                        Row(Modifier.weight(1f)) {
                                            repeat(2) { col ->
                                                val child = children.getOrNull(row * 2 + col)
                                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                                    if(child != null) AppIcon(apps[child.appKey], child.title, repository,
                                                        revision, (iconSize / 2 - 5.dp).coerceAtLeast(1.dp), showBadge = false)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else AppIcon(apps[item.appKey], item.title, repository, revision, iconSize, modifier = platform)
                }
            }
            if(prefs.labels) Text(item.title,
                modifier = Modifier.then(if(photoScrim) Modifier.background(Color.Black.copy(alpha = .64f),
                    RoundedCornerShape(5.dp)).padding(horizontal = 2.dp) else Modifier)
                    .testTag("itemLabel:${item.id}"),
                color = labelTint(prefs.labelColor, backdrop),
                style = workspaceLabelStyle(prefs).copy(shadow = if(backdrop == WorkspaceLabelBackdrop.PHOTO && !photoScrim &&
                    prefs.labelColor != LabelColor.BLACK) Shadow(Color.Black, Offset(1f, 1f), 4f) else null),
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        },
    ) { children, constraints ->
        val available = constraints.copy(minWidth = 0, minHeight = 0)
        // Measure the actual label first, so an oversized icon yields space to the text.
        val label = children.getOrNull(1)?.measure(available)
        val labelSpace = label?.height ?: 0
        val gap = if(label != null) 3.dp.roundToPx().coerceAtMost(available.maxHeight - labelSpace) else 0
        val icon = children.first().measure(available.copy(maxHeight = available.maxHeight - labelSpace - gap))
        val width = constraints.constrainWidth(maxOf(icon.width, label?.width ?: 0))
        val height = constraints.constrainHeight(icon.height + gap + labelSpace)
        val top = (height - icon.height - gap - labelSpace) / 2
        layout(width, height) {
            icon.placeRelative((width - icon.width) / 2, top)
            label?.placeRelative((width - label.width) / 2, top + icon.height + gap)
        }
    }
}

@Composable
fun AppDrawer(
    apps: List<LauncherApp>, repository: AppsRepository, revision: Long, registry: DragRegistry,
    onLaunch: (LauncherApp) -> Unit, onMenu: (ItemPayload) -> Unit, modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
) {
    val options = stringResource(R.string.item_options)
    val prefs = LocalLauncherPreferences.current
    LazyVerticalGrid(columns = GridCells.Fixed(prefs.drawerColumns), state = gridState, modifier = modifier.testTag("appsGrid"),
        contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(apps, key = { it.key }) { app ->
            val payload = ItemPayload(appKey = app.key, title = app.label)
            Column(Modifier.then(Modifier.sourceBounds(registry, "drawer:${app.key}", payload)).testTag("app:${app.key}")
                .clickable { onLaunch(app) }.semantics { onLongClick(options) { onMenu(payload); true } }
                .padding(vertical = 12.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AppIcon(app, app.label, repository, revision, prefs.iconSize.dp)
                Spacer(Modifier.height(8.dp))
                Text(app.label, fontSize = prefs.labelSize.sp, color = labelTint(prefs.labelColor), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun FolderOverlay(
    folder: LayoutItem, layout: LayoutState, apps: Map<String, LauncherApp>, repository: AppsRepository,
    revision: Long, registry: DragRegistry, onClose: () -> Unit, onLaunch: (LayoutItem) -> Unit,
    onMenu: (ItemPayload) -> Unit, onBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
) {
    val columns = LocalLauncherPreferences.current.folderColumns
    val prefs = LocalLauncherPreferences.current
    val rowHeight = maxOf(92.dp, 32.dp + (prefs.gap * 2 + 6).dp +
        if(prefs.labels) workspaceLabelHeight(prefs) + 3.dp else 0.dp)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f)).clickable(onClick = onClose))
        GlassSurface(GlassTarget.FOLDERS, Modifier.fillMaxWidth(.92f).heightIn(max = 520.dp).padding(vertical = 24.dp)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }.pointerInput(Unit) { detectTapGestures {} },
            backdrop = LocalWorkspaceBackdrop.current) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(folder.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
                }
                val children = layout.children(folder.id)
                Text(stringResource(R.string.folder_count, children.size), style = MaterialTheme.typography.labelMedium)
                if (children.isEmpty()) Text(stringResource(R.string.folder_empty), modifier = Modifier.padding(vertical = 24.dp))
                else LazyVerticalGrid(columns = GridCells.Fixed(LocalLauncherPreferences.current.folderColumns), modifier = Modifier.weight(1f, fill = false).testTag("folderContents")) {
                    val slots = (((children.maxOf { it.position } + 1 + columns - 1) / columns) * columns).coerceAtMost(64)
                    items(slots, key = { it }) { index ->
                        val slot = Slot(folderContainer(folder.id), index)
                        WorkspaceCell(layout.at(slot.container, index), slot, layout, apps, repository, revision, registry,
                            false, false, {}, onLaunch, onMenu, Modifier.height(rowHeight))
                    }
                }
            }
        }
    }
}




@Composable
internal fun labelTint(color: LabelColor, backdrop: WorkspaceLabelBackdrop = WorkspaceLabelBackdrop.SURFACE): Color = when(color) {
    LabelColor.AUTO -> if(backdrop != WorkspaceLabelBackdrop.SURFACE) Color.White else MaterialTheme.colorScheme.onSurface
    LabelColor.WHITE -> Color.White
    LabelColor.BLACK -> Color.Black
    LabelColor.VIOLET -> MaterialTheme.colorScheme.primary
}


