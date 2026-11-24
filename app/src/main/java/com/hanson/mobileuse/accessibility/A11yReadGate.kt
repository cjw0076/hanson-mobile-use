package com.hanson.mobileuse.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Result returned by [A11yReadGate] / [A11yReadGateEngine] wrapping an [AtomicSnapshot]
 * with explicit degradation telemetry whenever Android's Accessibility binder stalls or saturates.
 */
data class GatedSnapshotResult(
    val snapshot: AtomicSnapshot?,
    val degraded: Boolean,
    val degradedReason: String?,
    val snapshotAgeMs: Long,
    val cacheCleared: Boolean
) {
    fun toJson(includeFullTree: Boolean = false): JSONObject {
        val base = snapshot?.toJson(includeFullTree = includeFullTree) ?: JSONObject()
        base.put("degraded", degraded)
        if (degradedReason != null) {
            base.put("degradedReason", degradedReason)
        } else {
            base.put("degradedReason", JSONObject.NULL)
        }
        base.put("snapshotAgeMs", snapshotAgeMs)
        base.put("cacheCleared", cacheCleared)
        return base
    }
}

/**
 * Bounded single-flight Accessibility read gate absorbed from `droidrun/mobilerun-portal`
 * (`A11yReadGate.kt`) and `mobile-dev-harness` (`Commands.java:clearCache()`).
 *
 * Prevents wedged Android Accessibility binder calls from stalling HTTP/bridge worker threads:
 * - Uses a bounded daemon `ThreadPoolExecutor(0, maxWorkers, 60s, SynchronousQueue())` (default 4).
 * - Coalesces concurrent requests sharing the same flight key via single-flight [CompletableFuture].
 * - Enforces a configurable `timeoutMs` (default 2000ms). On timeout (`"a11y_timeout"`) or
 *   worker saturation (`"a11y_saturated"`), falls back to the last good cached [AtomicSnapshot]
 *   with `degraded = true` and exact `snapshotAgeMs`.
 */
