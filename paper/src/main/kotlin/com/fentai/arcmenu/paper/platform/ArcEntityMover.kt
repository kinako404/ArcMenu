package com.fentai.arcmenu.paper.platform

import com.fentai.arcmenu.core.model.Vec3
import org.bukkit.Location
import org.bukkit.entity.Entity
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Moves a private menu entity a short distance in place. Folia rejects Entity.teleport and its
 * teleportAsync re-adds the entity (the client sees a despawn/respawn, so Display teleport
 * interpolation is lost). Setting the server position directly lets the entity tracker send an
 * ordinary move packet, which Display entities interpolate over their teleport duration.
 * The caller must own the entity, and the move must stay inside the owning region.
 */
internal object ArcEntityMover {
    private class Handle(val getHandle: Method, val setPos: Method, val hasImpulse: Field?)

    private val handles = ConcurrentHashMap<Class<*>, Handle>()

    fun moveBy(entity: Entity, offset: Vec3) {
        val location = entity.location
        val x = location.x + offset.x
        val y = location.y + offset.y
        val z = location.z + offset.z
        val handle = runCatching { handle(entity.javaClass) }.getOrNull()
        if (handle == null) {
            if (!ArcScheduler.folia) entity.teleport(Location(location.world, x, y, z, location.yaw, location.pitch))
            return
        }
        val nms = handle.getHandle.invoke(entity)
        handle.setPos.invoke(nms, x, y, z)
        // Entities with a slow tracker interval (interaction: every 3 ticks) send the move next tick.
        handle.hasImpulse?.setBoolean(nms, true)
    }

    private fun handle(type: Class<*>): Handle = handles.getOrPut(type) {
        val getHandle = type.getMethod("getHandle")
        val nmsType = getHandle.returnType
        val setPos = nmsType.getMethod("setPos", Double::class.javaPrimitiveType, Double::class.javaPrimitiveType,
            Double::class.javaPrimitiveType)
        val impulse = generateSequence<Class<*>>(nmsType) { it.superclass }
            .firstNotNullOfOrNull { owner -> runCatching { owner.getDeclaredField("hasImpulse") }.getOrNull() }
            ?.takeIf { it.type == Boolean::class.javaPrimitiveType && it.trySetAccessible() }
        Handle(getHandle, setPos, impulse)
    }
}
