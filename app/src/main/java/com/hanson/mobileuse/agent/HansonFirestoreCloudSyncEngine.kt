package com.hanson.mobileuse.agent

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class FirestoreEditionMode(
    val id: String,
    val labelKo: String
) {
    STANDARD("standard", "Standard (default)"),
    ENTERPRISE("enterprise", "Enterprise Native");

    companion object {
        fun fromId(id: String?): FirestoreEditionMode {
            val clean = id?.trim() ?: return STANDARD
            return entries.firstOrNull { it.id.equals(clean, ignoreCase = true) } ?: STANDARD
        }
    }
}

data class FirestoreSyncConfig(
    val enabled: Boolean = false,
    val projectId: String = "",
    val applicationId: String = "",
    val databaseId: String = "(default)",
    val editionMode: FirestoreEditionMode = FirestoreEditionMode.STANDARD,
    val userUid: String = "local_owner"
)

data class FirestoreSyncUiState(
    val config: FirestoreSyncConfig = FirestoreSyncConfig(),
    val hasApiKeyInVault: Boolean = false,
    val lastSyncAtMs: Long = 0L,
    val lastStatusKo: String = "미설정 (로컬 전용 모드)",
    val syncedBubbleCount: Int = 0,
    val syncedRoutineCount: Int = 0,
    val isSyncing: Boolean = false
)

/**
 * Optional Bring-Your-Own (BYO) Cloud Firestore Sync Engine (`HansonFirestoreCloudSyncEngine`).
 *
 * Security & Architecture Contract:
 * - Zero bundled Firebase config JSON and zero static GMS Gradle plugin dependency.
 * - API keys are stored strictly encrypted inside [BlindCredentialVault] (`vault://firestore_sync_api_key`)
 *   and NEVER written to plaintext [SharedPreferences].
 * - Vault entries (`vault://...`) are NEVER synced or uploaded to Firestore.
 * - All synced documents (`users/{userUid}/{bubbles,routines,watchers,receipts}`) are sanitized and
 *   redacted before leaving the device, with Last-Write-Wins (LWW) conflict resolution.
 */
