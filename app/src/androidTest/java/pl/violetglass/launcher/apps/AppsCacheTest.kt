package pl.violetglass.launcher.apps

import android.content.ComponentCallbacks2
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppsCacheTest {
    @Test fun concurrentRequestsAndUnchangedRefreshReuseBitmap() = runBlocking {
        val repository = AppsRepository(ApplicationProvider.getApplicationContext<Context>())
        val app = repository.load().first()
        val revision = repository.iconRevision
        val icons = coroutineScope { (1..16).map { async(Dispatchers.Default) { repository.icon(app) } }.awaitAll() }
        assertNotNull(icons.first()); icons.forEach { assertSame(icons.first(), it) }
        repository.load()
        assertEquals(revision, repository.iconRevision)
        assertSame(icons.first(), repository.icon(app))
        assertTrue(repository.cachedIconBytes in 1..4*1024*1024)
    }
    @Suppress("DEPRECATION")
    @Test fun memoryPressureEvictsWithoutRecyclingDisplayedBitmap() = runBlocking {
        val repository = AppsRepository(ApplicationProvider.getApplicationContext<Context>())
        val app = repository.load().first()
        val bitmap = requireNotNull(repository.icon(app))
        repository.trimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertSame(bitmap, repository.icon(app))
        repository.trimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals(0, repository.cachedIconBytes)
        assertFalse(bitmap.isRecycled)
        assertNotSame(bitmap, repository.icon(app))
    }
}
