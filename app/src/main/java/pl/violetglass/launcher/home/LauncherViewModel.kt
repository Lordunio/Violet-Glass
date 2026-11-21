package pl.violetglass.launcher.home

import android.app.Application
import android.appwidget.AppWidgetHost
import android.os.Bundle
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.asStateFlow
import pl.violetglass.launcher.backup.*
import pl.violetglass.launcher.pet.PetRepository
import pl.violetglass.launcher.pet.petDataStore
import java.io.File
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.first
import pl.violetglass.launcher.R
import pl.violetglass.launcher.settings.*
import pl.violetglass.launcher.widgets.PendingWidget
import pl.violetglass.launcher.widgets.WIDGET_HOST_ID
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.violetglass.launcher.workspace.*
import pl.violetglass.launcher.apps.AppsRepository
import pl.violetglass.launcher.apps.LauncherApp

data class AppsState(val apps: List<LauncherApp> = emptyList(), val loading: Boolean = true, val failed: Boolean = false, val revision: Long = 0)

@OptIn(ExperimentalCoroutinesApi::class)
class LauncherViewModel(application: Application, private val savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    val preferencesRepository = PreferencesRepository(application.launcherDataStore)
    private val notices = Channel<Int>(Channel.BUFFERED)
    val notifications = notices.receiveAsFlow()
    private val mutableHaRefresh = MutableStateFlow(0)
    val haRefresh = mutableHaRefresh.asStateFlow()
    fun refreshHa() { mutableHaRefresh.value++ }
    private val mutableDiscoverRefresh = MutableStateFlow(0)
    val discoverRefresh = mutableDiscoverRefresh.asStateFlow()
    fun refreshDiscover() { mutableDiscoverRefresh.value++ }
    val preferences = preferencesRepository.data.catch { error ->
        if (error is CancellationException) throw error
        notices.send(R.string.preferences_read_error)
        emit(LauncherPreferences())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LauncherPreferences())
    val pendingWidget = savedStateHandle.getStateFlow<Bundle?>("pending_widget", null)
    fun pending(): PendingWidget? = pendingWidget.value?.let(PendingWidget::from)
    fun setPending(value: PendingWidget?) { savedStateHandle["pending_widget"] = value?.bundle() }
    fun notify(resource: Int) { notices.trySend(resource) }
    fun updatePreferences(transform: (LauncherPreferences) -> LauncherPreferences) {
        viewModelScope.launch {
            try { edits.withLock { backupRestorer.recover(); preferencesRepository.update(transform) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { notices.send(R.string.preferences_save_error) }
        }
    }
    private suspend fun requireUnlocked() {
        if (preferencesRepository.data.first().locked) throw LayoutRejected(LayoutRejected.Reason.LOCKED)
    }
    private val widgetCommits = mutableSetOf<Int>()
    fun cancelWidget() {
        val pending = pending() ?: return
        if (pending.id in widgetCommits) return
        if (!pending.configureOnly) releaseWidgetId(pending.id)
        setPending(null)
    }
    private fun releaseWidgetId(id: Int) {
        runCatching { AppWidgetHost(getApplication(), WIDGET_HOST_ID).deleteAppWidgetId(id) }
    }
    fun commitWidget() {
        val pending = pending() ?: return
        if (pending.configureOnly) { setPending(null); return }
        if (!widgetCommits.add(pending.id)) return
        setPending(pending.copy(stage = "commit"))
        viewModelScope.launch {
            edits.withLock {
                var oldId: Int? = null
                try {
                    backupRestorer.recover()
                    if (pending.replaceId == null) requireUnlocked()
                    layoutRepository.edit { state ->
                        oldId = state.items.find { it.id == pending.replaceId }?.widgetId
                        if (state.items.any { it.widgetId == pending.id }) state else LayoutEngine.addWidget(state, pending.id, pending.provider, pending.label,
                            pending.spanX, pending.spanY, pending.page, pending.replaceId)
                    }
                    if (oldId != null && oldId != pending.id) releaseWidgetId(oldId!!)
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) {
                    releaseWidgetId(pending.id)
                    messages.send((error as? LayoutRejected)?.reason)
                } finally { widgetCommits.remove(pending.id); if (this@LauncherViewModel.pending()?.id == pending.id) setPending(null) }
            }
        }
    }
    fun removeItem(id: String) {
        viewModelScope.launch { edits.withLock {
            try {
                backupRestorer.recover()
                requireUnlocked()
                var widgetId: Int? = null
                layoutRepository.edit { state -> widgetId = state.items.find { it.id == id }?.widgetId; LayoutEngine.remove(state, id) }
                widgetId?.let(::releaseWidgetId)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { messages.send((error as? LayoutRejected)?.reason) }
        } }
    }
    // Retained across configuration changes, deliberately fresh after process death.
    var hasHostedActivity = false
    val repository = AppsRepository(application)
    val layoutRepository = LayoutRepository(LayoutDatabase.get(application))
    private val restartLayout = MutableStateFlow(0)
    private val edits = Mutex()
    private val petPreferences = PetRepository(application.petDataStore)
    private val backupRestorer = BackupRestorer(layoutRepository, preferencesRepository,
        File(application.filesDir, "restore-pending.json"), ::releaseWidgetId, petPreferences)
    private val mutableBackup = MutableStateFlow(BackupState())
    val backup = mutableBackup.asStateFlow()
    fun dismissBackupPreview() { if(!mutableBackup.value.busy) mutableBackup.value = BackupState() }
    private fun backupWork(work: suspend () -> Unit) {
        if(mutableBackup.value.busy) return
        mutableBackup.value = mutableBackup.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try { work() }
            catch(cancel: CancellationException) { throw cancel }
            catch(error: Exception) {
                mutableBackup.value = mutableBackup.value.copy(message = if(error is LayoutRejected && error.reason == LayoutRejected.Reason.LOCKED)
                    R.string.layout_locked else R.string.backup_error)
            } finally { mutableBackup.value = mutableBackup.value.copy(busy = false) }
        }
    }
    fun exportBackup(uri: Uri) = backupWork {
        require(uri.scheme == "content")
        withContext(Dispatchers.IO) {
            val bytes = edits.withLock {
                backupRestorer.recover()
                BackupCodec.encode(BackupCodec.capture(requireNotNull(layoutRepository.state.first()),
                    preferencesRepository.data.first(), pet = petPreferences.settings.first()))
            }
            requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes); it.flush() }
        }
        mutableBackup.value = mutableBackup.value.copy(message = R.string.backup_exported)
    }
    fun inspectBackup(uri: Uri) {
        if(mutableBackup.value.busy) return
        mutableBackup.value = BackupState()
        backupWork {
        require(uri.scheme == "content")
        val document = withContext(Dispatchers.IO) {
            requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)).use { BackupCodec.decode(BackupCodec.read(it)) }
        }
        mutableBackup.value = mutableBackup.value.copy(preview = document)
        }
    }
    fun restoreBackup() {
        val document = mutableBackup.value.preview ?: return
        backupWork {
            edits.withLock {
                check(pending() == null) { "widget_pending" }
                val recovered = backupRestorer.recover()
                if(recovered != document) { requireUnlocked(); backupRestorer.restore(document) }
            }
            mutableBackup.value = BackupState(busy = true, message = R.string.backup_restored)
            notify(R.string.backup_restored)
        }
    }
    private val messages = Channel<LayoutRejected.Reason?>(Channel.BUFFERED)
    val layoutErrors = messages.receiveAsFlow()
    val workspace = restartLayout.flatMapLatest {
        layoutRepository.state.onStart {
            edits.withLock {
            backupRestorer.recover()
            layoutRepository.initialize()
            if (preferencesRepository.data.first().desktopRevision < 1) {
                layoutRepository.edit { LayoutEngine.resize(it, maxOf(6, it.config.columns), it.config.rows, maxOf(6, it.config.dockSlots)) }
                preferencesRepository.update { it.copy(desktopRevision = 1, dockBackground = false) }
            }
            }
        }
            .map { WorkspaceState(it) }
            .catch { error ->
                if (error is CancellationException) throw error
                emit(WorkspaceState(failed = true))
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkspaceState())
    private val refresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val state = merge(repository.changes, refresh).map {
        try {
            val apps = edits.withLock {
                repository.load().also { reconcileNewApps(it) }
            }
            AppsState(apps = apps, loading = false, revision = repository.iconRevision)
        }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { AppsState(loading = false, failed = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppsState())

    fun setAutoAddNewApps(enabled: Boolean) {
        viewModelScope.launch {
            edits.withLock {
                try {
                    backupRestorer.recover()
                    val packages = if (enabled) repository.load().mapTo(HashSet()) { it.component.packageName } else emptySet()
                    preferencesRepository.setAutoAddNewApps(enabled, packages)
                    refresh()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    notices.send(R.string.preferences_save_error)
                }
            }
        }
    }

    private suspend fun reconcileNewApps(apps: List<LauncherApp>) {
        try {
            if (!preferencesRepository.data.first().autoAddNewApps) return
            backupRestorer.recover()
            val prefs = preferencesRepository.data.first()
            if (!prefs.autoAddNewApps) return
            val byPackage = apps.distinctBy { it.component.packageName }.associateBy { it.component.packageName }
            val previous = preferencesRepository.knownAutoAddPackages()
            if (byPackage.keys == previous) return
            if (previous != null && !prefs.locked) {
                val added = byPackage.values.filter { it.component.packageName !in previous && it.key !in prefs.hiddenApps }
                if (added.isNotEmpty()) layoutRepository.edit { start ->
                    added.fold(start) { layout, app -> LayoutEngine.addNewApp(layout, app.key, app.label) }
                }
            }
            preferencesRepository.rememberAutoAddPackages(byPackage.keys.toSet())
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            notices.send(R.string.layout_save_error)
        }
    }

    fun refresh() { refresh.tryEmit(Unit) }
    fun retryLayout() { restartLayout.value++ }

    fun editLayout(transform: (LayoutState) -> LayoutState) {
        viewModelScope.launch {
            edits.withLock {
                try { backupRestorer.recover(); requireUnlocked(); layoutRepository.edit(transform) }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { messages.send((error as? LayoutRejected)?.reason) }
            }
        }
    }
    fun place(payload: ItemPayload, slot: Slot, merge: Boolean = true) = editLayout { LayoutEngine.place(it, payload, slot, merge) }
    fun add(payload: ItemPayload, container: String) = editLayout { LayoutEngine.place(it, payload, LayoutEngine.firstFree(it, container)) }
    private suspend fun cleanupRemovedPackage(packageName: String) {
        val absent = withContext(Dispatchers.IO) {
            try { getApplication<Application>().packageManager.getApplicationInfo(packageName, 0); false }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { true }
            catch (_: RuntimeException) { false }
        }
        if (!absent) return
        edits.withLock {
            try {
                backupRestorer.recover()
                layoutRepository.edit { LayoutEngine.removePackage(it, packageName) }
                pl.violetglass.launcher.apps.UninstallJournal.clear(getApplication(), packageName)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { notices.send(R.string.layout_save_error) }
        }
    }
    fun reconcilePendingUninstall() {
        val name = pl.violetglass.launcher.apps.UninstallJournal.pending(getApplication()) ?: return
        viewModelScope.launch { cleanupRemovedPackage(name) }
    }
    init { viewModelScope.launch { repository.removedPackages.collect { cleanupRemovedPackage(it) } } }
}

data class WorkspaceState(val layout: LayoutState? = null, val failed: Boolean = false)



