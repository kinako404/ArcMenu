package com.fentai.arcmenu.paper.api

import com.fentai.arcmenu.api.ArcMenuApi
import com.fentai.arcmenu.api.ArcMenuApplicationContext
import com.fentai.arcmenu.api.ArcMenuCapability
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

class ArcMenuApiDefaultsTest {
    private val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, _, _ -> null } as Player

    @Test
    fun `region threading is advertised as an appended capability`() {
        assertEquals(ArcMenuCapability.entries.last(), ArcMenuCapability.REGION_THREADING)
        assertEquals(ArcMenuCapability.SURFACE_REANCHOR.ordinal + 1, ArcMenuCapability.REGION_THREADING.ordinal)
    }

    @Test
    fun `openAsync runs open on the thread chosen by runFor`() {
        val owner = Executors.newSingleThreadExecutor { Thread(it, "player-owner") }
        try {
            var openedOn = ""
            val api = api { method, args, self ->
                when (method) {
                    "runFor" -> { owner.execute { @Suppress("UNCHECKED_CAST") (args[1] as Consumer<ArcMenuApi>).accept(self) }; true }
                    "open" -> { openedOn = Thread.currentThread().name; args[1] == "shop" }
                    else -> error(method)
                }
            }
            assertTrue(api.openAsync(player, "shop").get(5, TimeUnit.SECONDS))
            assertEquals("player-owner", openedOn)
        } finally {
            owner.shutdownNow()
        }
    }

    @Test
    fun `async open completes with false when the player cannot be scheduled`() {
        val opened = AtomicBoolean(false)
        val api = api { method, _, _ ->
            when (method) {
                "runFor" -> false
                "open", "openApplication" -> { opened.set(true); true }
                else -> error(method)
            }
        }
        assertFalse(api.openAsync(player, "shop", listOf("a")).get(1, TimeUnit.SECONDS))
        assertFalse(api.openApplicationAsync(player, "addon:shop").get(1, TimeUnit.SECONDS))
        assertFalse(opened.get())
    }

    @Test
    fun `failures inside the scheduled open complete the future exceptionally`() {
        val api = api { method, args, self ->
            when (method) {
                "runFor" -> { @Suppress("UNCHECKED_CAST") (args[1] as Consumer<ArcMenuApi>).accept(self); true }
                "openApplication" -> throw IllegalStateException("boom")
                else -> error(method)
            }
        }
        val future = api.openApplicationAsync(player, "addon:shop")
        assertTrue(future.isCompletedExceptionally)
    }

    @Test
    fun `tracking a region task cancels it with the session resources`() {
        val tracked = mutableListOf<AutoCloseable>()
        val context = Proxy.newProxyInstance(ArcMenuApplicationContext::class.java.classLoader, arrayOf(ArcMenuApplicationContext::class.java),
            InvocationHandler { proxy, method, args ->
                when {
                    method.isDefault -> InvocationHandler.invokeDefault(proxy, method, *(args ?: emptyArray()))
                    method.name == "track" && args?.get(0) is AutoCloseable -> { tracked += args[0] as AutoCloseable; args[0] }
                    else -> error(method.name)
                }
            }) as ArcMenuApplicationContext
        val cancelled = AtomicBoolean(false)
        val task = Proxy.newProxyInstance(ScheduledTask::class.java.classLoader, arrayOf(ScheduledTask::class.java)) { _, method, _ ->
            if (method.name == "cancel") { cancelled.set(true); null } else null
        } as ScheduledTask

        assertSame(task, context.track(task))
        assertEquals(1, tracked.size)
        assertFalse(cancelled.get())
        tracked.single().close()
        assertTrue(cancelled.get())
    }

    private fun api(handler: (String, Array<Any?>, ArcMenuApi) -> Any?): ArcMenuApi =
        Proxy.newProxyInstance(ArcMenuApi::class.java.classLoader, arrayOf(ArcMenuApi::class.java), InvocationHandler { proxy, method, args ->
            val arguments = args ?: emptyArray()
            if (method.isDefault && method.name != "runFor") InvocationHandler.invokeDefault(proxy, method, *arguments)
            else handler(method.name, arguments, proxy as ArcMenuApi)
        }) as ArcMenuApi
}
