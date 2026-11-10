package com.fentai.arcmenu.paper.api

import com.fentai.arcmenu.api.*
import com.fentai.arcmenu.paper.input.PointerController
import com.fentai.arcmenu.paper.input.PointerMode
import com.fentai.arcmenu.paper.runtime.MenuSessions
import com.fentai.arcmenu.paper.platform.ArcScheduler
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID

internal class ArcMenuApiImpl(
    private val sessions: MenuSessions,
    private val routes: ExtensionRoutes,
    private val applications: ApplicationRegistry,
    private val pointer: PointerController,
) : ArcMenuApi {
    override fun apiVersion(): Int = sync { 1 }

    override fun capabilities(): Set<ArcMenuCapability> = sync { ArcMenuCapability.entries.toSet() }

    override fun open(player: Player, menuId: String, arguments: List<String>): Boolean = sync(player) {
        sessions.open(player, menuId.lowercase(), remember = true, arguments = arguments.toList())
    }

    override fun close(player: Player) = sync<Unit>(player) { sessions.close(player, true) }

    override fun openApplication(player: Player, applicationId: String, arguments: List<String>): Boolean = sync(player) {
        sessions.openApplication(player, applicationId.lowercase(), remember = true, arguments = arguments.toList())
    }

    override fun playAnimation(player: Player, animationId: String): Boolean = sync(player) {
        sessions.playAnimation(player, animationId)
    }

    override fun stopAnimation(player: Player, animationId: String): Boolean = sync(player) {
        sessions.stopAnimation(player, animationId)
    }

    override fun animations(player: Player): Set<String> = sync(player) { sessions.animations(player) }

    override fun registerRoute(owner: Plugin, routeId: String, route: ArcMenuRoute) = sync<Unit> {
        routes.register(owner, routeId, route)
    }

    override fun unregisterRoutes(owner: Plugin) = sync<Unit> { routes.unregister(owner) }

    override fun dispatchRoute(player: Player, routeId: String, arguments: List<String>): Boolean = sync(player) {
        routes.dispatch(player, routeId, arguments) ?: false
    }

    override fun registerApplication(
        owner: Plugin,
        applicationId: String,
        application: ArcMenuApplication,
    ): ArcMenuApplicationHandle = registerApplication(owner, applicationId, ArcMenuApplicationOptions.DEFAULT, application)

    override fun registerApplication(
        owner: Plugin,
        applicationId: String,
        options: ArcMenuApplicationOptions,
        application: ArcMenuApplication,
    ): ArcMenuApplicationHandle = sync {
        val registration = applications.register(owner, applicationId, options, application)
        ApplicationHandle(registration.token, registration.id, owner)
    }

    override fun unregisterApplications(owner: Plugin) = sync<Unit> {
        sessions.closeApplications(owner)
        applications.unregister(owner)
    }

    override fun applications(): Set<String> = sync { applications.ids() }

    override fun activeApplication(player: Player): String? = sync(player) { sessions.activeApplication(player) }

    override fun surface(player: Player): ArcMenuSurface? = sync(player) { sessions.surface(player) }

    override fun pointer(player: Player): ArcMenuPoint? = sync(player) { sessions.pointerPoint(player) }

    override fun pointerMode(player: Player): ArcMenuPointerMode = sync(player) {
        ArcMenuPointerMode.valueOf(pointer.mode(player).name)
    }

    override fun setPointerMode(player: Player, mode: ArcMenuPointerMode): Boolean = sync(player) {
        val result = pointer.setPreference(player, PointerMode.valueOf(mode.name))
        if (result.accepted) sessions.refreshPointerMode(player)
        result.accepted
    }

    override fun pointerPolicy(): ArcMenuPointerPolicy = sync {
        ArcMenuPointerPolicy.valueOf(pointer.settings.policy.name)
    }

    /** Registry operations are thread-safe; on Folia they may come from the global or any region thread. */
    private fun <T> sync(operation: () -> T): T = operation()

    /** Player operations must run on the thread that owns the player (Paper: the main thread). */
    private fun <T> sync(player: Player, operation: () -> T): T {
        check(ArcScheduler.mayTouch(player)) { "ArcMenu 玩家 API 必须在玩家所属的区域线程调用（Paper 为主线程）" }
        return operation()
    }

    private inner class ApplicationHandle(
        private val token: UUID,
        override val applicationId: String,
        override val owner: Plugin,
    ) : ArcMenuApplicationHandle {
        override fun isRegistered(): Boolean = sync { applications.contains(token) }

        override fun unregister() = sync<Unit> {
            sessions.closeApplicationRegistration(token)
            applications.unregister(token)
        }
    }
}
