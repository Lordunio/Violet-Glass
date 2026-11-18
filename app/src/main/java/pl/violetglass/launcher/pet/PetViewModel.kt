package pl.violetglass.launcher.pet

import android.app.Application
import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.BatteryManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.math.*
import kotlin.random.Random

val Context.petDataStore by preferencesDataStore("interactive_pet")

class PetRepository(private val store: DataStore<Preferences>) {
    companion object {
        private val enabled = booleanPreferencesKey("enabled")
        private val wallpaperEnabled = booleanPreferencesKey("wallpaper_enabled")
        private val background = stringPreferencesKey("background")
        private val weather = stringPreferencesKey("weather")
        private val parallax = booleanPreferencesKey("parallax")
        private val appearance = stringPreferencesKey("appearance")
        private val motion = stringPreferencesKey("motion")
        private val restPlace = stringPreferencesKey("rest_place")
        private val effectArea = stringPreferencesKey("effect_area")
        private val intensity = floatPreferencesKey("effect_intensity")
        private val speed = floatPreferencesKey("effect_speed")
        private val size = floatPreferencesKey("effect_size")
        private val opacity = floatPreferencesKey("effect_opacity")
        private val messagesEnabled = booleanPreferencesKey("messages_enabled")
        private val allMessageApps = booleanPreferencesKey("all_message_apps")
        private val messageApps = stringSetPreferencesKey("message_apps")
        private val messagesOnly = booleanPreferencesKey("messages_only")
        private val energy = floatPreferencesKey("energy")
        private val happiness = floatPreferencesKey("happiness")
        private val cleanliness = floatPreferencesKey("cleanliness")
        private val updated = longPreferencesKey("updated")
        private inline fun <reified T : Enum<T>> enum(value: String?, default: T) = enumValues<T>().find { it.name == value } ?: default
        private fun stats(p: Preferences, now: Long) = PetStats(p[energy]?.takeIf(Float::isFinite)?.coerceIn(0f, 100f) ?: 80f,
            p[happiness]?.takeIf(Float::isFinite)?.coerceIn(0f, 100f) ?: 80f,
            p[cleanliness]?.takeIf(Float::isFinite)?.coerceIn(0f, 100f) ?: 100f, p[updated] ?: now)
        private fun put(p: MutablePreferences, stats: PetStats) {
            p[energy] = stats.energy; p[happiness] = stats.happiness; p[cleanliness] = stats.cleanliness; p[updated] = stats.lastUpdatedTimestamp
        }
        private fun configuration(p: Preferences) = PetSettings(p[enabled] ?: false, enum(p[background], PetBackgroundMode.PHOTO_PARALLAX),
            enum(p[weather], PetWeather.AUTO), p[parallax] ?: true, enum(p[appearance], PetAppearance.PIXEL_CAT),
            enum(p[motion], PetMotion.CALM), enum(p[effectArea], PetEffectArea.FULL_SCREEN), p[intensity] ?: .5f,
            p[speed] ?: 1f, p[size] ?: 1f, p[opacity] ?: .6f, p[messagesEnabled] ?: true,
            p[allMessageApps] ?: true, p[messageApps] ?: emptySet(), p[messagesOnly] ?: true,
            p[wallpaperEnabled] ?: p[enabled] ?: false, p[restPlace]).normalized()
    }
    val settings = store.data.map(::configuration)
    suspend fun update(change: (PetSettings) -> PetSettings) { store.edit { p ->
        val next = change(configuration(p)).normalized()
        p[enabled] = next.enabled; p[background] = next.background.name; p[weather] = next.weather.name; p[parallax] = next.parallax
        p[wallpaperEnabled] = next.wallpaperEnabled
        p[appearance] = next.appearance.name; p[motion] = next.motion.name; p[effectArea] = next.effectArea.name
        p[intensity] = next.effectIntensity; p[speed] = next.effectSpeed; p[size] = next.effectSize; p[opacity] = next.effectOpacity
        p[messagesEnabled] = next.messagesEnabled; p[allMessageApps] = next.allMessageApps; p[messageApps] = next.messageApps
        p[messagesOnly] = next.messagesOnly
        if(next.restPlace == null) p.remove(restPlace) else p[restPlace] = next.restPlace
    } }
    suspend fun resume(now: Long): PetStats {
        var result = PetStats(lastUpdatedTimestamp = now)
        store.edit { p -> result = stats(p, now).advance(now); put(p, result) }
        return result
    }
    suspend fun save(value: PetStats) { store.edit { p -> if(value.lastUpdatedTimestamp >= (p[updated] ?: 0L)) put(p, value) } }
}

