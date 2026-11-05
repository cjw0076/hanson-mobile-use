package com.hanson.mobileuse.paradigm

import android.content.Context
import com.hanson.mobileuse.accessibility.AtomicSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedList
import kotlin.math.abs

/**
 * Represents a distinct perceptual & structural screen state node S_t in the App World-Model Graph.
 */
data class ScreenStateNode(
    val stateHash: String,
    val packageName: String,
    val windowTitle: String,
    val hasEditableField: Boolean,
    val keyAnchors: List<String>,
    val visitCount: Int = 1,
    val lastVisitedMs: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("stateHash", stateHash)
        put("packageName", packageName)
        put("windowTitle", windowTitle)
        put("hasEditableField", hasEditableField)
        val arr = JSONArray()
        keyAnchors.take(10).forEach { arr.put(it) }
        put("keyAnchors", arr)
        put("visitCount", visitCount)
        put("lastVisitedMs", lastVisitedMs)
    }
}

/**
 * Represents a directed action transition edge: S_t --(toolName, args, verifiedDelta)--> S_{t+1}.
 * Tracks both success count and self-healed recovery recipes so the agent learns from failures.
 */
data class KineticTransitionEdge(
    val fromStateHash: String,
    val toStateHash: String,
    val toolName: String,
    val toolArgsJson: String,
    val avgUiDeltaRatio: Float,
    val successCount: Int = 1,
    val failureCount: Int = 0,
    val learnedSelfHealRecipe: String = "",
    val lastUpdatedMs: Long = System.currentTimeMillis()
) {
    val reliabilityScore: Float
        get() = successCount.toFloat() / (successCount + failureCount).coerceAtLeast(1).toFloat()

    fun toJson(): JSONObject = JSONObject().apply {
        put("fromStateHash", fromStateHash)
        put("toStateHash", toStateHash)
        put("toolName", toolName)
        put("toolArgsJson", toolArgsJson)
        put("avgUiDeltaRatio", avgUiDeltaRatio.toDouble())
        put("successCount", successCount)
        put("failureCount", failureCount)
        put("learnedSelfHealRecipe", learnedSelfHealRecipe)
        put("lastUpdatedMs", lastUpdatedMs)
    }
}

/**
 * New Paradigm Pillar 1: Persistent On-Device App World-Model State Graph (`WorldModelGraph`).
 *
 * Instead of treating every screen as an isolated screenshot, records every verified state transition
 * S_t -> S_{t+1} and builds a navigable Markov State Graph of every app the agent touches.
 * Supports BFS shortest-trajectory pathfinding and failure-aware edge weighting.
 */
class WorldModelGraph(context: Context) {

