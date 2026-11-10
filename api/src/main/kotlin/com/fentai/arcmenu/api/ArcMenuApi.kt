package com.fentai.arcmenu.api

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

/**
 * Public Java API published through Bukkit's ServicesManager.
 *
 * Threading contract (advertised by [ArcMenuCapability.REGION_THREADING]):
 * - Callable from any thread: [apiVersion], [capabilities], [applications], [pointerPolicy], [registerRoute],
 *   [unregisterRoutes], [registerApplication], [unregisterApplications], [close], [runFor], [openAsync],
 *   [openApplicationAsync] and every [ArcMenuApplicationHandle] method.
 * - Every other method taking a Player must run on the thread that owns that player (Paper: the main thread;
 *   Folia: the region thread that currently owns the player) and throws IllegalStateException elsewhere.
 *   From another thread use [runFor] or the `*Async` variants.
 * - Routes and application callbacks run on the owning thread of their player. Callbacks of one player are
 *   serial; on Folia callbacks of different players may run concurrently on different region threads, so
 *   state shared between players must be thread-safe.
 */
interface ArcMenuApi {
    fun apiVersion(): Int
    fun capabilities(): Set<ArcMenuCapability>
    fun open(player: Player, menuId: String, arguments: List<String>): Boolean
    fun open(player: Player, menuId: String): Boolean = open(player, menuId, emptyList())
    fun openApplication(player: Player, applicationId: String, arguments: List<String>): Boolean
    fun openApplication(player: Player, applicationId: String): Boolean = openApplication(player, applicationId, emptyList())
    /** Any thread: closes on the player's owning thread (inline when already there); offline players are ignored. */
    fun close(player: Player)
    fun playAnimation(player: Player, animationId: String): Boolean
    fun stopAnimation(player: Player, animationId: String): Boolean
    fun animations(player: Player): Set<String>
    fun registerRoute(owner: Plugin, routeId: String, route: ArcMenuRoute)
    fun unregisterRoutes(owner: Plugin)
    fun dispatchRoute(player: Player, routeId: String, arguments: List<String>): Boolean
    fun registerApplication(owner: Plugin, applicationId: String, application: ArcMenuApplication): ArcMenuApplicationHandle
    fun registerApplication(
        owner: Plugin,
        applicationId: String,
        options: ArcMenuApplicationOptions,
        application: ArcMenuApplication,
    ): ArcMenuApplicationHandle
    fun unregisterApplications(owner: Plugin)
    fun applications(): Set<String>
    fun activeApplication(player: Player): String?
    fun surface(player: Player): ArcMenuSurface?
    fun pointer(player: Player): ArcMenuPoint?
    fun pointerMode(player: Player): ArcMenuPointerMode
    /** Returns false when server policy rejects the requested player preference. */
    fun setPointerMode(player: Player, mode: ArcMenuPointerMode): Boolean
    fun pointerPolicy(): ArcMenuPointerPolicy

    /**
     * Any thread: runs [task] on the thread that owns [player], inline when the caller already owns it.
     * Returns false when the task cannot be scheduled (player offline or ArcMenu disabled).
     */
    fun runFor(player: Player, task: Consumer<ArcMenuApi>): Boolean {
        task.accept(this)
        return true
    }

    /** Any thread: [open] on the player's owning thread; completes with false when it cannot be scheduled. */
    fun openAsync(player: Player, menuId: String, arguments: List<String>): CompletableFuture<Boolean> =
        forPlayer(player) { it.open(player, menuId, arguments) }

    fun openAsync(player: Player, menuId: String): CompletableFuture<Boolean> = openAsync(player, menuId, emptyList())

    /** Any thread: [openApplication] on the player's owning thread; completes with false when it cannot be scheduled. */
    fun openApplicationAsync(player: Player, applicationId: String, arguments: List<String>): CompletableFuture<Boolean> =
        forPlayer(player) { it.openApplication(player, applicationId, arguments) }

    fun openApplicationAsync(player: Player, applicationId: String): CompletableFuture<Boolean> =
        openApplicationAsync(player, applicationId, emptyList())

    private fun forPlayer(player: Player, operation: (ArcMenuApi) -> Boolean): CompletableFuture<Boolean> {
        val result = CompletableFuture<Boolean>()
        val scheduled = runFor(player, Consumer { api ->
            try { result.complete(operation(api)) } catch (error: Throwable) { result.completeExceptionally(error) }
        })
        if (!scheduled) result.complete(false)
        return result
    }
}

/** A namespaced virtual route owned by an addon plugin. Java may use this as a SAM interface. */
fun interface ArcMenuRoute {
    fun open(player: Player, arguments: List<String>): Boolean
}

enum class ArcMenuPointerMode { TOUCH, MOUSE }
enum class ArcMenuPointerPolicy { PLAYER_CHOICE, FORCE_TOUCH, FORCE_MOUSE }

enum class ArcMenuCapability {
    APPLICATION_SESSIONS,
    APPLICATION_NAVIGATION,
    SCREEN_SURFACE,
    POINTER_EVENTS,
    MOUSE_HOTBAR_SCROLL,
    TRACKED_RESOURCES,
    SURFACE_REANCHOR,
    /** The region-threading contract documented on [ArcMenuApi], including [ArcMenuApi.runFor] and the async open methods. */
    REGION_THREADING,
}