/** Mutable, allocation-light simulation. Called on the main thread at fixed 1/120 s steps. */
class PetEngine(seed: Int = 19) {
    private val random = Random(seed)
    var state = PetUiState()
        private set
    private var world = Rect.Zero
    private var density = 1f
    private var platforms = emptyList<PlatformSurface>()
    private var registeredGeometry = emptyList<PlatformSurface>()
    private var current: PlatformSurface? = null
    private var goal: PlatformSurface? = null
    private var goalX = 0f
    private var route = emptyList<String>()
    private var vx = 0f; private var vy = 0f; private var airborne = false
    private var accumulator = 0f; private var decisionIn = 1.5f
    private var happyFor = 0f
    private var hopCooldown = 0f
    private var automaticRoute = false
    private var hopStartX = 0f
    private var roamSeconds = 0f
    private var restSeconds = 0f
    private var returningToRest = false
    private var pendingTouch: Offset? = null
    private var options = PetSettings()
    private var particleArea = Rect.Zero
    private var lowerEffectTop = 0f
    private var power = PowerState()
    val particles = ParticleField(seed)
    fun restore(stats: PetStats) { state = state.copy(ready = true, stats = stats) }
    fun viewport(width: Float, height: Float, scale: Float, navigationBottom: Float) {
        density = scale.coerceAtLeast(.5f)
        world = Rect(0f, 0f, width.coerceAtLeast(1f), (height - navigationBottom - 5f * density).coerceAtLeast(1f))
        lowerEffectTop = height * .45f
        state = state.copy(sizePx = min(44f * density, width / 6f * .85f).coerceAtLeast(24f * density))
        particleArea = Rect.Zero
        surfaces(registeredGeometry)
    }
    fun surfaces(values: List<PlatformSurface>) {
        // Layout callbacks may precede the overlay's first onSizeChanged. Retain raw
        // geometry so a later viewport/rotation can rebuild all eligible platforms.
        registeredGeometry = values.filter { it.kind != PlatformKind.FLOOR }
        if(world.width <= 0f || world.height <= state.sizePx) return
        val floor = PlatformSurface("floor", Rect(world.left, world.bottom, world.right, world.bottom + 1), PlatformKind.FLOOR)
        platforms = registeredGeometry.filter { it.bounds.width >= state.sizePx * .5f && it.bounds.top >= world.top + state.sizePx &&
            it.bounds.top <= world.bottom && it.bounds.left >= -1f && it.bounds.right <= world.right + 1f } + floor
        val old = current
        current = platforms.find { it.id == old?.id }
        if(current == null || current?.kind == PlatformKind.FLOOR && platforms.any { it.kind == PlatformKind.DOCK }) current = platforms.filter { it.kind == PlatformKind.DOCK }.maxByOrNull { it.bounds.width } ?: floor
        if(old != current || !state.visible) {
            airborne = false; vx = 0f; vy = 0f
            state = state.copy(feet = Offset(safeX(current!!, if(state.visible) state.feet.x else current!!.bounds.left + state.sizePx), current!!.bounds.top),
                platformId = current!!.id, visible = true)
            invalidateRoute()
            // A tap in the old viewport no longer points at the same place after a rebase.
            pendingTouch = null
        } else if((goal != null && platforms.none { it == goal }) || route.any { id -> platforms.none { it.id == id } }) {
            invalidateRoute()
        }
    }
    private fun invalidateRoute() {
        if(returningToRest || restSeconds > 0f) {
            roamSeconds = ROAM_BEFORE_REST_SECONDS
            restSeconds = 0f
            decisionIn = 0f
        }
        clearRoute()
    }

    private fun clearRoute() {
        goal = null
        route = emptyList()
        returningToRest = false
        state = state.copy(targetPlatform = null, targetX = null)
    }