    companion object {
        private const val PREFS_NAME = "hanson_paradigm_world_model_graph"
        private const val KEY_NODES = "state_nodes_json"
        private const val KEY_EDGES = "transition_edges_json"

        /**
         * Computes a deterministic structural fingerprint for any screen snapshot S_t.
         */
        fun computeStateHash(snapshot: AtomicSnapshot): String {
            val pkg = snapshot.activePackage.ifBlank { "unknown" }
            val hasEdit = snapshot.indexedElements.any { it.editable }
            val anchors = snapshot.indexedElements
                .filter { it.clickable || it.editable || it.scrollable }
                .take(12)
                .joinToString("|") { el ->
                    val label = el.text.ifBlank { el.contentDesc }.take(12)
                    "${el.shortClass}:$label@${el.normX / 100},${el.normY / 100}"
                }
            val rawHash = (pkg.hashCode() * 31 + (if (hasEdit) 1 else 0)) * 31 + abs(anchors.hashCode())
            return "${pkg.substringAfterLast('.')}_${Integer.toHexString(abs(rawHash)).take(6)}"
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val nodes = LinkedHashMap<String, ScreenStateNode>()
    private val edges = mutableListOf<KineticTransitionEdge>()

    init {
        loadFromDisk()
    }

    @Synchronized
    fun recordTransition(
        preSnapshot: AtomicSnapshot,
        postSnapshot: AtomicSnapshot,
        toolName: String,
        toolArgsJson: String,
        verifiedSuccess: Boolean,
        uiDeltaRatio: Float,
        selfHealRecipe: String = ""
    ) {
        val fromHash = computeStateHash(preSnapshot)
        val toHash = computeStateHash(postSnapshot)

        val preNode = registerOrUpdateNode(fromHash, preSnapshot)
        val postNode = registerOrUpdateNode(toHash, postSnapshot)
        nodes[fromHash] = preNode
        nodes[toHash] = postNode

        val existingIdx = edges.indexOfFirst {
            it.fromStateHash == fromHash && it.toolName == toolName && it.toolArgsJson == toolArgsJson
        }
        if (existingIdx >= 0) {
            val prev = edges[existingIdx]
            edges[existingIdx] = prev.copy(
                toStateHash = if (verifiedSuccess) toHash else prev.toStateHash,
                avgUiDeltaRatio = (prev.avgUiDeltaRatio + uiDeltaRatio) / 2f,
                successCount = prev.successCount + (if (verifiedSuccess) 1 else 0),
                failureCount = prev.failureCount + (if (!verifiedSuccess) 1 else 0),
                learnedSelfHealRecipe = selfHealRecipe.ifBlank { prev.learnedSelfHealRecipe },
                lastUpdatedMs = System.currentTimeMillis()
            )
        } else {
            edges.add(
                KineticTransitionEdge(
                    fromStateHash = fromHash,
                    toStateHash = toHash,
                    toolName = toolName,
                    toolArgsJson = toolArgsJson,
                    avgUiDeltaRatio = uiDeltaRatio,
                    successCount = if (verifiedSuccess) 1 else 0,
                    failureCount = if (verifiedSuccess) 0 else 1,
                    learnedSelfHealRecipe = selfHealRecipe
                )
            )
        }
        persistToDisk()
    }

    /**
     * Finds the shortest high-reliability trajectory of edges from `startStateHash`
     * to any state node matching `goalPredicate` using Breadth-First Search (BFS).
     */
    @Synchronized
    fun findShortestTrajectory(
        startStateHash: String,
        goalPredicate: (ScreenStateNode) -> Boolean
    ): List<KineticTransitionEdge> {
        val startNode = nodes[startStateHash]
        if (startNode != null && goalPredicate(startNode)) return emptyList()

        val queue = LinkedList<Pair<String, List<KineticTransitionEdge>>>()
        val visited = mutableSetOf(startStateHash)
        queue.add(startStateHash to emptyList())

        while (queue.isNotEmpty()) {
            val (currentHash, path) = queue.poll() ?: break
            if (path.size >= 6) continue

            val outgoing = edges
                .filter { it.fromStateHash == currentHash && it.reliabilityScore >= 0.4f }
                .sortedByDescending { it.reliabilityScore }

            for (edge in outgoing) {
                val nextHash = edge.toStateHash
                if (nextHash in visited) continue
                val nextPath = path + edge
                val targetNode = nodes[nextHash]
                if (targetNode != null && goalPredicate(targetNode)) {
                    return nextPath
                }
                visited.add(nextHash)
                queue.add(nextHash to nextPath)
            }
        }
        return emptyList()
    }

    @Synchronized
    fun getAllNodes(): List<ScreenStateNode> = nodes.values.toList()

    @Synchronized
    fun getAllEdges(): List<KineticTransitionEdge> = edges.toList()

    private fun registerOrUpdateNode(hash: String, snapshot: AtomicSnapshot): ScreenStateNode {
        val existing = nodes[hash]
        val anchors = snapshot.indexedElements
            .mapNotNull { el -> el.text.ifBlank { el.contentDesc }.takeIf { it.isNotBlank() }?.take(18) }
            .distinct()
            .take(8)
        return ScreenStateNode(
            stateHash = hash,
            packageName = snapshot.activePackage.ifBlank { "unknown" },
            windowTitle = snapshot.activeWindowTitle,
            hasEditableField = snapshot.indexedElements.any { it.editable },
            keyAnchors = anchors,
            visitCount = (existing?.visitCount ?: 0) + 1,
            lastVisitedMs = System.currentTimeMillis()
        )
    }

    private fun loadFromDisk() {
        try {
            val rawNodes = prefs.getString(KEY_NODES, null)
            if (!rawNodes.isNullOrBlank()) {
                val arr = JSONArray(rawNodes)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val anchorsArr = o.optJSONArray("keyAnchors") ?: JSONArray()
                    val anchors = mutableListOf<String>()
                    for (j in 0 until anchorsArr.length()) anchors.add(anchorsArr.optString(j))
                    val node = ScreenStateNode(
                        stateHash = o.optString("stateHash"),
                        packageName = o.optString("packageName"),
                        windowTitle = o.optString("windowTitle"),
                        hasEditableField = o.optBoolean("hasEditableField"),
                        keyAnchors = anchors,
                        visitCount = o.optInt("visitCount", 1),
                        lastVisitedMs = o.optLong("lastVisitedMs", 0L)
                    )
                    if (node.stateHash.isNotBlank()) nodes[node.stateHash] = node
                }
            }
            val rawEdges = prefs.getString(KEY_EDGES, null)
            if (!rawEdges.isNullOrBlank()) {
                val arr = JSONArray(rawEdges)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    edges.add(
                        KineticTransitionEdge(
                            fromStateHash = o.optString("fromStateHash"),
                            toStateHash = o.optString("toStateHash"),
                            toolName = o.optString("toolName"),
                            toolArgsJson = o.optString("toolArgsJson"),
                            avgUiDeltaRatio = o.optDouble("avgUiDeltaRatio", 0.2).toFloat(),
                            successCount = o.optInt("successCount", 1),
                            failureCount = o.optInt("failureCount", 0),
                            learnedSelfHealRecipe = o.optString("learnedSelfHealRecipe", ""),
                            lastUpdatedMs = o.optLong("lastUpdatedMs", 0L)
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun persistToDisk() {
        try {
            val nodeArr = JSONArray()
            nodes.values.toList().takeLast(80).forEach { nodeArr.put(it.toJson()) }
            val edgeArr = JSONArray()
            edges.takeLast(120).forEach { edgeArr.put(it.toJson()) }
            prefs.edit()
                .putString(KEY_NODES, nodeArr.toString())
                .putString(KEY_EDGES, edgeArr.toString())
                .apply()
        } catch (_: Exception) {
        }
    }
}