class A11yReadGateEngine(
    val maxWorkers: Int = A11yReadGate.DEFAULT_MAX_WORKERS,
    val defaultTimeoutMs: Long = A11yReadGate.DEFAULT_TIMEOUT_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private data class CachedSnapshot(
        val snapshot: AtomicSnapshot,
        val cachedAtMs: Long
    )

    private val workerCounter = AtomicInteger(0)
    private val executor: ThreadPoolExecutor = ThreadPoolExecutor(
        0,
        maxWorkers.coerceIn(1, 16),
        60L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        ThreadFactory { runnable ->
            Thread(runnable, "Hanson-A11yReadGate-${workerCounter.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
    )

    private val inFlight = ConcurrentHashMap<String, CompletableFuture<AtomicSnapshot>>()
    private val lastGood = AtomicReference<CachedSnapshot?>(null)

    fun lastGoodSnapshot(): AtomicSnapshot? = lastGood.get()?.snapshot

    fun lastGoodTimestampMs(): Long = lastGood.get()?.cachedAtMs ?: 0L

    fun clearCachedSnapshot() {
        lastGood.set(null)
    }

    /**
     * Executes [producer] behind the bounded single-flight gate under [flightKey].
     * Concurrent callers with the same [flightKey] coalesce onto the active [CompletableFuture].
     */
    fun executeGated(
        flightKey: String = "default",
        timeoutMs: Long = defaultTimeoutMs,
        cacheCleared: Boolean = false,
        producer: () -> AtomicSnapshot
    ): GatedSnapshotResult {
        val key = flightKey.ifBlank { "default" }
        val effectiveTimeoutMs = timeoutMs.coerceIn(25L, 30_000L)

        var createdByMe = false
        val future = inFlight.computeIfAbsent(key) {
            createdByMe = true
            CompletableFuture<AtomicSnapshot>()
        }

        if (createdByMe) {
            try {
                executor.execute {
                    try {
                        val snap = producer()
                        lastGood.set(CachedSnapshot(snap, clockMs()))
                        future.complete(snap)
                    } catch (t: Throwable) {
                        future.completeExceptionally(t)
                    } finally {
                        inFlight.remove(key, future)
                    }
                }
            } catch (_: RejectedExecutionException) {
                inFlight.remove(key, future)
                future.cancel(false)
                return buildDegradedResult(
                    reason = A11yReadGate.REASON_SATURATED,
                    cacheCleared = cacheCleared
                )
            }
        }

        return try {
            val snap = future.get(effectiveTimeoutMs, TimeUnit.MILLISECONDS)
            GatedSnapshotResult(
                snapshot = snap,
                degraded = false,
                degradedReason = null,
                snapshotAgeMs = 0L,
                cacheCleared = cacheCleared
            )
        } catch (_: TimeoutException) {
            buildDegradedResult(
                reason = A11yReadGate.REASON_TIMEOUT,
                cacheCleared = cacheCleared
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            buildDegradedResult(
                reason = A11yReadGate.REASON_INTERRUPTED,
                cacheCleared = cacheCleared
            )
        } catch (_: ExecutionException) {
            buildDegradedResult(
                reason = A11yReadGate.REASON_ERROR,
                cacheCleared = cacheCleared
            )
        } catch (_: Throwable) {
            buildDegradedResult(
                reason = A11yReadGate.REASON_ERROR,
                cacheCleared = cacheCleared
            )
        }
    }

    /**
     * Captures an [AtomicSnapshot] via [HierarchyDumper.captureAtomicSnapshot] guarded by
     * single-flight timeout & pool saturation fallback.
     */
    fun captureGated(
        service: AccessibilityService,
        includeScreenshot: Boolean = true,
        filterInvisible: Boolean = true,
        jpegQuality: Int = 78,
        clearCacheBeforeCapture: Boolean = true,
        timeoutMs: Long = defaultTimeoutMs
    ): GatedSnapshotResult {
        val cleared = if (clearCacheBeforeCapture) {
            A11yReadGate.clearAccessibilityCache(service)
        } else {
            false
        }
        val flightKey = "snap:$includeScreenshot:$filterInvisible:${jpegQuality.coerceIn(45, 85)}"
        return executeGated(
            flightKey = flightKey,
            timeoutMs = timeoutMs,
            cacheCleared = cleared
        ) {
            HierarchyDumper.captureAtomicSnapshot(
                service = service,
                includeScreenshot = includeScreenshot,
                filterInvisible = filterInvisible,
                jpegQuality = jpegQuality,
                clearCacheBeforeCapture = false
            )
        }
    }

    fun shutdownNow() {
        runCatching { executor.shutdownNow() }
    }

    private fun buildDegradedResult(reason: String, cacheCleared: Boolean): GatedSnapshotResult {
        val cached = lastGood.get()
        val ageMs = if (cached != null) {
            (clockMs() - cached.cachedAtMs).coerceAtLeast(0L)
        } else {
            -1L
        }
        return GatedSnapshotResult(
            snapshot = cached?.snapshot,
            degraded = true,
            degradedReason = reason,
            snapshotAgeMs = ageMs,
            cacheCleared = cacheCleared
        )
    }
}

/**
 * Default singleton entry point for Hanson's bounded Accessibility read gate and cache-clear helper.
 */
object A11yReadGate {
    private const val TAG = "A11yReadGate"

    const val DEFAULT_MAX_WORKERS: Int = 4
    const val DEFAULT_TIMEOUT_MS: Long = 2_000L

    const val REASON_TIMEOUT: String = "a11y_timeout"
    const val REASON_SATURATED: String = "a11y_saturated"
    const val REASON_INTERRUPTED: String = "a11y_interrupted"
    const val REASON_ERROR: String = "a11y_error"

    private val defaultEngine = A11yReadGateEngine(
        maxWorkers = DEFAULT_MAX_WORKERS,
        defaultTimeoutMs = DEFAULT_TIMEOUT_MS
    )

    fun createEngine(
        maxWorkers: Int = DEFAULT_MAX_WORKERS,
        defaultTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
        clockMs: () -> Long = { System.currentTimeMillis() }
    ): A11yReadGateEngine = A11yReadGateEngine(
        maxWorkers = maxWorkers,
        defaultTimeoutMs = defaultTimeoutMs,
        clockMs = clockMs
    )

    fun captureGated(
        service: AccessibilityService,
        includeScreenshot: Boolean = true,
        filterInvisible: Boolean = true,
        jpegQuality: Int = 78,
        clearCacheBeforeCapture: Boolean = true,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): GatedSnapshotResult = defaultEngine.captureGated(
        service = service,
        includeScreenshot = includeScreenshot,
        filterInvisible = filterInvisible,
        jpegQuality = jpegQuality,
        clearCacheBeforeCapture = clearCacheBeforeCapture,
        timeoutMs = timeoutMs
    )

    fun executeGated(
        flightKey: String = "default",
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        cacheCleared: Boolean = false,
        producer: () -> AtomicSnapshot
    ): GatedSnapshotResult = defaultEngine.executeGated(
        flightKey = flightKey,
        timeoutMs = timeoutMs,
        cacheCleared = cacheCleared,
        producer = producer
    )

    fun lastGoodSnapshot(): AtomicSnapshot? = defaultEngine.lastGoodSnapshot()

    fun lastGoodTimestampMs(): Long = defaultEngine.lastGoodTimestampMs()

    fun clearCachedSnapshot() {
        defaultEngine.clearCachedSnapshot()
    }

    /**
     * Clears Android's Accessibility node cache to prevent stale Compose / SPA hierarchy trees
     * (`mobile-dev-harness` `Commands.java:clearCache()` pattern).
     *
     * Tries `AccessibilityService.clearCache()` on API 33/34+ first, then safely falls back to
     * `AccessibilityInteractionClient.getInstance().clearCache()` via reflection.
     * Fully wrapped in [runCatching] so it NEVER throws on older APIs or JVM unit tests.
     */
    fun clearAccessibilityCache(service: AccessibilityService?): Boolean {
        if (service == null) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val apiCleared = runCatching {
                val clearMethod = service.javaClass.getMethod("clearCache")
                (clearMethod.invoke(service) as? Boolean) ?: false
            }.getOrDefault(false)
            if (apiCleared) {
                return true
            }
        }

        val reflectionCleared = runCatching {
            val clientClass = Class.forName("android.view.accessibility.AccessibilityInteractionClient")
            val getInstance = clientClass.getMethod("getInstance")
            val instance = getInstance.invoke(null) ?: return@runCatching false
            val clearMethod = clientClass.getMethod("clearCache")
            clearMethod.invoke(instance)
            true
        }.getOrDefault(false)

        if (!reflectionCleared) {
            runCatching { Log.d(TAG, "Accessibility cache clear unavailable on current runtime") }
        }
        return reflectionCleared
    }
}