    private fun favoriteSurface(): PlatformSurface? {
        val favorite = options.restPlace ?: return null
        if(favorite == PET_REST_DOCK) {
            return platforms.filter { it.kind == PlatformKind.DOCK }
                .minByOrNull { (it.bounds.center - world.center).getDistanceSquared() }
        }
        return platforms.firstOrNull { it.kind != PlatformKind.FLOOR && petRestItem(it.id.substringAfter(':')) == favorite }
    }

    private fun startRestRoute(source: PlatformSurface): Boolean {
        val favorite = favoriteSurface() ?: return false
        returningToRest = routeTo(source, favorite, favorite.bounds.center.x, automatic = true)
        return returningToRest
    }

    private fun routeTo(source: PlatformSurface, destination: PlatformSurface, x: Float, automatic: Boolean): Boolean {
        val localRoute = if(automatic) path(source, destination, nearbyOnly = true) else emptyList()
        val next = localRoute.ifEmpty { path(source, destination) }
        if(source.id != destination.id && next.isEmpty()) return false
        goal = destination
        route = next
        goalX = safeX(destination, x)
        automaticRoute = automatic
        returningToRest = false
        state = state.copy(targetPlatform = destination.id, targetX = goalX)
        return true
    }
    private fun safeX(surface: PlatformSurface, x: Float): Float {
        val inset = min(state.sizePx / 2, surface.bounds.width / 2)
        return x.coerceIn(surface.bounds.left + inset, surface.bounds.right - inset)
    }
    private fun reachable(a: PlatformSurface, b: PlatformSurface): Boolean {
        val lateralRoom = max(abs(safeX(a, a.bounds.left) - safeX(b, b.bounds.right)),
            abs(safeX(a, a.bounds.right) - safeX(b, b.bounds.left)))
        // Every registered on-screen ledge is a destination, including sparse upper rows
        // and tall widgets. The ballistic arc below adapts to the actual height difference.
        return options.motion != PetMotion.FAST_HOPS || lateralRoom >= state.sizePx * .75f
    }
    private fun nearby(a: PlatformSurface, b: PlatformSurface): Boolean {
        val left = max(a.bounds.left, b.bounds.left)
        val right = min(a.bounds.right, b.bounds.right)
        val gap = max(0f, left - right)
        return gap <= max(world.width / 6f, 56f * density) &&
            abs(a.bounds.top - b.bounds.top) <= 140f * density
    }