class HansonFirestoreCloudSyncEngine @JvmOverloads constructor(
    private val context: Context,
    private val blindVault: BlindCredentialVault? = null
) {

    companion object {
        private const val PREFS_NAME = "hanson_firestore_sync_config"
        private const val KEY_ENABLED = "sync_enabled"
        private const val KEY_PROJECT_ID = "sync_project_id"
        private const val KEY_APPLICATION_ID = "sync_application_id"
        private const val KEY_DATABASE_ID = "sync_database_id"
        private const val KEY_EDITION_MODE = "sync_edition_mode"
        private const val KEY_USER_UID = "sync_user_uid"
        private const val KEY_LAST_SYNC_AT = "sync_last_at_ms"
        private const val KEY_LAST_STATUS_KO = "sync_last_status_ko"
        private const val KEY_SYNCED_BUBBLE_COUNT = "sync_bubble_count"
        private const val KEY_SYNCED_ROUTINE_COUNT = "sync_routine_count"

        private const val VAULT_API_KEY_NAME = "firestore_sync_api_key"
        private const val BYO_APP_NAME = "hanson_byo_firestore"
        private const val FIRESTORE_TIMEOUT_SEC = 8L
        private const val MAX_SYNC_RECEIPTS = 25
        private const val MAX_FIRESTORE_TIMESTAMP_MS = 253_402_300_799_999L

        val FORBIDDEN_SECRET_KEYS = setOf(
            "plaintext",
            "secret",
            "ciphertextBase64",
            "password",
            "apiKey",
            "privateKey",
            "token"
        )

        private val CARD_16_SEPARATED_REGEX = Regex("(?<!\\d)(?:\\d{4}[-\\s]){3}\\d{4}(?!\\d)")
        private val CARD_16_PLAIN_REGEX = Regex("(?<!\\d)\\d{16}(?!\\d)")
        private val RRN_REGEX = Regex("(?<!\\d)\\d{6}[-\\s]?[1-4]\\d{6}(?!\\d)")
        private val GOOGLE_API_KEY_REGEX = Regex("AIza[0-9A-Za-z_\\-]{8,}")
        private val SK_KEY_REGEX = Regex("(?<![A-Za-z0-9_])sk-[A-Za-z0-9_\\-]{8,}")
        private val BEARER_TOKEN_REGEX = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._\\-]{8,}")
        private val PASSWORD_KEYWORD_REGEX = Regex(
            "(?i)(?:비밀번호|패스워드|비번|핀번호|보안카드|password|passwd|passphrase|secret[_\\s-]?key|private[_\\s-]?key|access[_\\s-]?token|api[_\\s-]?key)"
        )

        private fun redactText(raw: String, vault: BlindCredentialVault?): String {
            if (raw.isBlank()) return ""
            var out = vault?.let { runCatching { it.redactSensitiveText(raw) }.getOrDefault(raw) } ?: raw
            out = CARD_16_SEPARATED_REGEX.replace(out, "[REDACTED:CARD]")
            out = CARD_16_PLAIN_REGEX.replace(out, "[REDACTED:CARD]")
            out = RRN_REGEX.replace(out, "[REDACTED:RRN]")
            out = GOOGLE_API_KEY_REGEX.replace(out, "[REDACTED:API_KEY]")
            out = SK_KEY_REGEX.replace(out, "[REDACTED:API_KEY]")
            out = BEARER_TOKEN_REGEX.replace(out, "[REDACTED:TOKEN]")
            if (PASSWORD_KEYWORD_REGEX.containsMatchIn(out)) {
                out = "[REDACTED:SECRET]"
            }
            return out
        }

        private fun stripForbiddenKeys(input: Map<String, Any>): Map<String, Any> {
            return input.filterKeys { it !in FORBIDDEN_SECRET_KEYS }
        }

        fun sanitizeBubbleMap(
            bubble: HumanMemoryBubble,
            vault: BlindCredentialVault? = null
        ): Map<String, Any> {
            val safeFact = redactText(bubble.factKo, vault).take(500).ifBlank { "메모" }
            val safeMap = linkedMapOf<String, Any>(
                "id" to bubble.id.trim().take(128).ifBlank { "bubble" },
                "emoji" to bubble.emoji.trim().ifBlank { "🫧" }.take(32),
                "category" to bubble.category.trim().ifBlank { "PREF" }.take(32),
                "factKo" to safeFact,
                "enabled" to bubble.enabled,
                "updatedAtMs" to bubble.updatedAtMs.coerceIn(0L, MAX_FIRESTORE_TIMESTAMP_MS)
            )
            return stripForbiddenKeys(safeMap)
        }

        fun sanitizeRoutineMap(
            routine: PersonalRoutine,
            vault: BlindCredentialVault? = null
        ): Map<String, Any> {
            val safeMap = linkedMapOf<String, Any>(
                "id" to routine.id.trim().take(128).ifBlank { "routine" },
                "titleKo" to redactText(routine.titleKo, vault).take(200).ifBlank { "루틴" },
                "emoji" to routine.emoji.trim().ifBlank { "⚡" }.take(32),
                "goalPrompt" to redactText(routine.goalPrompt, vault).take(1000).ifBlank { "작업을 실행해줘" },
                "sourceBadge" to redactText(routine.sourceBadge, vault).take(64).ifBlank { "내 루틴" },
                "pinned" to routine.pinned,
                "stepCount" to routine.stepCount.coerceIn(1, 50),
                "createdAtMs" to routine.createdAtMs.coerceIn(0L, MAX_FIRESTORE_TIMESTAMP_MS)
            )
            return stripForbiddenKeys(safeMap)
        }

        fun sanitizeWatcherMap(
            rule: WatcherRule,
            vault: BlindCredentialVault? = null
        ): Map<String, Any> {
            val cleanKeywords = rule.matchKeywords
                .map { redactText(it, vault).take(100) }
                .filter { it.isNotBlank() }
                .take(20)
                .ifEmpty { listOf("알림") }
            val safeMode = if (rule.mode == WatcherActionMode.HOLD_FOR_APPROVAL) {
                "HOLD_FOR_APPROVAL"
            } else {
                "NOTIFY_ONLY"
            }
            val safeMap = linkedMapOf<String, Any>(
                "id" to rule.id.trim().take(128).ifBlank { "watcher" },
                "title" to redactText(rule.title, vault).take(200).ifBlank { "감시 규칙" },
                "targetAppOrUrl" to redactText(rule.targetAppOrUrl, vault).take(500),
                "keywords" to cleanKeywords,
                "mode" to safeMode,
                "enabled" to rule.enabled,
                "hitCount" to rule.hitCount.coerceIn(0, 1_000_000),
                "updatedAtMs" to rule.lastCheckedAtMs.coerceIn(0L, MAX_FIRESTORE_TIMESTAMP_MS)
            )
            return stripForbiddenKeys(safeMap)
        }

        fun sanitizeReceiptMap(
            receipt: ActionReceipt,
            vault: BlindCredentialVault? = null
        ): Map<String, Any> {
            val safeMap = linkedMapOf<String, Any>(
                "id" to receipt.id.trim().take(128).ifBlank { "rcpt" },
                "timestampMs" to receipt.timestampMs.coerceIn(0L, MAX_FIRESTORE_TIMESTAMP_MS),
                "toolName" to redactText(receipt.toolName, vault).trim().take(100).ifBlank { "action" },
                "summaryKo" to redactText(receipt.summaryKo, vault).take(500).ifBlank { "작업 실행" },
                "detailKo" to redactText(receipt.detailKo, vault).take(1000),
                "undoHintKo" to redactText(receipt.undoHintKo, vault).take(500),
                "undoToolName" to receipt.undoToolName.trim().take(100),
                "undoArgsJson" to redactText(receipt.undoArgsJson, vault).take(1000),
                "undone" to receipt.undone
            )
            return stripForbiddenKeys(safeMap)
        }

        fun mergeBubblesLww(
            local: List<HumanMemoryBubble>,
            remote: List<HumanMemoryBubble>
        ): List<HumanMemoryBubble> {
            val merged = LinkedHashMap<String, HumanMemoryBubble>()
            for (item in local) {
                if (item.id.isBlank()) continue
                val existing = merged[item.id]
                if (existing == null || item.updatedAtMs > existing.updatedAtMs) {
                    merged[item.id] = item
                }
            }
            for (item in remote) {
                if (item.id.isBlank()) continue
                val existing = merged[item.id]
                if (existing == null || item.updatedAtMs > existing.updatedAtMs) {
                    merged[item.id] = item
                }
            }
            return merged.values.sortedByDescending { it.updatedAtMs }
        }

        fun mergeRoutinesLww(
            local: List<PersonalRoutine>,
            remote: List<PersonalRoutine>
        ): List<PersonalRoutine> {
            val merged = LinkedHashMap<String, PersonalRoutine>()
            for (item in local) {
                if (item.id.isBlank()) continue
                val existing = merged[item.id]
                if (existing == null || item.createdAtMs > existing.createdAtMs) {
                    merged[item.id] = item
                }
            }
            for (item in remote) {
                if (item.id.isBlank()) continue
                val existing = merged[item.id]
                if (existing == null || item.createdAtMs > existing.createdAtMs) {
                    merged[item.id] = item
                }
            }
            return merged.values.sortedByDescending { it.createdAtMs }
        }
    }

    private val appContext: Context = context.applicationContext ?: context
    private val prefs: SharedPreferences? = runCatching {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }.getOrNull()
    private val resolvedVault: BlindCredentialVault by lazy {
        blindVault ?: HansonRuntimeHolder.getBlindVault(appContext)
    }
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncLock = Any()

    private val _state = MutableStateFlow(loadInitialState())
    val state: StateFlow<FirestoreSyncUiState> = _state.asStateFlow()

    fun saveByoConfig(
        enabled: Boolean,
        projectId: String,
        applicationId: String,
        apiKeyPlaintext: String,
        databaseId: String = "(default)",
        editionMode: FirestoreEditionMode = FirestoreEditionMode.STANDARD,
        userUid: String = "local_owner"
    ) {
        val cleanProjectId = projectId.trim()
        val cleanAppId = applicationId.trim()
        val cleanDbId = databaseId.trim().ifBlank { "(default)" }
        val cleanUid = userUid.trim()
            .replace(Regex("[^a-zA-Z0-9_.-]+"), "_")
            .ifBlank { "local_owner" }
        val cleanApiKey = apiKeyPlaintext.trim()

        if (cleanApiKey.isNotEmpty()) {
            runCatching {
                resolvedVault.putSecret(
                    rawKey = VAULT_API_KEY_NAME,
                    label = "BYO Cloud Firestore API Key",
                    plaintext = cleanApiKey,
                    category = VaultSecretCategory.API_TOKEN
                )
            }
        }

        val hasKey = hasVaultApiKey()
        val nextConfig = FirestoreSyncConfig(
            enabled = enabled,
            projectId = cleanProjectId,
            applicationId = cleanAppId,
            databaseId = cleanDbId,
            editionMode = editionMode,
            userUid = cleanUid
        )

        val statusKo = when {
            !enabled -> "미설정 (로컬 전용 모드)"
            cleanProjectId.isEmpty() || cleanAppId.isEmpty() || !hasKey -> "BYO Firebase 미설정 — 로컬 전용 저장 중"
            else -> "BYO Firestore 연결 준비 완료 (${editionMode.labelKo})"
        }

        val nextState = _state.value.copy(
            config = nextConfig,
            hasApiKeyInVault = hasKey,
            lastStatusKo = statusKo,
            isSyncing = false
        )
        persistNonSecretState(nextState)
        _state.value = nextState
    }

    fun syncNow(
        bubblesStore: HumanMemoryBubblesStore,
        routineStore: PersonalRoutineStore,
        watcherEngine: ProactiveWatcherEngine? = null,
        receipts: List<ActionReceipt> = emptyList()
    ): FirestoreSyncUiState {
        val currentCfg = _state.value.config
        val resolvedApiKey = runCatching {
            resolvedVault.resolvePlaintextForBlindInjection(VAULT_API_KEY_NAME)?.second?.trim().orEmpty()
        }.getOrDefault("")
        val hasKey = resolvedApiKey.isNotEmpty()

        if (!currentCfg.enabled || currentCfg.projectId.isBlank() || currentCfg.applicationId.isBlank() || !hasKey) {
            val standbyState = _state.value.copy(
                hasApiKeyInVault = hasKey,
                lastStatusKo = "BYO Firebase 미설정 — 로컬 전용 저장 중",
                isSyncing = false
            )
            persistNonSecretState(standbyState)
            _state.value = standbyState
            return standbyState
        }

        val isMainUiThread = runCatching {
            val mainLooper = Looper.getMainLooper()
            mainLooper != null && Looper.myLooper() === mainLooper
        }.getOrDefault(false)

        if (isMainUiThread) {
            val pending = _state.value.copy(
                hasApiKeyInVault = true,
                isSyncing = true,
                lastStatusKo = "BYO Firestore 동기화 중 (${currentCfg.editionMode.labelKo})..."
            )
            _state.value = pending
            syncScope.launch {
                executeSyncInternal(
                    cfg = currentCfg,
                    apiKey = resolvedApiKey,
                    bubblesStore = bubblesStore,
                    routineStore = routineStore,
                    watcherEngine = watcherEngine,
                    receipts = receipts
                )
            }
            return pending
        }

        return executeSyncInternal(
            cfg = currentCfg,
            apiKey = resolvedApiKey,
            bubblesStore = bubblesStore,
            routineStore = routineStore,
            watcherEngine = watcherEngine,
            receipts = receipts
        )
    }

    private fun executeSyncInternal(
        cfg: FirestoreSyncConfig,
        apiKey: String,
        bubblesStore: HumanMemoryBubblesStore,
        routineStore: PersonalRoutineStore,
        watcherEngine: ProactiveWatcherEngine?,
        receipts: List<ActionReceipt>
    ): FirestoreSyncUiState {
        synchronized(syncLock) {
            val result = runCatching {
                val activeApp = resolveByoApp(cfg, apiKey)
                val effectiveUid = resolveAuthenticatedUid(activeApp, cfg)
                val db = resolveFirestore(activeApp, cfg)
                val userDoc = db.collection("users").document(effectiveUid)

                // Note: Standard & Enterprise Native editions both expose standard collection/document
                // operations cleanly via BoM 35.2.0 while Enterprise Native databases use named databaseId.
                val remoteBubblesSnap = Tasks.await(
                    userDoc.collection("bubbles").get(),
                    FIRESTORE_TIMEOUT_SEC,
                    TimeUnit.SECONDS
                )
                val remoteBubbles = remoteBubblesSnap.documents.mapNotNull { doc ->
                    val fact = doc.getString("factKo")?.trim().orEmpty()
                    val id = doc.getString("id")?.trim().orEmpty().ifBlank { doc.id }
                    if (id.isEmpty() || fact.isEmpty()) return@mapNotNull null
                    HumanMemoryBubble(
                        id = id,
                        emoji = doc.getString("emoji")?.ifBlank { "🫧" } ?: "🫧",
                        category = doc.getString("category")?.ifBlank { "PREF" } ?: "PREF",
                        factKo = fact,
                        enabled = doc.getBoolean("enabled") ?: true,
                        updatedAtMs = doc.getLong("updatedAtMs") ?: System.currentTimeMillis()
                    )
                }

                val remoteRoutinesSnap = Tasks.await(
                    userDoc.collection("routines").get(),
                    FIRESTORE_TIMEOUT_SEC,
                    TimeUnit.SECONDS
                )
                val remoteRoutines = remoteRoutinesSnap.documents.mapNotNull { doc ->
                    val title = doc.getString("titleKo")?.trim().orEmpty()
                    val goal = doc.getString("goalPrompt")?.trim().orEmpty()
                    val id = doc.getString("id")?.trim().orEmpty().ifBlank { doc.id }
                    if (id.isEmpty() || title.isEmpty() || goal.isEmpty()) return@mapNotNull null
                    PersonalRoutine(
                        id = id,
                        titleKo = title,
                        emoji = doc.getString("emoji")?.ifBlank { "⚡" } ?: "⚡",
                        goalPrompt = goal,
                        sourceBadge = doc.getString("sourceBadge")?.ifBlank { "클라우드 동기화" } ?: "클라우드 동기화",
                        pinned = doc.getBoolean("pinned") ?: true,
                        stepCount = (doc.getLong("stepCount") ?: 1L).toInt().coerceIn(1, 50),
                        createdAtMs = doc.getLong("createdAtMs") ?: System.currentTimeMillis()
                    )
                }

                val localBubbles = bubblesStore.bubbles.value
                val mergedBubbles = mergeBubblesLww(localBubbles, remoteBubbles)
                bubblesStore.upsertMergedBubbles(mergedBubbles)

                val localRoutines = routineStore.routines.value
                val mergedRoutines = mergeRoutinesLww(localRoutines, remoteRoutines)
                routineStore.upsertMergedRoutines(mergedRoutines)

                // Upload sanitized records (NEVER upload vault secrets!)
                val bubblesCol = userDoc.collection("bubbles")
                for (b in mergedBubbles) {
                    val payload = sanitizeBubbleMap(b, resolvedVault)
                    val docId = (payload["id"] as? String)?.ifBlank { b.id } ?: b.id
                    Tasks.await(
                        bubblesCol.document(docId).set(payload, SetOptions.merge()),
                        FIRESTORE_TIMEOUT_SEC,
                        TimeUnit.SECONDS
                    )
                }

                val routinesCol = userDoc.collection("routines")
                for (r in mergedRoutines) {
                    val payload = sanitizeRoutineMap(r, resolvedVault)
                    val docId = (payload["id"] as? String)?.ifBlank { r.id } ?: r.id
                    Tasks.await(
                        routinesCol.document(docId).set(payload, SetOptions.merge()),
                        FIRESTORE_TIMEOUT_SEC,
                        TimeUnit.SECONDS
                    )
                }

                if (watcherEngine != null) {
                    val watchersCol = userDoc.collection("watchers")
                    for (rule in watcherEngine.listRules()) {
                        val payload = sanitizeWatcherMap(rule, resolvedVault)
                        val docId = (payload["id"] as? String)?.ifBlank { rule.id } ?: rule.id
                        Tasks.await(
                            watchersCol.document(docId).set(payload, SetOptions.merge()),
                            FIRESTORE_TIMEOUT_SEC,
                            TimeUnit.SECONDS
                        )
                    }
                }

                if (receipts.isNotEmpty()) {
                    val receiptsCol = userDoc.collection("receipts")
                    for (rcpt in receipts.take(MAX_SYNC_RECEIPTS)) {
                        val payload = sanitizeReceiptMap(rcpt, resolvedVault)
                        val docId = (payload["id"] as? String)?.ifBlank { rcpt.id } ?: rcpt.id
                        Tasks.await(
                            receiptsCol.document(docId).set(payload, SetOptions.merge()),
                            FIRESTORE_TIMEOUT_SEC,
                            TimeUnit.SECONDS
                        )
                    }
                }

                Triple(mergedBubbles.size, mergedRoutines.size, effectiveUid)
            }

            val nextState = result.fold(
                onSuccess = { (bubbleCnt, routineCnt, effectiveUid) ->
                    _state.value.copy(
                        config = _state.value.config.copy(userUid = effectiveUid),
                        hasApiKeyInVault = true,
                        lastSyncAtMs = System.currentTimeMillis(),
                        lastStatusKo = "BYO Firestore 동기화 완료 (${cfg.editionMode.labelKo}) · 버블 ${bubbleCnt} · 루틴 ${routineCnt}",
                        syncedBubbleCount = bubbleCnt,
                        syncedRoutineCount = routineCnt,
                        isSyncing = false
                    )
                },
                onFailure = { err ->
                    val reason = err.message?.take(48).orEmpty().ifBlank { "오프라인/권한 대기" }
                    _state.value.copy(
                        hasApiKeyInVault = true,
                        lastStatusKo = "BYO Firestore 대기 — 로컬 전용 저장 유지 ($reason)",
                        isSyncing = false
                    )
                }
            )
            persistNonSecretState(nextState)
            _state.value = nextState
            return nextState
        }
    }

    private fun resolveByoApp(cfg: FirestoreSyncConfig, apiKey: String): FirebaseApp {
        val existing = FirebaseApp.getApps(appContext).firstOrNull { it.name == BYO_APP_NAME }
        return if (existing != null) {
            val opts = existing.options
            val matches = opts.projectId == cfg.projectId &&
                opts.applicationId == cfg.applicationId &&
                opts.apiKey == apiKey
            if (matches) {
                existing
            } else {
                runCatching { existing.delete() }
                initializeByoApp(cfg, apiKey)
            }
        } else {
            initializeByoApp(cfg, apiKey)
        }
    }

    private fun resolveAuthenticatedUid(activeApp: FirebaseApp, cfg: FirestoreSyncConfig): String {
        val authUid = runCatching {
            val auth = FirebaseAuth.getInstance(activeApp)
            val existingUser = auth.currentUser
            if (existingUser != null && existingUser.uid.isNotBlank()) {
                existingUser.uid.trim()
            } else {
                val authResult = Tasks.await(
                    auth.signInAnonymously(),
                    FIRESTORE_TIMEOUT_SEC,
                    TimeUnit.SECONDS
                )
                authResult?.user?.uid?.trim()?.ifBlank { null }
                    ?: auth.currentUser?.uid?.trim()?.ifBlank { null }
            }
        }.getOrNull()
        return (authUid ?: cfg.userUid.trim()).take(128).ifBlank { "local_owner" }
    }

    private fun resolveFirestore(activeApp: FirebaseApp, cfg: FirestoreSyncConfig): FirebaseFirestore {
        return if (cfg.databaseId.isNotBlank() && cfg.databaseId != "(default)") {
            FirebaseFirestore.getInstance(activeApp, cfg.databaseId)
        } else {
            FirebaseFirestore.getInstance(activeApp)
        }
    }

    private fun initializeByoApp(cfg: FirestoreSyncConfig, apiKey: String): FirebaseApp {
        val options = FirebaseOptions.Builder()
            .setProjectId(cfg.projectId)
            .setApplicationId(cfg.applicationId)
            .setApiKey(apiKey)
            .build()
        return FirebaseApp.initializeApp(appContext, options, BYO_APP_NAME)
            ?: FirebaseApp.getApps(appContext).firstOrNull { it.name == BYO_APP_NAME }
            ?: error("Unable to initialize BYO FirebaseApp")
    }

    private fun hasVaultApiKey(): Boolean {
        return runCatching {
            resolvedVault.getHandleSummary(VAULT_API_KEY_NAME) != null
        }.getOrDefault(false)
    }

    private fun loadInitialState(): FirestoreSyncUiState {
        val p = prefs
        val enabled = p?.getBoolean(KEY_ENABLED, false) ?: false
        val projectId = p?.getString(KEY_PROJECT_ID, "")?.trim().orEmpty()
        val appId = p?.getString(KEY_APPLICATION_ID, "")?.trim().orEmpty()
        val dbId = p?.getString(KEY_DATABASE_ID, "(default)")?.trim().orEmpty().ifBlank { "(default)" }
        val edition = FirestoreEditionMode.fromId(p?.getString(KEY_EDITION_MODE, FirestoreEditionMode.STANDARD.id))
        val uid = p?.getString(KEY_USER_UID, "local_owner")?.trim().orEmpty().ifBlank { "local_owner" }
        val hasKey = hasVaultApiKey()
        val lastSyncAt = p?.getLong(KEY_LAST_SYNC_AT, 0L) ?: 0L
        val defaultStatus = if (enabled && projectId.isNotEmpty() && appId.isNotEmpty() && hasKey) {
            "BYO Firestore 연결 준비 완료 (${edition.labelKo})"
        } else {
            "미설정 (로컬 전용 모드)"
        }
        val statusKo = p?.getString(KEY_LAST_STATUS_KO, defaultStatus)?.ifBlank { defaultStatus } ?: defaultStatus
        val syncedBubbles = p?.getInt(KEY_SYNCED_BUBBLE_COUNT, 0) ?: 0
        val syncedRoutines = p?.getInt(KEY_SYNCED_ROUTINE_COUNT, 0) ?: 0

        return FirestoreSyncUiState(
            config = FirestoreSyncConfig(
                enabled = enabled,
                projectId = projectId,
                applicationId = appId,
                databaseId = dbId,
                editionMode = edition,
                userUid = uid
            ),
            hasApiKeyInVault = hasKey,
            lastSyncAtMs = lastSyncAt,
            lastStatusKo = statusKo,
            syncedBubbleCount = syncedBubbles,
            syncedRoutineCount = syncedRoutines,
            isSyncing = false
        )
    }

    private fun persistNonSecretState(state: FirestoreSyncUiState) {
        val editor = prefs?.edit() ?: return
        editor
            .putBoolean(KEY_ENABLED, state.config.enabled)
            .putString(KEY_PROJECT_ID, state.config.projectId)
            .putString(KEY_APPLICATION_ID, state.config.applicationId)
            .putString(KEY_DATABASE_ID, state.config.databaseId)
            .putString(KEY_EDITION_MODE, state.config.editionMode.id)
            .putString(KEY_USER_UID, state.config.userUid)
            .putLong(KEY_LAST_SYNC_AT, state.lastSyncAtMs)
            .putString(KEY_LAST_STATUS_KO, state.lastStatusKo)
            .putInt(KEY_SYNCED_BUBBLE_COUNT, state.syncedBubbleCount)
            .putInt(KEY_SYNCED_ROUTINE_COUNT, state.syncedRoutineCount)
            .apply()
    }
}
