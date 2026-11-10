package com.fentai.arcmenu.paper.platform

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.TimeUnit

/**
 * Region-aware scheduling shared by Paper and Folia. Paper implements the same
 * entity/global/async schedulers on its single main thread, so ArcMenu never uses
 * the legacy BukkitScheduler (it throws UnsupportedOperationException on Folia).
 */
internal object ArcScheduler {
    val folia: Boolean = runCatching {
        Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
    }.isSuccess

    fun stopping(): Boolean = Bukkit.isStopping()

    fun owns(entity: Entity): Boolean = if (folia) Bukkit.isOwnedByCurrentRegion(entity) else Bukkit.isPrimaryThread()

    /** True where entity state may be touched directly: its owning thread, or shutdown. */
    fun mayTouch(entity: Entity): Boolean = stopping() || owns(entity)

    fun ownsGlobal(): Boolean = if (folia) Bukkit.isGlobalTickThread() else Bukkit.isPrimaryThread()

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
     * entities in another Folia region, where a direct remove() would throw.
     */
    fun remove(plugin: Plugin, entity: Entity) {
        if (mayTouch(entity) || !plugin.isEnabled) {
            runCatching { entity.remove() }
            return
        }
        entity.scheduler.execute(plugin, Runnable { entity.remove() }, null, 1L)
    }

    /** Exposes a region task through the public BukkitTask-based application API. */
    fun bukkitTask(plugin: Plugin, task: ScheduledTask?, sync: Boolean = true): BukkitTask = ScheduledBukkitTask(plugin, task, sync)

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