    private fun path(from: PlatformSurface, to: PlatformSurface, nearbyOnly: Boolean = false): List<String> {
        val previous = mutableMapOf(from.id to "")
        val queue = ArrayDeque<PlatformSurface>().apply { add(from) }
        while(queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if(next.id == to.id) {
                val result = mutableListOf<String>(); var id = to.id
                while(id != from.id) { result.add(id); id = previous.getValue(id) }
                return result.asReversed()
            }
            roamingSurfaces().asSequence().filter { it.id !in previous && reachable(next, it) && (!nearbyOnly || nearby(next, it)) }
                .sortedBy { abs(it.bounds.center.x - to.bounds.center.x) }.forEach { previous[it.id] = next.id; queue.add(it) }
        }
        return emptyList()
    }
    // The navigation-bar floor is a safety net, not a destination below a real dock.
    private fun roamingSurfaces() = platforms.filter { it.kind != PlatformKind.FLOOR }.ifEmpty { platforms }
    fun target(point: Offset, ripple: Boolean = true, automatic: Boolean = false) {
        if(!state.visible) return
        if(!automatic) { roamSeconds = 0f; restSeconds = 0f }
        if(ripple) state = state.copy(ripple = Offset(point.x.coerceIn(0f, world.right), point.y.coerceIn(
            if(options.effectArea == PetEffectArea.FULL_SCREEN) 0f else lowerEffectTop, world.bottom)), rippleAge = 0f)
        if(airborne && !automatic) {
            // Keep the current landing plan until the pet has solid ground again.
            pendingTouch = point
            return
        }
        val source = current ?: return
        val candidates = roamingSurfaces().sortedBy { abs(it.bounds.top - point.y) * .5f + abs(safeX(it, point.x) - point.x) }
        for(candidate in candidates) {
            if(routeTo(source, candidate, point.x, automatic)) {
                decisionIn = when(options.motion) { PetMotion.CALM -> 12f; PetMotion.NORMAL -> 7f; PetMotion.FAST_HOPS -> 1.2f }; return
            }
        }
    }
    fun pat(now: Long) {
        happyFor = 2.5f
        state = state.copy(stats = state.stats.advance(now, power.plugged, state.night).let { it.copy(happiness = (it.happiness + 3).coerceAtMost(100f)) })
    }
    fun clean(now: Long) { pat(now); state = state.copy(stats = state.stats.copy(cleanliness = 100f)) }
    fun notification() { notifications(1) }
    fun notifications(count: Int) { state = state.copy(notificationCount = count.coerceIn(0, 999)) }
    fun environment(value: PowerState, night: Boolean) { power = value; state = state.copy(night = night, charging = value.plugged) }
    fun advanceStats(now: Long) { state = state.copy(stats = state.stats.advance(now, power.plugged, state.night || power.percent <= 12)) }
    fun tick(seconds: Float, weather: PetWeather, parallax: Offset, settings: PetSettings = PetSettings()) {
        if(options.restPlace != settings.restPlace) {
            roamSeconds = 0f
            restSeconds = 0f
            if(returningToRest) clearRoute()
        }
        options = settings
        val delta = seconds.takeIf { it.isFinite() }?.coerceIn(0f, .1f) ?: 0f
        accumulator += delta
        while(accumulator >= 1f / 120f) { step(1f / 120f); accumulator -= 1f / 120f }
        val effective = if(weather == PetWeather.AUTO) PetWeather.FIREFLIES else weather
        val area = if(settings.effectArea == PetEffectArea.FULL_SCREEN) world else world.copy(top = min(lowerEffectTop, world.bottom))
        if(area != particleArea) { particleArea = area; particles.reset(area) }
        particles.step(delta, effective, platforms, area, settings.effectIntensity, settings.effectSpeed, density)
        state = state.copy(animationSeconds = state.animationSeconds + delta, rippleAge = state.rippleAge + delta,
            parallax = state.parallax + (parallax - state.parallax) * (1f - exp(-delta / .12f)), appearance = settings.appearance)
    }
    private fun step(dt: Float) {
        if(!state.visible) return
        happyFor = (happyFor - dt).coerceAtLeast(0f)
        hopCooldown = (hopCooldown - dt).coerceAtLeast(0f)
        val sleeping = !power.plugged && (state.night || power.percent <= 12 || state.stats.energy <= 8)
        val stationary = sleeping || power.plugged || happyFor > 0
        var feet = state.feet
        val calm = options.motion == PetMotion.CALM
        val fast = options.motion == PetMotion.FAST_HOPS
        val cooldown = if(fast) .65f else if(calm) 12f else 6f
        val speed = (if(fast) 150f else if(calm) 36f else 48f) * density
        if(!airborne && !stationary) {
            val source = current!!
            roamSeconds = min(ROAM_BEFORE_REST_SECONDS, roamSeconds + dt)
            decisionIn -= dt
            if(restSeconds > 0f) {
                restSeconds = (restSeconds - dt).coerceAtLeast(0f)
            } else if(decisionIn <= 0 && route.isEmpty() && !returningToRest) {
                if(roamSeconds < ROAM_BEFORE_REST_SECONDS || !startRestRoute(source)) {
                    val alternatives = roamingSurfaces().filter {
                        it.id != source.id && (reachable(source, it) || path(source, it).isNotEmpty())
                    }
                    val stay = alternatives.isEmpty() || !fast && (hopCooldown > 0 || random.nextFloat() < if(calm) .8f else .55f)
                    val choice = if(stay) source else {
                        val neighbors = alternatives.filter { nearby(source, it) }
                        val choices = if(neighbors.isNotEmpty() && random.nextFloat() < .8f) neighbors else alternatives
                        choices.random(random)
                    }
                    routeTo(source, choice, choice.bounds.left + random.nextFloat() * choice.bounds.width, automatic = true)
                    decisionIn = (if(fast) 1.2f else if(calm) 8f else 4f) + random.nextFloat() * (if(fast) 1f else if(calm) 8f else 5f)
                }
            }
            val next = route.firstOrNull()?.let { id -> platforms.find { it.id == id } }
            val destination = if(next == null) if(goal?.id == source.id) goalX else feet.x else {
                val side = if(next.bounds.center.x >= feet.x) -1f else 1f
                safeX(source, next.bounds.center.x + if(fast) side * state.sizePx * 1.1f else 0f)
            }
            val dx = destination - feet.x
            vx = if(abs(dx) < 1f) 0f else sign(dx) * speed
            feet = feet.copy(x = if(abs(dx) <= speed * dt) destination else feet.x + vx * dt)
            if(returningToRest && next == null && goal?.id == source.id && abs(feet.x - goalX) <= 1f) {
                clearRoute()
                roamSeconds = 0f
                restSeconds = 5f
            }
            if(next != null && abs(feet.x - destination) <= 1f) {
                var landX = safeX(next, if(next.id == goal?.id) goalX else if(fast) next.bounds.center.x else feet.x)
                if(fast && abs(landX - feet.x) < state.sizePx * .75f) {
                    landX = listOf(safeX(next, next.bounds.left), safeX(next, next.bounds.right)).maxBy { abs(it - feet.x) }
                }
                val dy = next.bounds.top - feet.y
                val joined = abs(dy) <= 5f * density && source.bounds.left <= next.bounds.right && next.bounds.left <= source.bounds.right
                if(joined) {
                    // Overlapping/contiguous ledges are one walking surface, not a jump.
                    current = next; feet = feet.copy(y = next.bounds.top)
                    route = route.drop(1)
                } else if(hopCooldown <= 0 || !automaticRoute) {
                    if(fast && abs(landX - feet.x) < state.sizePx * .75f) {
                        // No horizontal room: wait/walk instead of bouncing vertically.
                        clearRoute()
                        decisionIn = 1.2f
                        return
                    }
                    val gravity = 600f * density
                    val climb = max(0f, -dy)
                    val clearance = (if(fast) 14f else if(calm) 32f else 24f) * density
                    val headroom = (feet.y - world.top - state.sizePx).coerceAtLeast(0f)
                    val rise = min(climb + clearance, headroom).coerceAtLeast(climb + density)
                    val duration = sqrt(2f * rise / gravity) + sqrt(2f * max(0f, rise + dy) / gravity)
                    vx = (landX - feet.x) / duration
                    vy = -sqrt(2f * gravity * rise)
                    airborne = true
                    hopStartX = feet.x
                    hopCooldown = cooldown
                } else vx = 0f
            }
        } else if(!airborne) vx = 0f
        if(airborne) {
            val before = feet.y
            feet += Offset(vx * dt, vy * dt + 300f * density * dt * dt)
            vy += 600f * density * dt
            if(vy >= 0) {
                val landing = platforms.filter {
                    before <= it.bounds.top + 1 && feet.y >= it.bounds.top &&
                        feet.x in it.bounds.left..it.bounds.right &&
                        (it.kind == PlatformKind.FLOOR || (it.id != current?.id || route.isEmpty()) &&
                            (!fast || abs(feet.x - hopStartX) >= state.sizePx * .75f))
                }
                    .minByOrNull { it.bounds.top }
                if(landing != null) {
                    current = landing; feet = Offset(safeX(landing, feet.x), landing.bounds.top)
                    airborne = false; vx = 0f; vy = 0f
                    val nextTouch = pendingTouch
                    pendingTouch = null
                    if(nextTouch != null) {
                        clearRoute()
                        target(nextTouch, ripple = false)
                    } else if(route.firstOrNull() == landing.id) route = route.drop(1)
                    else if(returningToRest && startRestRoute(landing)) {
                        // Continue to the favorite after an intermediate landing.
                        hopCooldown = max(hopCooldown, cooldown)
                    }
                    else {
                        // A missed target must not cause an endless bounce on the same ledge.
                        clearRoute()
                        automaticRoute = true
                        hopCooldown = max(hopCooldown, cooldown); decisionIn = hopCooldown
                    }
                }
            }
        }
        feet = feet.copy(x = feet.x.coerceIn(state.sizePx / 2, max(state.sizePx / 2, world.right - state.sizePx / 2)), y = feet.y.coerceAtMost(world.bottom))
        val mode = when { state.notificationCount > 0 -> PetMode.COURIER; happyFor > 0 -> PetMode.HAPPY
            power.plugged -> PetMode.EATING; sleeping -> PetMode.SLEEPING
            airborne -> PetMode.HOPPING; abs(vx) > .1f -> PetMode.WALKING; else -> PetMode.IDLE }
        state = state.copy(feet = feet, mode = mode, facing = if(abs(vx) > 1) if(vx > 0) 1 else -1 else state.facing, platformId = current?.id)
    }
}

