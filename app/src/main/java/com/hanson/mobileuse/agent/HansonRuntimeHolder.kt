package com.hanson.mobileuse.agent

import android.content.Context

/**
 * Immortal Process-Wide Runtime Holder.
 * Ensures `MainActivity`, `HansonForegroundService`, `FloatingHudService`,
 * `HansonNotificationListenerService`, and `ArtemisCommandServer` (:18888/chat)
 * share the exact same continuous Live Chat session and agent engine at runtime.
 */
object HansonRuntimeHolder {
    @Volatile
    private var permissionHubInstance: PermissionHubManager? = null

    @Volatile
    private var agentEngineInstance: MobileUseAgentEngine? = null

    fun getPermissionHub(context: Context): PermissionHubManager {
        return permissionHubInstance ?: synchronized(this) {
            permissionHubInstance ?: PermissionHubManager(context.applicationContext).also {
                permissionHubInstance = it
            }
        }
    }

    fun getAgentEngine(context: Context): MobileUseAgentEngine {
        return agentEngineInstance ?: synchronized(this) {
            val hub = getPermissionHub(context)
            agentEngineInstance ?: MobileUseAgentEngine(context.applicationContext, hub).also {
                agentEngineInstance = it
            }
        }
    }
}
