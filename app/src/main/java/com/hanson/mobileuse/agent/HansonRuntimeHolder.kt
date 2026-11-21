package com.hanson.mobileuse.agent

import android.content.Context
import com.hanson.mobileuse.workspace.AgentSessionStore
import com.hanson.mobileuse.workspace.MobileWorkspaceEngine

/**
 * Immortal Process-Wide Runtime Holder.
 * Ensures `MainActivity`, `HansonForegroundService`, `FloatingHudService`,
 * `HansonNotificationListenerService`, and `ArtemisCommandServer` (:18888/chat)
 * share the exact same continuous Live Chat session, workspace engine, session store,
 * and agent engine at runtime.
 */
object HansonRuntimeHolder {
    @Volatile
    private var permissionHubInstance: PermissionHubManager? = null

    @Volatile
    private var workspaceEngineInstance: MobileWorkspaceEngine? = null

    @Volatile
    private var sessionStoreInstance: AgentSessionStore? = null

    @Volatile
    private var agentEngineInstance: MobileUseAgentEngine? = null

    @Volatile
    private var blindVaultInstance: BlindCredentialVault? = null

    @Volatile
    private var watcherEngineInstance: ProactiveWatcherEngine? = null

    @Volatile
    private var scratchpadInstance: NotetakerScratchpad? = null

    @Volatile
    private var memoryBubblesStoreInstance: HumanMemoryBubblesStore? = null

    @Volatile
    private var personalRoutineStoreInstance: PersonalRoutineStore? = null

    @Volatile
    private var firestoreCloudSyncInstance: HansonFirestoreCloudSyncEngine? = null

    fun getPermissionHub(context: Context): PermissionHubManager {
        return permissionHubInstance ?: synchronized(this) {
            permissionHubInstance ?: PermissionHubManager(context.applicationContext).also {
                permissionHubInstance = it
            }
        }
    }

    fun getWorkspaceEngine(context: Context): MobileWorkspaceEngine {
        return workspaceEngineInstance ?: synchronized(this) {
            workspaceEngineInstance ?: MobileWorkspaceEngine(context.applicationContext).also {
                it.initWorkspace()
                workspaceEngineInstance = it
            }
        }
    }

    fun getSessionStore(context: Context): AgentSessionStore {
        return sessionStoreInstance ?: synchronized(this) {
            sessionStoreInstance ?: AgentSessionStore(context.applicationContext).also {
                sessionStoreInstance = it
            }
        }
    }

    fun getBlindVault(context: Context): BlindCredentialVault {
        return blindVaultInstance ?: synchronized(this) {
            val appContext = context.applicationContext
            blindVaultInstance ?: BlindCredentialVault(appContext).also {
                blindVaultInstance = it
            }
        }
    }

    fun getWatcherEngine(context: Context): ProactiveWatcherEngine {
        return watcherEngineInstance ?: synchronized(this) {
            val appContext = context.applicationContext
            watcherEngineInstance ?: ProactiveWatcherEngine(appContext).also {
                watcherEngineInstance = it
            }
        }
    }

    fun getScratchpad(context: Context): NotetakerScratchpad {
        return scratchpadInstance ?: synchronized(this) {
            val appContext = context.applicationContext
            scratchpadInstance ?: NotetakerScratchpad(appContext).also {
                scratchpadInstance = it
            }
        }
    }

    fun getMemoryBubblesStore(context: Context): HumanMemoryBubblesStore {
        return memoryBubblesStoreInstance ?: synchronized(this) {
            val appContext = context.applicationContext ?: context
            memoryBubblesStoreInstance ?: HumanMemoryBubblesStore(appContext).apply {
                seedDefaultsIfEmpty()
            }.also {
                memoryBubblesStoreInstance = it
            }
        }
    }

    fun getPersonalRoutineStore(context: Context): PersonalRoutineStore {
        return personalRoutineStoreInstance ?: synchronized(this) {
            val appContext = context.applicationContext ?: context
            personalRoutineStoreInstance ?: PersonalRoutineStore(appContext).apply {
                seedDefaultsIfEmpty()
            }.also {
                personalRoutineStoreInstance = it
            }
        }
    }

    fun getFirestoreCloudSyncEngine(context: Context): HansonFirestoreCloudSyncEngine {
        return firestoreCloudSyncInstance ?: synchronized(this) {
            val appContext = context.applicationContext ?: context
            val vault = getBlindVault(appContext)
            firestoreCloudSyncInstance ?: HansonFirestoreCloudSyncEngine(appContext, vault).also {
                firestoreCloudSyncInstance = it
            }
        }
    }

    fun getAgentEngine(context: Context): MobileUseAgentEngine {
        return agentEngineInstance ?: synchronized(this) {
            val appContext = context.applicationContext
            val hub = getPermissionHub(appContext)
            val workspace = getWorkspaceEngine(appContext)
            val sessionStore = getSessionStore(appContext)
            val vault = getBlindVault(appContext)
            val watcher = getWatcherEngine(appContext)
            val pad = getScratchpad(appContext)
            val bubbles = getMemoryBubblesStore(appContext)
            val routines = getPersonalRoutineStore(appContext)
            agentEngineInstance ?: MobileUseAgentEngine(
                context = appContext,
                permissionHub = hub,
                workspaceEngine = workspace,
                sessionStore = sessionStore,
                blindVault = vault,
                watcherEngine = watcher,
                scratchpad = pad,
                memoryBubblesStore = bubbles,
                personalRoutineStore = routines
            ).also {
                agentEngineInstance = it
            }
        }
    }
}