private const val ROAM_BEFORE_REST_SECONDS = 25f

/** Fixed storage, shared on the UI thread between physics and Canvas; never creates a coroutine/timer. */
class ParticleField(seed: Int, val count: Int = 128) {
    private val random = Random(seed)
    val x = FloatArray(count); val y = FloatArray(count); val vy = FloatArray(count)
    val splash = FloatArray(count)
    val splashX = FloatArray(count); val splashY = FloatArray(count)
    private val bounced = BooleanArray(count)
    private var previousEffect: PetWeather? = null
    var activeCount = count; private set
    var effect = PetWeather.NONE
        private set
    fun reset(area: Rect) { repeat(count) { x[it] = random.nextFloat() * area.width; y[it] = area.top + random.nextFloat() * area.height; vy[it] = 0f; splash[it] = 0f; bounced[it] = false } }
    private fun respawn(i: Int, area: Rect) {
        x[i] = random.nextFloat() * area.width; y[i] = area.top; vy[i] = 0f; splash[i] = 0f; bounced[i] = false
    }
    fun step(dt: Float, weather: PetWeather, platforms: List<PlatformSurface>, area: Rect,
        intensity: Float = .5f, speed: Float = 1f, density: Float = 1f) {
        effect = weather
        if(previousEffect != null && weather != previousEffect) reset(area)
        previousEffect = weather
        activeCount = (count * intensity.coerceIn(.1f, 1f)).roundToInt().coerceIn(1, count)
        if(weather == PetWeather.NONE || area.width <= 0) return
        val elapsed = dt * speed
        repeat(activeCount) { i ->
            splash[i] = (splash[i] - dt).coerceAtLeast(0f)
            if(bounced[i] && splash[i] <= 0) { respawn(i, area); return@repeat }
            val before = y[i]
            x[i] = (x[i] + elapsed * density * if(weather == PetWeather.RAIN) 20f else 6f + i % 11).mod(area.width)
            if(weather == PetWeather.RAIN) vy[i] = (vy[i] + elapsed * 900 * density).coerceAtMost(420f * density)
            else vy[i] = if(weather == PetWeather.SNOW) (24f + i % 30) * density else sin(x[i] * .015f) * 12 * density
            y[i] += vy[i] * elapsed
            if(weather == PetWeather.RAIN && !bounced[i] && vy[i] > 0) {
                val hit = platforms.filter { x[i] >= it.bounds.left && x[i] <= it.bounds.right && before <= it.bounds.top && y[i] >= it.bounds.top }.minByOrNull { it.bounds.top }
                if(hit != null) { y[i] = hit.bounds.top; vy[i] = -abs(vy[i]) * .28f; splash[i] = .4f
                    splashX[i] = x[i]; splashY[i] = y[i]; bounced[i] = true }
            }
            if(y[i] > area.bottom || y[i] < area.top) respawn(i, area)
        }
    }
}

class PetViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = PetRepository(application.petDataStore)
    val settings = repository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, PetSettings())
    internal val engine = PetEngine()
    private val mutable = MutableStateFlow(engine.state)
    val ui: StateFlow<PetUiState> = mutable.asStateFlow()
    private val image = MutableStateFlow<ImageBitmap?>(null)
    val photo = image.asStateFlow()
    private val errors = MutableStateFlow<String?>(null)
    val error = errors.asStateFlow()
    internal var frameCount = 0L; private set
    internal var running = false; private set
    internal var powerRegistrations = 0; private set
    private var power = PowerState()
    private var lastStats = 0L; private var lastSave = 0L; private var lastEnvironment = 0L
    private var parallax = Offset.Zero
    private val photoFile get() = File(getApplication<Application>().filesDir, "pet-wallpaper.jpg")
    init { viewModelScope.launch { engine.restore(repository.resume(System.currentTimeMillis())); publish(); reloadPhoto() } }
    private fun publish() { mutable.value = engine.state }
    fun configure(change: (PetSettings) -> PetSettings) { viewModelScope.launch { repository.update(change) } }
    fun viewport(width: Int, height: Int, density: Float, bottom: Int) { engine.viewport(width.toFloat(), height.toFloat(), density, bottom.toFloat()); publish() }
    fun surfaces(value: List<PlatformSurface>) { engine.surfaces(value); publish() }
    suspend fun resume() {
        engine.restore(repository.resume(System.currentTimeMillis()))
        running = true; environment(System.currentTimeMillis()); publish()
    }
    fun pause() { running = false; parallax = Offset.Zero; persist() }
    fun motion(value: Offset) { parallax = value }
    fun power(value: PowerState) { power = value; environment(System.currentTimeMillis()); publish() }
    private fun environment(now: Long) {
        val hour = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).hour
        engine.environment(power, hour >= 22 || hour < 6); lastEnvironment = now
    }
    fun frame(seconds: Float, now: Long = System.currentTimeMillis()) {
        if(!running) return
        frameCount++
        if(now - lastEnvironment >= 30_000) environment(now)
        if(now - lastStats >= 1000) { engine.advanceStats(now); lastStats = now }
        val config = settings.value
        engine.tick(seconds, if(config.wallpaperEnabled) config.weather else PetWeather.NONE, parallax, config); publish()
        if(now - lastSave >= 60_000) persist()
    }
    fun hit(point: Offset): Boolean = settings.value.enabled && engine.state.let { it.visible && Rect(it.feet.x - it.sizePx / 2, it.feet.y - it.sizePx, it.feet.x + it.sizePx / 2, it.feet.y).contains(point) }
    fun touch(point: Offset) { if(hit(point)) pat() else { engine.target(point); publish() } }
    fun pat() { engine.pat(System.currentTimeMillis()); publish(); persist() }
    fun clean() { engine.clean(System.currentTimeMillis()); publish(); persist() }
    fun messages(active: pl.violetglass.launcher.notifications.PetNotificationSources) {
        val config = settings.value
        engine.notifications(config.messageCount(if(config.messagesOnly) active.messages else active.all)); publish()
    }
    private fun persist() {
        engine.advanceStats(System.currentTimeMillis()); publish()
        val snapshot = engine.state.stats; lastSave = System.currentTimeMillis()
        if(snapshot.lastUpdatedTimestamp > 0 && engine.state.ready) viewModelScope.launch { repository.save(snapshot) }
    }
    fun powerEvents(): Flow<PowerState> = callbackFlow {
        val context = getApplication<Application>()
        fun read(intent: Intent?): PowerState {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) ?: 100
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
            return PowerState((level * 100 / scale).coerceIn(0, 100), (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when(intent.action) {
                    Intent.ACTION_POWER_CONNECTED -> trySend(power.copy(plugged = true))
                    Intent.ACTION_POWER_DISCONNECTED -> trySend(power.copy(plugged = false))
                    else -> trySend(read(intent))
                }
            }
        }
        val initial = ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        powerRegistrations++; trySend(read(initial))
        awaitClose { context.unregisterReceiver(receiver); powerRegistrations-- }
    }.conflate()
    private suspend fun reloadPhoto() { image.value = withContext(Dispatchers.IO) { if(photoFile.exists()) BitmapFactory.decodeFile(photoFile.path)?.asImageBitmap() else null } }
    fun choosePhoto(uri: Uri) { viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) {
                val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)) { decoder, info, _ ->
                    val sample = max(1, ceil(max(info.size.width, info.size.height) / 2048f).toInt())
                    decoder.setTargetSampleSize(sample); decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
                }
                val temporary = File(photoFile.parentFile, "pet-wallpaper.tmp")
                try { temporary.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) }; check(temporary.renameTo(photoFile)) }
                finally { bitmap.recycle(); temporary.delete() }
            }
            reloadPhoto(); errors.value = null
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { errors.value = "Nie udało się wczytać zdjęcia. Wybierz je ponownie." }
    } }
    fun systemPhoto() { viewModelScope.launch { withContext(Dispatchers.IO) { photoFile.delete() }; image.value = null; errors.value = null } }
}
