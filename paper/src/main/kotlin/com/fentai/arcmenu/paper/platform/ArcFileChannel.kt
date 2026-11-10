package com.fentai.arcmenu.paper.platform

import org.bukkit.plugin.Plugin
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The single thread that reads and writes ArcMenu's data folder, parses candidate catalogs and
 * serializes every reload. Publication that touches server state (command map, catalog swap,
 * session fan-out) is handed to the global region with [onGlobal]; the channel thread waits
 * for it so file transactions can still roll back when publication fails. Tick threads must
 * never block on this channel.
 */
internal class ArcFileChannel(
    name: String,
    private val enabled: () -> Boolean,
    private val global: (Runnable) -> Unit,
) : AutoCloseable {
    constructor(plugin: Plugin) : this("ArcMenu-files", plugin::isEnabled, { task -> ArcScheduler.global(plugin, task) })

    @Volatile private var thread: Thread? = null
    @Volatile private var closed = false
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, name).apply { isDaemon = true; thread = this }
    }

    fun owns(): Boolean = Thread.currentThread() === thread

    /** Queues [work] behind every earlier file operation; runs inline when already on the channel. */
    fun <T> submit(work: () -> T): CompletableFuture<T> {
        if (owns()) return try {
            CompletableFuture.completedFuture(work())
        } catch (error: Throwable) {
            CompletableFuture.failedFuture(error)
        }
        val future = CompletableFuture<T>()
        if (closed) return future.also { it.completeExceptionally(IllegalStateException("ArcMenu file channel is closed")) }
        try {
            executor.execute {
                try { future.complete(work()) } catch (error: Throwable) { future.completeExceptionally(error) }
            }
        } catch (error: Exception) {
            future.completeExceptionally(error)
        }
        return future
    }

    /** Runs [work] on the channel and waits for it. Only for worker threads, never for tick threads. */
    fun <T> call(work: () -> T): T = await(submit(work))

    /**
     * Runs [work] on the global region (Paper: the main thread) and waits for its result.
     * Must be called from the channel thread; aborts when the plugin is disabled meanwhile.
     */
    fun <T> onGlobal(work: () -> T): T {
        check(owns()) { "ArcMenu global publication must start on the file channel" }
        val future = CompletableFuture<T>()
        global(Runnable {
            if (closed || !enabled()) future.cancel(false)
            else try { future.complete(work()) } catch (error: Throwable) { future.completeExceptionally(error) }
        })
        return await(future)
    }

    private fun <T> await(future: CompletableFuture<T>): T {
        while (true) {
            try {
                return future.get(100, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                if (closed || !enabled()) {
                    future.cancel(false)
                    throw IllegalStateException("ArcMenu is shutting down")
                }
            } catch (_: CancellationException) {
                throw IllegalStateException("ArcMenu is shutting down")
            } catch (error: ExecutionException) {
                throw error.cause ?: error
            }
        }
    }

    override fun close() {
        closed = true
        executor.shutdown()
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow()
    }
}
