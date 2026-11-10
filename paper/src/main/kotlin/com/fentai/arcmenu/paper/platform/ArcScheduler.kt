package com.fentai.arcmenu.paper.platform

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

/**
 * Region-aware scheduling shared by Paper and Folia. Paper implements the same
 * entity/global/async schedulers on its single main thread, so ArcMenu never uses
 * the legacy BukkitScheduler (it throws UnsupportedOperationException on Folia).
 *
 * Entity tasks are silently dropped (`false`/`null`) once the entity's scheduler is
 * retired, which for players means they have disconnected. On Folia a task that comes
 * due while its player is between regions (async teleport, dimension change, respawn)
 * is also dropped; repeating work must re-arm itself from its retired callback.
 */
internal object ArcScheduler {
    val folia: Boolean = runCatching {
        Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
    }.isSuccess

    private val globalTickCheck: Method? = globalTickMethod(Bukkit::class.java) ?: runCatching {
        globalTickMethod(Class.forName("io.papermc.paper.threadedregions.RegionizedServer"))
    }.getOrNull()

    fun stopping(): Boolean = Bukkit.isStopping()

    fun owns(entity: Entity): Boolean = if (folia) Bukkit.isOwnedByCurrentRegion(entity) else Bukkit.isPrimaryThread()

    /** True when the current thread may spawn into or modify the chunk containing [location]. */
    fun owns(location: Location): Boolean = if (folia) Bukkit.isOwnedByCurrentRegion(location) else Bukkit.isPrimaryThread()

    /** True where entity state may be touched directly: its owning thread, or shutdown. */
    fun mayTouch(entity: Entity): Boolean = stopping() || owns(entity)

    fun ownsGlobal(): Boolean = if (folia) isGlobalTickThread() else Bukkit.isPrimaryThread()

    fun execute(plugin: Plugin, entity: Entity, task: Runnable): Boolean {
        if (owns(entity)) { task.run(); return true }
        if (!plugin.isEnabled) return false
        return entity.scheduler.execute(plugin, task, null, 1L)
    }

    fun later(plugin: Plugin, entity: Entity, task: Runnable, delay: Long): ScheduledTask? {
        if (!plugin.isEnabled) return null
        return entity.scheduler.runDelayed(plugin, { task.run() }, null, delay.coerceAtLeast(1L))
    }

    fun timer(plugin: Plugin, entity: Entity, delay: Long, period: Long, task: Runnable, retired: Runnable? = null): ScheduledTask? {
        if (!plugin.isEnabled) return null
        return entity.scheduler.runAtFixedRate(plugin, { task.run() }, retired, delay.coerceAtLeast(1L), period.coerceAtLeast(1L))
    }

    /** Runs on the global region now when already there, otherwise on its next tick. */
    fun global(plugin: Plugin, task: Runnable) {
        if (ownsGlobal()) task.run()
        else if (plugin.isEnabled) Bukkit.getGlobalRegionScheduler().execute(plugin, task)
    }

    fun globalLater(plugin: Plugin, task: Runnable, delay: Long = 1L): ScheduledTask? {
        if (!plugin.isEnabled) return null
        return Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { task.run() }, delay.coerceAtLeast(1L))
    }

    fun asyncLater(plugin: Plugin, task: Runnable, delayTicks: Long): ScheduledTask? {
        if (!plugin.isEnabled) return null
        return Bukkit.getAsyncScheduler().runDelayed(plugin, { task.run() }, delayTicks.coerceAtLeast(1L) * 50L, TimeUnit.MILLISECONDS)
    }

    /**
     * Removes an entity from its owning thread. A teleported player leaves its old menu
     * entities in another Folia region, where a direct remove() would throw. After a
     * Folia hot disable no task can reach that region; the non-persistent entity is left
     * for its chunk to discard.
     */
    fun remove(plugin: Plugin, entity: Entity) {
        if (mayTouch(entity)) {
            runCatching { entity.remove() }
            return
        }
        if (!plugin.isEnabled) return
        entity.scheduler.execute(plugin, Runnable { entity.remove() }, null, 1L)
    }

    /** Exposes a region task through the public BukkitTask-based application API. */
    fun bukkitTask(plugin: Plugin, task: ScheduledTask?, sync: Boolean = true): BukkitTask = ScheduledBukkitTask(plugin, task, sync)

    private fun isGlobalTickThread(): Boolean =
        globalTickCheck?.let { runCatching { it.invoke(null) as Boolean }.getOrNull() } ?: Bukkit.isPrimaryThread()

    private class ScheduledBukkitTask(
        private val plugin: Plugin,
        private val task: ScheduledTask?,
        private val sync: Boolean,
    ) : BukkitTask {
        override fun getTaskId(): Int = -1
        override fun getOwner(): Plugin = plugin
        override fun isSync(): Boolean = sync
        override fun isCancelled(): Boolean = task == null || task.isCancelled
        override fun cancel() { task?.cancel() }
    }
}

/** `Bukkit.isGlobalTickThread()` only exists from the 1.21.4 API; ArcMenu compiles against 1.21.1. */
internal fun globalTickMethod(owner: Class<*>): Method? = runCatching {
    owner.getMethod("isGlobalTickThread").takeIf {
        java.lang.reflect.Modifier.isStatic(it.modifiers) && it.returnType == Boolean::class.javaPrimitiveType
    }
}.getOrNull()
