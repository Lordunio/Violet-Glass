package pl.violetglass.launcher.ui

import androidx.compose.ui.graphics.Color

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import pl.violetglass.launcher.settings.*
import pl.violetglass.launcher.gestures.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.LocalActivity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.violetglass.launcher.R
import pl.violetglass.launcher.apps.AppSearch
import pl.violetglass.launcher.home.LauncherViewModel
import pl.violetglass.launcher.workspace.*
import pl.violetglass.launcher.pet.*
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

private data class IconDrag(val payload: ItemPayload, val start: Offset, val point: Offset, val moved: Boolean = false, val grabOffset: Offset? = null)

@Composable
fun LauncherScreen(model: LauncherViewModel, homeEvent: Int, isDefault: Boolean, requestDefault: () -> Unit, homeSettings: () -> Unit, systemActions: SystemActions) {
    val counts by pl.violetglass.launcher.notifications.BadgeStore.counts.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalBadgeCounts provides counts) {
        LauncherContent(model, homeEvent, isDefault, requestDefault, homeSettings, systemActions)
    }
}

@Composable
private fun LauncherContent(model: LauncherViewModel, homeEvent: Int, isDefault: Boolean, requestDefault: () -> Unit, homeSettings: () -> Unit, systemActions: SystemActions) {
    val pet: PetViewModel = viewModel()
    val petSettings by pet.settings.collectAsStateWithLifecycle()
    val petPhoto by pet.photo.collectAsStateWithLifecycle()
    val petTracker = remember { GridSurfaceTracker() }
    val storedPreferences = LocalLauncherPreferences.current
    val prefs = if(pl.violetglass.launcher.BuildConfig.DISCOVER_AVAILABLE) storedPreferences
        else storedPreferences.copy(discover = storedPreferences.discover.copy(enabled = false))
    val widgetController = LocalWidgetController.current
    var personalization by rememberSaveable { mutableStateOf(false) }
    var settingsSection by rememberSaveable { mutableStateOf<String?>(null) }
    var desktopOptions by remember { mutableStateOf(false) }
    var folderArea by remember { mutableStateOf(Rect.Zero) }
    var widgetPicker by remember { mutableStateOf(false) }
    var resizingWidget by remember { mutableStateOf<LayoutItem?>(null) }
    var searchRequested by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val resourceValues by rememberUpdatedState(LocalResources.current)
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.apps, state.revision, prefs.iconPack, prefs.iconOverrides) {
        model.repository.warmIcons(state.apps, prefs.iconPack, pl.violetglass.launcher.icons.decodeIconChoices(prefs.iconOverrides))
    }
    val workspace by model.workspace.collectAsStateWithLifecycle()
    val layout = workspace.layout
    var showApps by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var drawerCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var editDrawerCategories by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val closeDrawer = {
        keyboard?.hide()
        showApps = false
        query = ""
        drawerCategory = null
    }
    var folderId by rememberSaveable { mutableStateOf<String?>(null) }
    var layoutDialog by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<ItemPayload?>(null) }
    var editingIcon by remember { mutableStateOf<ItemPayload?>(null) }
    var rename by remember { mutableStateOf<LayoutItem?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var removeFolder by remember { mutableStateOf<LayoutItem?>(null) }
    var placement by remember { mutableStateOf<ItemPayload?>(null) }
    var drag by remember { mutableStateOf<IconDrag?>(null) }
    var pageArea by remember { mutableStateOf(Rect.Zero) }
    var pendingPage by remember { mutableStateOf<Int?>(null) }
    val registry = remember { DragRegistry() }
    val pages = remember(prefs.ha.enabled, prefs.discover.enabled, layout?.config?.pages, prefs.petGameEnabled) {
        LauncherPages(prefs.ha.enabled, prefs.discover.enabled, layout?.config?.pages ?: 1, prefs.petGameEnabled)
    }
    val pager = rememberPagerState(initialPage = pages.firstDesktop) { pages.count }
    if (pl.violetglass.launcher.BuildConfig.DEBUG) LaunchedEffect(pager) {
        snapshotFlow { "page=${pager.currentPage} offset=${pager.currentPageOffsetFraction} target=${pager.targetPage} scrolling=${pager.isScrollInProgress}" }
            .collect { android.util.Log.d("VioletMotion", it) }
    }
    val activity = LocalActivity.current!!
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val discoverFactory = LocalDiscoverFactory.current
    val discoverConnection = remember(activity, discoverFactory) { discoverFactory(activity) }
    val floatingHa = prefs.ha.enabled && prefs.discover.enabled
    var overlayLayer by remember { mutableIntStateOf(0) }
    var panelDragging by remember { mutableStateOf(false) }
    DisposableEffect(discoverConnection) {
        discoverConnection.windowLayer { overlayLayer++ }
        onDispose { discoverConnection.windowLayer(null) }
    }
    SideEffect { discoverConnection.coveredByHa(floatingHa && pager.currentPage + pager.currentPageOffsetFraction < .999f) }
    val discoverRefresh by model.discoverRefresh.collectAsStateWithLifecycle()
    DisposableEffect(discoverConnection, lifecycle, prefs.discover.enabled, prefs.discover.provider, discoverRefresh) {
        val observer = LifecycleEventObserver { _, event ->
            if(event == Lifecycle.Event.ON_START && prefs.discover.enabled) discoverConnection.start(prefs.discover.provider)
            if(event == Lifecycle.Event.ON_STOP) discoverConnection.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); discoverConnection.stop() }
    }
    val discoverPage = if(prefs.discover.enabled) if(prefs.ha.enabled) 1 else 0 else -1
    LaunchedEffect(discoverConnection, discoverPage, personalization, floatingHa) {
        snapshotFlow {
            if(discoverPage < 0 || personalization) 0f else
                if(floatingHa && pager.currentPage + pager.currentPageOffsetFraction <= discoverPage) 1f else
                if(!pager.isScrollInProgress) { if(pager.currentPage == discoverPage) 1f else 0f }
                else (1f - kotlin.math.abs(pager.currentPage - discoverPage + pager.currentPageOffsetFraction)).coerceIn(0f, 1f)
        }.collect { discoverConnection.scroll(it) }
    }
    val desktopPage = pages.desktop(pager.currentPage)
    val showHa = pages.kind(pager.currentPage) == LauncherPage.HOME_ASSISTANT
    val showGame = pages.kind(pager.currentPage) == LauncherPage.PET_GAME
    var gameLocked by remember { mutableStateOf(false) }
    LaunchedEffect(showGame) {
        if (!showGame) {
            gameLocked = false
            pet.gameSession.pause()
        }
    }
    val onDesktop = pages.kind(pager.currentPage) == LauncherPage.DESKTOP
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    DisposableEffect(discoverConnection, prefs.ha.enabled) {
        discoverConnection.leftNavigation(if(prefs.ha.enabled) ({ scope.launch { pager.animateScrollToPage(0) }; Unit }) else null)
        discoverConnection.leftDrag(if(prefs.ha.enabled) ({ fraction, released ->
            panelDragging = !released
            scope.launch {
                if(released) pager.animateScrollToPage(if(fraction >= .25f) 0 else 1)
                else {
                    val position = 1f - fraction
                    val target = position.roundToInt()
                    pager.scrollToPage(target, position - target)
                }
            }; Unit
        }) else null)
        onDispose { discoverConnection.leftNavigation(null); discoverConnection.leftDrag(null) }
    }
    val goHome: () -> Unit = { scope.launch { pager.animateScrollToPage(pages.firstDesktop) } }
    val panelMask = (if(prefs.ha.enabled) 1 else 0) or (if(prefs.discover.enabled) 2 else 0) or (if(prefs.petGameEnabled) 4 else 0)
    var previousPanelMask by rememberSaveable { mutableIntStateOf(panelMask) }
    LaunchedEffect(panelMask) {
        if(previousPanelMask != panelMask) { previousPanelMask = panelMask; pager.scrollToPage(pages.firstDesktop) }
    }
    val haptic = LocalHapticFeedback.current
    val compact = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() < 480.dp }
    val landscape = LocalWindowInfo.current.containerSize.let { it.width > it.height }
    val density = LocalDensity.current
    val edge = with(density) { 32.dp.toPx() }
    val previewHalf = with(density) { 38.dp.toPx() }
    val previousLabel = stringResource(R.string.previous_page)
    val nextLabel = stringResource(R.string.next_page)
    val addPageLabel = stringResource(R.string.add_page)
    val launchError = stringResource(R.string.launch_error)
    val unavailable = stringResource(R.string.app_unavailable)
    val layoutMessages = mapOf(
        null to stringResource(R.string.layout_save_error),
        LayoutRejected.Reason.FULL to stringResource(R.string.layout_full),
        LayoutRejected.Reason.INVALID to stringResource(R.string.layout_invalid),
        LayoutRejected.Reason.PAGE_NOT_EMPTY to stringResource(R.string.page_not_empty),
        LayoutRejected.Reason.LIMIT to stringResource(R.string.page_limit),
        LayoutRejected.Reason.LOCKED to stringResource(R.string.layout_locked),
    )
    val messages by rememberUpdatedState(layoutMessages)
    val apps = remember(state.apps) { state.apps.associateBy { it.key } }
    val selectedCategory = drawerCategory?.takeIf { it in prefs.drawerCategories }
    val categoryApps = prefs.drawerCategories[selectedCategory]
    val visible = remember(state.apps, query, prefs.hiddenApps, prefs.reverseApps, categoryApps) {
        state.apps.filter { it.key !in prefs.hiddenApps && (categoryApps == null || it.key in categoryApps) &&
            AppSearch.matches(it.label, it.component.packageName, query) }
            .let { if (prefs.reverseApps) it.reversed() else it }
    }
    val notify: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }
    val launch: (LayoutItem) -> Unit = { item ->
        if (item.isWidget) menu = ItemPayload(item.id, title = item.title)
        else if (item.isFolder) folderId = item.id
        else {
            val app = apps[item.appKey]
            if (app == null) notify(unavailable)
            else if (!model.repository.launch(app)) { model.refresh(); notify(launchError) }
        }
    }
    val openMenu: (ItemPayload) -> Unit = { menu = it }
    val drawerCanDismiss by rememberUpdatedState(showApps && menu == null && drag == null && !editDrawerCategories)
    val dismissDrawer by rememberUpdatedState(closeDrawer)
    LaunchedEffect(showApps) {
        if(!showApps) {
            drawerCategory = null
            editDrawerCategories = false
        }
    }
    val selectSlot: (Slot) -> Unit = { slot ->
        placement?.let { model.place(it, slot); placement = null }
    }
    LaunchedEffect(model) { model.notifications.collect { snackbar.showSnackbar(resourceValues.getString(it)) } }
    LaunchedEffect(showApps, searchRequested) {
        if (showApps && searchRequested) { searchFocus.requestFocus(); searchRequested = false }
    }
    LaunchedEffect(model) { model.layoutErrors.collect { snackbar.showSnackbar(messages[it] ?: messages.getValue(null)) } }
    LaunchedEffect(homeEvent) {
        if (homeEvent > 0) {
            showApps = false; query = ""; folderId = null; placement = null; drag = null
            menu = null; rename = null; layoutDialog = false; newFolder = false; removeFolder = null
            personalization = false; widgetPicker = false; resizingWidget = null
            desktopOptions = false
            panelDragging = false
            pager.scrollToPage(pages.firstDesktop)
        }
    }
    LaunchedEffect(layout?.config?.pages, pendingPage) {
        pendingPage?.let { target ->
            if (target < (layout?.config?.pages ?: 1)) { pager.scrollToPage(pages.index(target)); pendingPage = null }
        }
    }
    BackHandler {
        when {
            showGame && gameLocked -> gameLocked = false
            !onDesktop -> goHome()
            drag != null -> drag = null
            folderId != null -> folderId = null
            placement != null -> placement = null
            showApps -> closeDrawer()
            else -> goHome()
        }
    }
    fun targetAt(point: Offset): Pair<Slot, Rect>? {
        val session = drag
        val item = layout?.items?.find { item -> item.id == session?.payload?.itemId }
        if(item?.occupiesRectangle == true && session != null) {
            if(!pageArea.contains(point)) return null
            val config = layout.config
            val origin = point - (session.grabOffset ?: Offset.Zero)
            return registry.targets.entries.filter {
                it.key.container == pageContainer(desktopPage) && it.value.width > 0f && it.value.height > 0f &&
                    it.key.position % config.columns <= config.columns - item.spanX &&
                    it.key.position / config.columns <= config.rows - item.spanY
            }.minByOrNull { (it.value.topLeft - origin).getDistanceSquared() }?.let { it.key to it.value }
        }
        return registry.targets.entries.firstOrNull {
            (if (folderId != null) it.key.container == folderContainer(folderId!!) else
                it.key.container == pageContainer(desktopPage) || it.key.container == DOCK) && it.value.contains(point)
        }?.let { it.key to it.value }
    }
    val selected = drag?.takeIf { it.moved }?.let { targetAt(it.point)?.first }
    val beginDrag by rememberUpdatedState<(Offset) -> Boolean>({ point ->
        if (onDesktop && layout != null && !layoutDialog && menu == null && !desktopOptions) {
            registry.sources.values.lastOrNull { (bounds, payload) ->
                val item = layout.items.find { it.id == payload.itemId }
                val visibleLayer = when {
                    showApps -> payload.itemId == null
                    folderId != null -> item?.container == folderContainer(folderId!!)
                    else -> item?.container == pageContainer(desktopPage) || item?.container == DOCK
                }
                bounds.contains(point) && visibleLayer
            }?.let { (bounds, payload) ->
                if (prefs.locked) menu = payload else {
                    val rectangle = layout.items.find { it.id == payload.itemId && it.occupiesRectangle }
                    drag = IconDrag(payload, point, point, grabOffset = rectangle?.let { point - bounds.topLeft })
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
        }
        drag != null
    })
    val updateDrag by rememberUpdatedState<(Offset) -> Unit>({ delta ->
        drag?.let {
            val point = it.point + delta
            val moved = it.moved || (point - it.start).getDistance() > 12f
            drag = it.copy(point = point, moved = moved)
            if (moved) {
                showApps = false; placement = null
                if (folderId != null && !folderArea.contains(point)) folderId = null
            }
        }
    })
    val endDrag by rememberUpdatedState<() -> Unit>({
        drag?.let { session ->
            if (!session.moved) menu = session.payload
            else {
                val target = targetAt(session.point)
                if (target != null) {
                    val (slot, bounds) = target
                    val center = bounds.center
                    val merge = layout?.at(slot.container, slot.position)?.isExpandedFolder == true ||
                        (kotlin.math.abs(session.point.x - center.x) < bounds.width * .30f &&
                            kotlin.math.abs(session.point.y - center.y) < bounds.height * .30f)
                    model.place(session.payload, slot, merge)
                }
            }
        }
        drag = null
    })
    val activeDrag by rememberUpdatedState(drag)
    val activePageArea by rememberUpdatedState(pageArea)
    LaunchedEffect(drag?.payload) {
        while (activeDrag != null) {
            delay(650)
            val session = activeDrag ?: break
            if (session.moved && folderId == null && activePageArea.contains(session.point)) {
                val target = when {
                    session.point.x < activePageArea.left + edge -> pages.desktop(pager.currentPage) - 1
                    session.point.x > activePageArea.right - edge -> pages.desktop(pager.currentPage) + 1
                    else -> pages.desktop(pager.currentPage)
                }
                if (target in 0 until pages.desktops && target != pages.desktop(pager.currentPage)) pager.scrollToPage(pages.index(target))
            }
        }
    }
    val gestureEligible by rememberUpdatedState<(Offset) -> Boolean>({ point ->
        onDesktop && !desktopOptions && layout != null && !showApps && folderId == null && placement == null && drag == null &&
            !layoutDialog && menu == null && rename == null && !newFolder && removeFolder == null &&
            !personalization && !widgetPicker && resizingWidget == null &&
            pageArea.contains(point) && point.x > pageArea.left + edge && point.x < pageArea.right - edge &&
            registry.sources.values.none { it.first.contains(point) }
    })
    val desktopLabelHeight = workspaceLabelHeight(prefs)
    val verticalGestures by rememberUpdatedState<() -> Boolean>({
        val required = with(density) {
            workspaceCellMinimumHeight(prefs, (pageArea.width / (layout?.config?.columns ?: 6)).toDp(),
                desktopLabelHeight).toPx() * (layout?.config?.rows ?: 5)
        }
        pageArea.height + 1 >= required
    })
    val swipeEligible by rememberUpdatedState<(Offset) -> Boolean>({ point ->
        onDesktop && !desktopOptions && layout != null && !showApps && folderId == null && placement == null && drag == null &&
            !layoutDialog && menu == null && rename == null && !newFolder && removeFolder == null &&
            !personalization && !widgetPicker && resizingWidget == null && (pageArea.contains(point) ||
                layout.items.any { !it.isWidget && registry.sources["item:${it.id}"]?.first?.contains(point) == true }) &&
            layout.items.none { it.isWidget && registry.sources["item:${it.id}"]?.first?.contains(point) == true }
    })
    val iconAt by rememberUpdatedState<(Offset) -> Boolean>({ point ->
        layout?.items?.any { !it.isWidget && registry.sources["item:${it.id}"]?.first?.contains(point) == true } == true
    })
    val performGesture by rememberUpdatedState<(DesktopGesture) -> Unit>({ gesture ->
        val binding = prefs.gestures[gesture] ?: GestureBinding()
        when(binding.action) {
            GestureAction.APPS -> { query = ""; showApps = true }
            GestureAction.SEARCH -> { query = ""; showApps = true; searchRequested = true }
            GestureAction.SETTINGS -> if (gesture == DesktopGesture.LONG_PRESS) desktopOptions = true else personalization = true
            GestureAction.WIDGETS -> widgetPicker = true
            GestureAction.NONE -> Unit
            else -> systemActions.execute(binding)
        }
    })
    val nativeWidgetAt by rememberUpdatedState<(Offset) -> Boolean>({ point -> !showApps && onDesktop && layout?.items?.any { item ->
                item.isWidget && item.container == pageContainer(desktopPage) && registry.sources["item:${item.id}"]?.first?.contains(point) == true
            } == true })
    // Keep one animation clock across desktop pages; panel boundaries reveal the scene spatially.
    val pagerPosition = pager.currentPage + pager.currentPageOffsetFraction
    val petSceneLeft = (pages.firstDesktop - pagerPosition).coerceIn(0f, 1f)
    val petSceneRight = (pages.lastDesktop + 1f - pagerPosition).coerceIn(0f, 1f)
    val petActive = petSettings.sceneEnabled && layout != null && !showApps && petSceneLeft < petSceneRight &&
        !desktopOptions && !personalization && folderId == null && drag == null && placement == null &&
        !widgetPicker && !layoutDialog && menu == null && rename == null && !newFolder && removeFolder == null && resizingWidget == null && editingIcon == null
    val petVisible by rememberUpdatedState(petActive && !pager.isScrollInProgress && onDesktop)
    var hopOrigin by remember(pager) { mutableStateOf<Offset?>(null) }
    LaunchedEffect(pager) {
        snapshotFlow { pager.isScrollInProgress }.collect { moving ->
            hopOrigin = if (moving) pet.ui.value.takeIf { it.ready && it.visible && it.sleepingDesktop == null }?.feet else null
        }
    }
    val hopDelta = pagerPosition - pager.settledPage
    val hopDestination = pager.settledPage + if (hopDelta >= 0f) 1 else -1
    val desktopHop = pager.isScrollInProgress && hopDestination in 0 until pages.count &&
        (pager.settledPage in pages.firstDesktop..pages.lastDesktop || hopDestination in pages.firstDesktop..pages.lastDesktop)
    Box(Modifier.fillMaxSize().testTag("launcherRoot").pointerInput(Unit) {
        detectLauncherDrag(
            acceptsConsumed = { nativeWidgetAt(it) },
            onStart = { beginDrag(it) }, onDrag = { updateDrag(it) },
            onEnd = { endDrag() }, onCancel = { drag = null },
        )
    }.pointerInput(Unit) {
        detectDesktopGestures({ gestureEligible(it) }, { verticalGestures() || iconAt(it) }, { performGesture(it) }, { swipeEligible(it) },
            onPinch = { desktopOptions = true },
            onTap = { point -> if(petVisible) { val touched = pet.hit(point); pet.touch(point); if(touched) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove) } },
            interceptTap = { point -> petVisible && !nativeWidgetAt(point) && pet.hit(point) })
    }) {
        HorizontalPager(pager, beyondViewportPageCount = 2,
            key = { pages.key(it) }, userScrollEnabled = !gameLocked && drag == null && placement == null && !showApps && folderId == null,
            modifier = Modifier.fillMaxSize().testTag("screenPager")) { screen ->
        if (pages.kind(screen) == LauncherPage.DESKTOP) {
        val inactiveRegistry = remember(screen) { DragRegistry() }
        val pageRegistry = if(screen == pager.currentPage) registry else inactiveRegistry
        val page = pages.desktop(screen)
        val drawerHere = showApps && screen == pager.currentPage
        val needsBackdrop = folderId != null || menu != null || layoutDialog || personalization || widgetPicker ||
            resizingWidget != null || rename != null || newFolder || removeFolder != null || editDrawerCategories
        val labelBackdrop = when {
            drawerHere -> WorkspaceLabelBackdrop.SURFACE
            petSettings.wallpaperEnabled && petSettings.background.isProcedural -> WorkspaceLabelBackdrop.DARK
            prefs.systemWallpaper || petSettings.wallpaperEnabled && petPhoto != null -> WorkspaceLabelBackdrop.PHOTO
            else -> WorkspaceLabelBackdrop.SURFACE
        }
        val wallpaperBackdrop = if (LocalGlassPolicy.current.blurDp > 0 &&
            petSettings.wallpaperEnabled && (petSettings.background.isProcedural || petPhoto != null)) {
            val layer = rememberGraphicsLayer()
            remember(layer) { GlassBackdrop(layer) }
        } else null
        CompositionLocalProvider(
            LocalGridSurfaceTracker provides if(petSettings.sceneEnabled && screen == pager.currentPage && !drawerHere) petTracker else null,
            LocalWorkspaceLabelBackdrop provides labelBackdrop,
            LocalGlassBackdrop provides (wallpaperBackdrop ?: LocalSceneBackdrop.current),
        ) {
        Scaffold(modifier = Modifier.then(if (screen != pager.currentPage) Modifier.clearAndSetSemantics {} else Modifier)
            .recordGlassBackdrop(if (needsBackdrop && screen == pager.currentPage) LocalWorkspaceBackdrop.current else null), containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = if (prefs.systemWallpaper && !drawerHere) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onBackground,
            snackbarHost = { SnackbarHost(snackbar) }) { insets ->
            Box(Modifier.fillMaxSize()) {
            InteractiveWallpaperLayer(pet, petSettings, Modifier.fillMaxSize().recordGlassBackdrop(wallpaperBackdrop))
            Column(Modifier.fillMaxSize().padding(insets).imePadding().padding(horizontal = prefs.margin.dp)) {
                if (drawerHere) {
                    GlassSurface(GlassTarget.DRAWER, Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.fillMaxSize().padding(12.dp)) {
                    val textShadow = drawerTextShadow()
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.all_apps), Modifier.weight(1f),
                            style = MaterialTheme.typography.headlineSmall.copy(shadow = textShadow))
                        DrawerAction(closeDrawer, Modifier.testTag("homeButton"), stringResource(R.string.home))
                    }
                    GlassSurface(GlassTarget.SEARCH, Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    val searchText = LocalContentColor.current
                    val searchShadow = drawerTextShadow(GlassTarget.SEARCH)
                    TextField(query, { query = it }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(shadow = searchShadow),
                        placeholder = { Text(stringResource(R.string.search_apps), style = LocalTextStyle.current.copy(shadow = searchShadow)) },
                        colors = TextFieldDefaults.colors(focusedTextColor = searchText, unfocusedTextColor = searchText,
                            focusedPlaceholderColor = searchText, unfocusedPlaceholderColor = searchText,
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            cursorColor = searchText,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier.fillMaxWidth().testTag("appSearch").focusRequester(searchFocus))
                    }
                    DrawerCategoryBar(prefs.drawerCategories, selectedCategory, { drawerCategory = it }) { editDrawerCategories = true }
                    key(query, selectedCategory) {
                        val gridState = rememberLazyGridState()
                        if(!state.loading && !state.failed && visible.isNotEmpty()) {
                            Text(stringResource(R.string.apps_count, visible.size),
                                style = MaterialTheme.typography.labelMedium.copy(shadow = textShadow))
                        }
                        Box(Modifier.weight(1f).fillMaxWidth().testTag("drawerViewport").pointerInput(gridState) {
                            detectDrawerReturn(atTop = { !gridState.canScrollBackward && !gridState.isScrollInProgress },
                                canDismiss = { drawerCanDismiss }, onDismiss = { dismissDrawer() })
                        }) {
                            when {
                                state.loading -> Column {
                                    CircularProgressIndicator()
                                    Text(stringResource(R.string.loading), style = LocalTextStyle.current.copy(shadow = textShadow))
                                }
                                state.failed -> Column {
                                    Text(stringResource(R.string.load_error), style = LocalTextStyle.current.copy(shadow = textShadow))
                                    Button(onClick = model::refresh) { Text(stringResource(R.string.retry)) }
                                }
                                visible.isEmpty() -> Text(stringResource(when {
                                    query.isNotBlank() -> R.string.no_results
                                    selectedCategory != null -> R.string.drawer_category_empty
                                    else -> R.string.no_apps
                                }), style = LocalTextStyle.current.copy(shadow = textShadow))
                                else -> AppDrawer(visible, model.repository, state.revision, pageRegistry,
                                    { if (!model.repository.launch(it)) { model.refresh(); notify(launchError) } },
                                    openMenu, Modifier.fillMaxSize(), gridState)
                            }
                        }
                    }
                    }
                    }
                } else if (layout == null) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        Text(stringResource(if (workspace.failed) R.string.layout_load_error else R.string.layout_loading))
                        if (workspace.failed) Button(onClick = model::retryLayout) { Text(stringResource(R.string.retry)) }
                    }
                } else {
                    if (placement != null) {
                        Text(stringResource(R.string.place_hint), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { placement = null }) { Text(stringResource(R.string.cancel)) }
                    }
                    if (drag?.moved == true) Text(stringResource(R.string.drop_hint), style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (landscape && prefs.dockVisible) 8.dp else 0.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth().testTag("desktopPager")
                            .semantics { onLongClick(resourceValues.getString(R.string.desktop_options)) { desktopOptions = true; true } }
                            .onGloballyPositioned { if(screen == pager.currentPage) pageArea = it.boundsInRoot() }) {
                        WorkspaceGrid(layout, page, apps, model.repository, state.revision, pageRegistry, selected, placement != null,
                            selectSlot, launch, openMenu, Modifier.fillMaxSize())
                    }
                    Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        repeat(layout.config.pages) { index ->
                            val pageLabel = stringResource(R.string.page_number, index + 1, layout.config.pages)
                            Box(Modifier.size(30.dp, 24.dp).testTag("page:$index")
                                .clickable { scope.launch { pager.animateScrollToPage(pages.index(index)) } }
                                .semantics { contentDescription = pageLabel; this.selected = index == desktopPage }, contentAlignment = Alignment.Center) {
                                Text(if (index == desktopPage) "●" else "○", fontSize = 10.sp, lineHeight = 12.sp,
                                    color = labelTint(pl.violetglass.launcher.settings.LabelColor.AUTO, labelBackdrop),
                                    modifier = if (index == desktopPage) Modifier.testTag("pageIndicator") else Modifier)
                            }
                        }
                    }
                    if (prefs.dockVisible && !landscape) WorkspaceDock(layout, apps, model.repository, state.revision, pageRegistry, selected, placement != null, selectSlot, launch, openMenu, compact)
                    }
                    if (prefs.dockVisible && landscape) WorkspaceSideDock(layout, apps, model.repository, state.revision, pageRegistry, selected, placement != null, selectSlot, launch, openMenu)
                    }
                }
                if (!drawerHere) {
                    val openAppsLabel = stringResource(R.string.open_apps_gesture)
                    Box(Modifier.fillMaxWidth().height(24.dp).testTag("appDrawerGestureArea")
                        .semantics { contentDescription = openAppsLabel; onClick(openAppsLabel) { showApps = true; true }
                            onLongClick(resourceValues.getString(R.string.desktop_options)) { desktopOptions = true; true } }
                        .pointerInput(Unit) {
                            var distance = 0f
                            detectVerticalDragGestures(onDragStart = { distance = 0f },
                                onDragEnd = { if (distance < -40.dp.toPx()) { query = ""; showApps = true } },
                                onVerticalDrag = { change, amount -> change.consume(); distance += amount })
                        }.pointerInput(Unit) { detectTapGestures(onLongPress = { desktopOptions = true }) })
                }
            }
        }
        }
        }
        } else if (pages.kind(screen) == LauncherPage.HOME_ASSISTANT) {
            if(!floatingHa) {
            val active by remember(pager, screen) { derivedStateOf { kotlin.math.abs(pager.currentPage - screen + pager.currentPageOffsetFraction) < 1f } }
            val haRefresh by model.haRefresh.collectAsStateWithLifecycle()
            HomeAssistantScreen(prefs.ha, onHome = goHome,
                onSettings = { settingsSection = "HA"; personalization = true }, active = active, refreshKey = haRefresh)
            } else Box(Modifier.fillMaxSize().testTag("haPage"))
        }
        else if (pages.kind(screen) == LauncherPage.PET_GAME) {
            PetGamePanel(pet, petSettings.appearance, pager.settledPage == screen && !pager.isScrollInProgress && !personalization,
                locked = gameLocked, onToggleLock = { gameLocked = !gameLocked })
        } else DiscoverScreen(prefs.discover, goHome, { settingsSection = "DISCOVER"; personalization = true },
            progress = { if(personalization) 0f else (1f - kotlin.math.abs(pager.currentPage - screen + pager.currentPageOffsetFraction)).coerceIn(0f, 1f) },
            onNativeScroll = { value -> scope.launch {
                if(floatingHa && pager.currentPage + pager.currentPageOffsetFraction < screen - .001f) return@launch
                val position = screen + 1f - value
                val target = position.roundToInt().coerceIn(0, pages.count - 1)
                pager.scrollToPage(target, position - target)
            } }, connection = discoverConnection)
        }
        if(floatingHa) {
            val refresh by model.haRefresh.collectAsStateWithLifecycle()
            FloatingHaPanel(prefs.ha, pager.currentPage + pager.currentPageOffsetFraction, refresh, personalization, overlayLayer, panelDragging,
                goHome, { settingsSection = "HA"; personalization = true }) { position, released ->
                scope.launch {
                    panelDragging = !released
                    if(released) pager.animateScrollToPage(if(position > .25f) 1 else 0)
                    else { val page = position.roundToInt(); pager.scrollToPage(page, position - page) }
                }
            }
        }
        val restDesktop = remember(layout, petSettings.restPlace) {
            layout?.items?.firstOrNull { petRestItem(it.id) == petSettings.restPlace && it.container.startsWith("page:") }
                ?.container?.substringAfter(':')?.toIntOrNull()
        }
        InteractiveTamagotchiOverlay(pet, petTracker, petSettings, petActive,
            sceneLeftFraction = petSceneLeft, interactive = petVisible, sceneRightFraction = petSceneRight,
            hopOrigin = if (desktopHop) hopOrigin else null, hopPageDelta = hopDelta, pagerMoving = pager.isScrollInProgress,
            desktopPage = if (onDesktop) desktopPage else null, restPage = restDesktop,
            desktopPosition = pagerPosition - pages.firstDesktop)
        val desktopBars = !showApps && onDesktop
        val darkWallpaper = desktopBars && petSettings.wallpaperEnabled && petSettings.background.isProcedural
        LauncherSystemBars(darkWallpaper || showGame, !showHa && !showGame && !darkWallpaper &&
            (prefs.systemWallpaper || desktopBars && petSettings.wallpaperEnabled && petPhoto != null))
        val folder = layout?.items?.find { it.id == folderId && it.isFolder }
        if (folder != null && !showApps) FolderOverlay(folder, layout, apps, model.repository, state.revision, registry,
            { folderId = null }, launch, openMenu, onBounds = { folderArea = it })
        drag?.takeIf { it.moved }?.let { session ->
            Surface(Modifier.offset { IntOffset((session.point.x - previewHalf).roundToInt(), (session.point.y - previewHalf).roundToInt()) }
                .size(76.dp).zIndex(5f), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primaryContainer, shadowElevation = 10.dp) {
                Box(contentAlignment = Alignment.Center) { AppIcon(apps[session.payload.appKey], session.payload.title, model.repository, state.revision) }
            }
        }
    }
    if (desktopOptions) DesktopOptions(
        onDismiss = { desktopOptions = false }, onSettings = { desktopOptions = false; personalization = true },
        onLayout = { desktopOptions = false; layoutDialog = true }, onWidgets = { desktopOptions = false; widgetPicker = true },
        onWallpaper = { desktopOptions = false; systemActions.execute(GestureBinding(GestureAction.WALLPAPER)) },
        onAddPage = { desktopOptions = false; pendingPage = layout?.config?.pages; model.editLayout(LayoutEngine::addPage) },
        canAddPage = layout != null && layout.config.pages < 10 && !prefs.locked,
        onDefault = requestDefault, isDefault = isDefault)
    if(showApps && editDrawerCategories) {
        val categoryCandidates = remember(state.apps, prefs.hiddenApps) { state.apps.filter { it.key !in prefs.hiddenApps } }
        DrawerCategoryDialog(prefs.drawerCategories, categoryCandidates, selectedCategory,
            onSave = { oldName, name, keys ->
                model.updatePreferences { current ->
                    val categories = current.drawerCategories
                    val nameTaken = categories.keys.any { it != oldName && it.equals(name, ignoreCase = true) }
                    if(nameTaken || oldName == null && categories.size >= MAX_DRAWER_CATEGORIES) current
                    else current.copy(drawerCategories = (if(oldName == null) categories else categories - oldName) + (name to keys))
                }
                if(oldName == selectedCategory && oldName != null) drawerCategory = name
                editDrawerCategories = false
            }, onDelete = { name ->
                model.updatePreferences { it.copy(drawerCategories = it.drawerCategories - name) }
                if(drawerCategory == name) drawerCategory = null
                editDrawerCategories = false
            }, onDismiss = { editDrawerCategories = false })
    }
    if (personalization) PersonalizationDialog(model, state.apps, systemActions, { personalization = false; settingsSection = null },
        { layoutDialog = true }, { widgetPicker = true }, initialSection = settingsSection)
    if (widgetPicker && widgetController != null) WidgetPicker(widgetController, desktopPage, { widgetPicker = false })
    if (layout != null) {
        editingIcon?.let { payload -> payload.appKey?.let { key -> IndividualIconPicker(model, key, payload.title) { editingIcon = null } } }
        resizingWidget?.let { item -> WidgetResizeDialog(item, layout.config, { resizingWidget = null }) { x, y ->
            model.editLayout { LayoutEngine.resizeWidget(it, item.id, x, y) }
        } }
        menu?.let { payload ->
            val item = layout.items.find { it.id == payload.itemId }
            if (item?.isWidget == true) WidgetMenu(item, prefs.locked, { menu = null },
                onMove = { placement = payload; showApps = false }, onResize = { resizingWidget = item }, onRemove = { model.removeItem(item.id) })
            else ItemMenu(payload, item?.isFolder == true, { menu = null },
                onResizeFolder = item?.takeIf { it.isFolder && it.container.startsWith("page:") }?.let { folder ->
                    { model.editLayout { LayoutEngine.resizeFolder(it, folder.id, !folder.isExpandedFolder) } }
                },
                expandedFolder = item?.isExpandedFolder == true,
                onUninstall = apps[payload.appKey]?.takeIf { model.repository.canUninstall(it) }?.let { app -> ({ systemActions.uninstall(app) }) },
                onIcon = { editingIcon = payload },
                onAdd = { container -> model.add(payload, if (container == "home") pageContainer(desktopPage) else container) },
                onMove = { placement = payload; showApps = false; folderId = null },
                onRename = { rename = item },
                onRemove = {
                    if (item?.isFolder == true) removeFolder = item
                    else item?.let { model.removeItem(it.id) }
                })
        }
        rename?.let { item -> NameDialog(stringResource(if (item.isFolder) R.string.folder_name else R.string.shortcut_name), item.title,
            { rename = null }, { name -> model.editLayout { LayoutEngine.rename(it, item.id, name) } }) }
        if (newFolder) NameDialog(stringResource(R.string.folder_name), stringResource(R.string.new_folder), { newFolder = false },
            { name -> model.editLayout { LayoutEngine.addFolder(it, pageContainer(desktopPage), name) } })
        if (layoutDialog) LayoutDialog(layout.config, { layoutDialog = false },
            { columns, rows, dock -> model.editLayout { LayoutEngine.resize(it, columns, rows, dock) } },
            { newFolder = true }, { model.editLayout(LayoutEngine::removeLastPage) }, requestDefault, homeSettings, isDefault,
            onWidgets = { widgetPicker = true }, onPersonalization = { personalization = true })
        removeFolder?.let { item ->
            GlassAlertDialog(onDismissRequest = { removeFolder = null }, title = { Text(item.title) },
                text = { Text(stringResource(R.string.remove_folder_confirm)) }, confirmButton = {
                    TextButton(onClick = { model.removeItem(item.id); removeFolder = null }) { Text(stringResource(R.string.remove)) }
                }, dismissButton = { TextButton(onClick = { removeFolder = null }) { Text(stringResource(R.string.cancel)) } })
        }
    }
}









