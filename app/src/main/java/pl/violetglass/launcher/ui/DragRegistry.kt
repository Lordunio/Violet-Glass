package pl.violetglass.launcher.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import pl.violetglass.launcher.workspace.ItemPayload
import pl.violetglass.launcher.workspace.Slot

class DragRegistry {
    val sources = mutableMapOf<String, Pair<Rect, ItemPayload>>()
    val targets = mutableMapOf<Slot, Rect>()
    internal val sourceOwners = mutableMapOf<String, Any>()
    internal val targetOwners = mutableMapOf<Slot, Any>()
}

@Composable
fun Modifier.sourceBounds(registry: DragRegistry, key: String, payload: ItemPayload): Modifier {
    val owner = remember(registry, key) { Any() }
    DisposableEffect(registry, key, owner) { onDispose {
        if(registry.sourceOwners[key] === owner) { registry.sources.remove(key); registry.sourceOwners.remove(key) }
    } }
    return this.onGloballyPositioned { registry.sourceOwners[key] = owner; registry.sources[key] = it.boundsInRoot() to payload }
}

@Composable
fun Modifier.targetBounds(registry: DragRegistry, slot: Slot): Modifier {
    val owner = remember(registry, slot) { Any() }
    DisposableEffect(registry, slot, owner) { onDispose {
        if(registry.targetOwners[slot] === owner) { registry.targets.remove(slot); registry.targetOwners.remove(slot) }
    } }
    return this.onGloballyPositioned { registry.targetOwners[slot] = owner; registry.targets[slot] = it.boundsInRoot() }
}

