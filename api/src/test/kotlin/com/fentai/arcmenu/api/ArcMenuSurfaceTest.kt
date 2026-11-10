package com.fentai.arcmenu.api

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.util.Vector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class ArcMenuSurfaceTest {
    @Test
    fun `logical axes convert to world coordinates without exposing mutable basis`() {
        val world = Proxy.newProxyInstance(World::class.java.classLoader, arrayOf(World::class.java)) { proxy, method, args ->
            when (method.name) {
                "getName" -> "test"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> null
            }
        } as World
        val surface = ArcMenuSurface(
            world,
            Location(world, 10.0, 20.0, 30.0),
            Vector(1.0, 0.0, 0.0),
            Vector(0.0, 1.0, 0.0),
            Vector(0.0, 0.0, 1.0),
            ArcMenuCanvas(320.0, 180.0, 100.0, 3.0),
        )

        val point = surface.toWorld(50.0, -25.0, 10.0)
        assertEquals(10.5, point.x, 1.0e-9)
        assertEquals(19.75, point.y, 1.0e-9)
        assertEquals(30.1, point.z, 1.0e-9)

        surface.origin().add(100.0, 100.0, 100.0)
        assertEquals(10.0, surface.origin().x, 1.0e-9)
    }

    @Test
    fun `surface move event is optional for sessions and does not expose a mutable offset`() {
        val session = object : ArcMenuApplicationSession() {}
        val world = Proxy.newProxyInstance(World::class.java.classLoader, arrayOf(World::class.java)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> null
            }
        } as World
        val surface = ArcMenuSurface(world, Location(world, 0.0, 0.0, 0.0), Vector(1, 0, 0), Vector(0, 1, 0),
            Vector(0, 0, 1), ArcMenuCanvas(320.0, 180.0, 100.0, 3.0))
        val offset = Vector(0.0, -0.35, 0.0)
        val event = ArcMenuSurfaceMoveEvent(surface, offset, ArcMenuSurfaceMoveEvent.DEFAULT_DURATION_TICKS)
        session.onSurfaceMoved(event)
        offset.setY(5.0)
        event.offset().setY(7.0)
        assertEquals(-0.35, event.offset().y, 1.0e-9)
        assertEquals(2, event.durationTicks)
    }

    @Test
    fun `application option defaults are safe and explicit`() {
        val options = ArcMenuApplicationOptions.DEFAULT
        assertEquals(true, options.inheritCanvas)
        assertEquals(false, options.captureMouseScroll)
        assertEquals(1, options.tickInterval)
        assertEquals(320.0, options.canvas.width)
        assertEquals(180.0, options.canvas.height)
    }
}
