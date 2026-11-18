package pl.violetglass.launcher.backup

import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import pl.violetglass.launcher.pet.PetRepository
import pl.violetglass.launcher.settings.PreferencesRepository
import pl.violetglass.launcher.workspace.LayoutRepository
import java.io.File

/** Durable roll-forward across Room and DataStore. Caller serializes all launcher writes. */
class BackupRestorer(
    private val layout: LayoutRepository, private val preferences: PreferencesRepository, file: File,
    private val releaseWidget: (Int) -> Unit,
    private val petPreferences: PetRepository? = null,
    private val afterLayout: suspend () -> Unit = {},
) {
    private val journal = AtomicFile(file)
    fun pending() = journal.baseFile.exists() || File(journal.baseFile.path + ".bak").exists()
    suspend fun restore(document: BackupDocument) = withContext(NonCancellable + Dispatchers.IO) {
        require(document.pet == null || petPreferences != null)
        if(pending() && recover() == document) return@withContext
        val payload = BackupCodec.encode(document)
        val old = layout.state.first()?.items.orEmpty().mapNotNull { it.widgetId }
        val record = JSONObject().put("format", "violet-restore-v1").put("document", JSONObject(String(payload, Charsets.UTF_8)))
            .put("release", JSONArray(old))
        val stream = journal.startWrite()
        try { stream.write(record.toString().toByteArray(Charsets.UTF_8)); journal.finishWrite(stream) }
        catch(error: Throwable) { journal.failWrite(stream); throw error }
        check(pending()) { "journal_write_failed" }
        recover()
    }
    suspend fun recover() = withContext(NonCancellable + Dispatchers.IO) {
        if(!pending()) return@withContext null
        val root = journal.openRead().use { JSONObject(String(BackupCodec.read(it, BackupCodec.MAX_BYTES * 2), Charsets.UTF_8)) }
        require(root.getString("format") == "violet-restore-v1")
        val document = BackupCodec.decode(root.getJSONObject("document").toString().toByteArray(Charsets.UTF_8))
        require(document.pet == null || petPreferences != null)
        val released = root.getJSONArray("release")
        require(released.length() <= BackupCodec.MAX_ITEMS)
        val ids = (0 until released.length()).map { released.getInt(it).also { id -> require(id > 0) } }
        layout.edit { document.layout }
        afterLayout()
        preferences.update { BackupCodec.merge(document.settings, it) }
        document.pet?.let { settings -> requireNotNull(petPreferences).update { settings } }
        ids.forEach(releaseWidget)
        journal.delete()
        document
    }
}
