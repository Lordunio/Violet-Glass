package pl.violetglass.launcher.pet

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlin.math.*

internal class ParallaxFilter {
    private var neutral: Offset? = null
    private var filtered = Offset.Zero
    private var timestamp = 0L
    fun update(pitch: Float, roll: Float, nanos: Long): Offset {
        if(!pitch.isFinite() || !roll.isFinite()) return filtered
        val zero = neutral ?: Offset(pitch, roll).also { neutral = it }
        fun difference(value: Float) = atan2(sin(value), cos(value)).coerceIn(-.35f, .35f) / .35f
        val target = Offset(difference(roll - zero.y), difference(pitch - zero.x))
        val dt = if(timestamp == 0L) 0f else ((nanos - timestamp).coerceAtLeast(0) / 1e9).toFloat().coerceAtMost(.2f)
        timestamp = nanos
        filtered += (target - filtered) * (1f - exp(-dt / .18f))
        return filtered
    }
}

class SensorParallaxController(context: Context, private val rotation: () -> Int = { Surface.ROTATION_0 }) {
    private val manager = context.applicationContext.getSystemService(SensorManager::class.java)
    internal var registrations = 0
        private set
    fun offsets(lifecycle: Lifecycle): Flow<Offset> = callbackFlow {
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if(sensor == null) { trySend(Offset.Zero); awaitClose {}; return@callbackFlow }
        val matrix = FloatArray(9); val remapped = FloatArray(9); val orientation = FloatArray(3)
        val filter = ParallaxFilter()
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                val axes = when(rotation()) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(matrix, axes.first, axes.second, remapped)
                SensorManager.getOrientation(remapped, orientation)
                trySend(filter.update(orientation[1], orientation[2], event.timestamp))
            }
        }
        val registered = manager.registerListener(listener, sensor, 20_000)
        if(registered) registrations++ else trySend(Offset.Zero)
        awaitClose { manager.unregisterListener(listener); if(registered) registrations-- }
    }.conflate().flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
}
