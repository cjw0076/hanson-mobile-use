package com.hanson.mobileuse.workspace

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

enum class AgentWorkMode(
    val id: String,
    val label: String,
    val shortBadge: String,
    val description: String
) {
    AUTO(
        id = "auto",
        label = "Auto",
        shortBadge = "AUTO",
        description = "Automatically balances planning, workspace edits, and mobile actions"
    ),
    PLAN(
        id = "plan",
        label = "Plan",
        shortBadge = "PLAN",
        description = "Drafts a structured PLAN.md blueprint before making edits or running actions"
    ),
    BUILD(
        id = "build",
        label = "Build",
        shortBadge = "BUILD",
        description = "Directly edits workspace files, runs checks, and executes device tasks"
    );

    companion object {
        fun fromId(id: String?): AgentWorkMode {
            val normalized = id?.trim()?.lowercase(Locale.ROOT) ?: return AUTO
            return entries.firstOrNull {
                it.id == normalized || it.name.lowercase(Locale.ROOT) == normalized
            } ?: AUTO
        }
    }
}

data class SessionThread(
    val id: String,
    val title: String,
    val mode: AgentWorkMode = AgentWorkMode.AUTO,
    val isForeground: Boolean = true,
    val statusBadge: String = "IDLE",
    val foregroundPackage: String = "",
    val unreadCount: Int = 0,
    val updatedAtMs: Long = 0L,
    val previewLine: String = "",
    val planMarkdown: String = "",
    val walkthroughMarkdown: String = "",
    val hasPendingApproval: Boolean = false
)

data class SessionStoreState(
    val activeSessionId: String = "default-session",
    val sessions: List<SessionThread> = emptyList()
) {
    val activeSession: SessionThread?
        get() = sessions.firstOrNull { it.id == activeSessionId } ?: sessions.firstOrNull()
}

data class SlashCommandOption(
    val command: String,
    val label: String,
    val description: String,
    val targetMode: AgentWorkMode? = null
)

data class MentionSuggestion(
    val token: String,
    val label: String,
    val description: String,
    val category: String
)

data class ParsedComposerInput(
    val rawText: String,
    val slashCommand: String?,
    val resolvedMode: AgentWorkMode?,
    val mentions: List<String>,
    val cleanPrompt: String
)

/**
 * Persistent multi-session store for foreground/background agent threads,
 * per-session work modes (`Auto`, `Plan`, `Build`), artifacts (`PLAN.md` / `WALKTHROUGH.md`),
 * unread counters, and approval alerts.
 */
class AgentSessionStore {

    companion object {
        private const val DEFAULT_SESSION_ID = "default-session"
        private const val DEFAULT_SESSION_TITLE = "Primary Studio"
        private const val STORE_RELATIVE_PATH = "agent_sessions/sessions.json"
        private const val SCHEMA_VERSION = 1
    }

    private val storageFile: File
    private val lock = Any()
    private val sessionSeq = AtomicInteger(0)
    private val _state: MutableStateFlow<SessionStoreState>
    val state: StateFlow<SessionStoreState>

    constructor(context: Context) : this(
        File((context.applicationContext ?: context).filesDir, STORE_RELATIVE_PATH)
    )

    constructor(storageFile: File) {
        this.storageFile = storageFile.canonicalFile
        val initial = synchronized(lock) { loadOrInitializeLocked() }
        this._state = MutableStateFlow(initial)
        this.state = _state.asStateFlow()
    }

    fun createSession(
        title: String,
        mode: AgentWorkMode = AgentWorkMode.AUTO,
        isForeground: Boolean = true
    ): SessionThread = synchronized(lock) {
        val now = System.currentTimeMillis()
        val seq = sessionSeq.incrementAndGet()
        val newId = "session_${now}_$seq"
        val cleanTitle = title.trim().ifBlank { "Session #$seq" }
        val thread = SessionThread(
            id = newId,
            title = cleanTitle,
            mode = mode,
            isForeground = isForeground,
            statusBadge = "IDLE",
            updatedAtMs = now
        )
        val current = _state.value
        val updatedState = current.copy(
            activeSessionId = newId,
            sessions = listOf(thread) + current.sessions
        )
        commitStateLocked(updatedState)
        thread
    }

    fun selectSession(sessionId: String): Boolean = synchronized(lock) {
        val current = _state.value
        if (current.sessions.none { it.id == sessionId }) {
            return@synchronized false
        }
        if (current.activeSessionId != sessionId) {
            commitStateLocked(current.copy(activeSessionId = sessionId))
        }
        true
    }

    fun updateActiveMode(mode: AgentWorkMode): SessionThread? = synchronized(lock) {
        val current = _state.value
        val targetId = current.activeSession?.id ?: return@synchronized null
        val now = System.currentTimeMillis()
        var updatedThread: SessionThread? = null
        val updatedList = current.sessions.map { session ->
            if (session.id == targetId) {
                session.copy(mode = mode, updatedAtMs = now).also { updatedThread = it }
            } else {
                session
            }
        }
        if (updatedThread != null) {
            commitStateLocked(current.copy(sessions = updatedList))
        }
        updatedThread
    }

    fun toggleForeground(sessionId: String): SessionThread? = synchronized(lock) {
        val current = _state.value
        val now = System.currentTimeMillis()
        var updatedThread: SessionThread? = null
        val updatedList = current.sessions.map { session ->
            if (session.id == sessionId) {
                session.copy(
                    isForeground = !session.isForeground,
                    updatedAtMs = now
                ).also { updatedThread = it }
            } else {
                session
            }
        }
        if (updatedThread != null) {
            commitStateLocked(current.copy(sessions = updatedList))
        }
        updatedThread
    }

    fun updateSessionStatus(
        sessionId: String,
        statusBadge: String,
        previewLine: String = "",
        foregroundPackage: String = "",
        hasPendingApproval: Boolean = false
    ): SessionThread? = synchronized(lock) {
        val current = _state.value
        val now = System.currentTimeMillis()
        var updatedThread: SessionThread? = null
        val updatedList = current.sessions.map { session ->
            if (session.id == sessionId) {
                val isBackgroundInactive = session.id != current.activeSessionId
                val shouldIncrementUnread = isBackgroundInactive && (
                    previewLine.isNotBlank() ||
                        hasPendingApproval ||
                        statusBadge != session.statusBadge
                    )
                session.copy(
                    statusBadge = statusBadge.trim().ifBlank { session.statusBadge },
                    previewLine = if (previewLine.isNotBlank()) previewLine else session.previewLine,
                    foregroundPackage = if (foregroundPackage.isNotBlank()) foregroundPackage else session.foregroundPackage,
                    hasPendingApproval = hasPendingApproval,
                    unreadCount = if (shouldIncrementUnread) session.unreadCount + 1 else session.unreadCount,
                    updatedAtMs = now
                ).also { updatedThread = it }
            } else {
                session
            }
        }
        if (updatedThread != null) {
            commitStateLocked(current.copy(sessions = updatedList))
        }
        updatedThread
    }

    fun updateArtifacts(
        sessionId: String,
        planMarkdown: String = "",
        walkthroughMarkdown: String = ""
    ): SessionThread? = synchronized(lock) {
        val current = _state.value
        val now = System.currentTimeMillis()
        var updatedThread: SessionThread? = null
        val updatedList = current.sessions.map { session ->
            if (session.id == sessionId) {
                session.copy(
                    planMarkdown = if (planMarkdown.isNotEmpty()) planMarkdown else session.planMarkdown,
                    walkthroughMarkdown = if (walkthroughMarkdown.isNotEmpty()) walkthroughMarkdown else session.walkthroughMarkdown,
                    updatedAtMs = now
                ).also { updatedThread = it }
            } else {
                session
            }
        }
        if (updatedThread != null) {
            commitStateLocked(current.copy(sessions = updatedList))
        }
        updatedThread
    }

    fun markAllRead(sessionId: String): SessionThread? = synchronized(lock) {
        val current = _state.value
        var updatedThread: SessionThread? = null
        val updatedList = current.sessions.map { session ->
            if (session.id == sessionId) {
                session.copy(unreadCount = 0).also { updatedThread = it }
            } else {
                session
            }
        }
        if (updatedThread != null) {
            commitStateLocked(current.copy(sessions = updatedList))
        }
        updatedThread
    }

    private fun loadOrInitializeLocked(): SessionStoreState {
        if (storageFile.isFile && storageFile.length() > 0L) {
            val loaded = runCatching {
                val raw = storageFile.readText(StandardCharsets.UTF_8)
                parseStoreJson(JSONObject(raw))
            }.getOrNull()
            if (loaded != null && loaded.sessions.isNotEmpty()) {
                return loaded
            }
        }
        val defaultThread = SessionThread(
            id = DEFAULT_SESSION_ID,
            title = DEFAULT_SESSION_TITLE,
            mode = AgentWorkMode.AUTO,
            isForeground = true,
            statusBadge = "IDLE",
            updatedAtMs = System.currentTimeMillis()
        )
        val initial = SessionStoreState(
            activeSessionId = defaultThread.id,
            sessions = listOf(defaultThread)
        )
        persistStateLocked(initial)
        return initial
    }

    private fun commitStateLocked(next: SessionStoreState) {
        _state.value = next
        persistStateLocked(next)
    }

    private fun persistStateLocked(state: SessionStoreState) {
        val parent = storageFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val json = serializeStoreJson(state).toString(2)
        val tmpFile = File(parent ?: File("."), "${storageFile.name}.tmp")
        tmpFile.writeText(json, StandardCharsets.UTF_8)
        runCatching {
            Files.move(
                tmpFile.toPath(),
                storageFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }.onFailure {
            storageFile.writeText(json, StandardCharsets.UTF_8)
            tmpFile.delete()
        }
    }

    private fun serializeStoreJson(state: SessionStoreState): JSONObject {
        val arr = JSONArray()
        for (session in state.sessions) {
            arr.put(
                JSONObject().apply {
                    put("id", session.id)
                    put("title", session.title)
                    put("mode", session.mode.id)
                    put("isForeground", session.isForeground)
                    put("statusBadge", session.statusBadge)
                    put("foregroundPackage", session.foregroundPackage)
                    put("unreadCount", session.unreadCount)
                    put("updatedAtMs", session.updatedAtMs)
                    put("previewLine", session.previewLine)
                    put("planMarkdown", session.planMarkdown)
                    put("walkthroughMarkdown", session.walkthroughMarkdown)
                    put("hasPendingApproval", session.hasPendingApproval)
                }
            )
        }
        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("activeSessionId", state.activeSessionId)
            put("sessions", arr)
        }
    }

    private fun parseStoreJson(root: JSONObject): SessionStoreState {
        val arr = root.optJSONArray("sessions") ?: JSONArray()
        val sessions = mutableListOf<SessionThread>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id", "").trim()
            if (id.isEmpty()) continue
            sessions.add(
                SessionThread(
                    id = id,
                    title = obj.optString("title", DEFAULT_SESSION_TITLE),
                    mode = AgentWorkMode.fromId(obj.optString("mode", AgentWorkMode.AUTO.id)),
                    isForeground = obj.optBoolean("isForeground", true),
                    statusBadge = obj.optString("statusBadge", "IDLE"),
                    foregroundPackage = obj.optString("foregroundPackage", ""),
                    unreadCount = obj.optInt("unreadCount", 0).coerceAtLeast(0),
                    updatedAtMs = obj.optLong("updatedAtMs", 0L),
                    previewLine = obj.optString("previewLine", ""),
                    planMarkdown = obj.optString("planMarkdown", ""),
                    walkthroughMarkdown = obj.optString("walkthroughMarkdown", ""),
                    hasPendingApproval = obj.optBoolean("hasPendingApproval", false)
                )
            )
        }
        if (sessions.isEmpty()) {
            return SessionStoreState()
        }
        val activeId = root.optString("activeSessionId", sessions.first().id)
        val resolvedActiveId = if (sessions.any { it.id == activeId }) activeId else sessions.first().id
        return SessionStoreState(
            activeSessionId = resolvedActiveId,
            sessions = sessions
        )
    }
}

/**
 * Parses and autocompletes `/` slash commands and `@` context mentions
 * inside the mobile agent studio composer.
 */
object ComposerCommandParser {

    private const val MAX_FILE_MENTIONS = 40
    private val MENTION_TOKEN_REGEX = Regex("(?:^|[\\s(\\[])(@[a-zA-Z][a-zA-Z0-9_.:/-]*)")
    private val TRAILING_PUNCT_CHARS = charArrayOf(',', '.', ';', ')', ']', '!', '?')

    val defaultSlashCommands: List<SlashCommandOption> = listOf(
        SlashCommandOption(
            command = "/auto",
            label = "Auto Mode",
            description = "Automatically balance planning, code edits, and device control",
            targetMode = AgentWorkMode.AUTO
        ),
        SlashCommandOption(
            command = "/plan",
            label = "Plan Mode",
            description = "Generate a PLAN.md blueprint and ask before executing",
            targetMode = AgentWorkMode.PLAN
        ),
        SlashCommandOption(
            command = "/build",
            label = "Build Mode",
            description = "Directly edit workspace files and execute device actions",
            targetMode = AgentWorkMode.BUILD
        ),
        SlashCommandOption(
            command = "/init",
            label = "Init AGENTS.md",
            description = "Generate a starter AGENTS.md guide in the workspace root",
            targetMode = null
        ),
        SlashCommandOption(
            command = "/files",
            label = "Workspace Files",
            description = "Open the Code Workbench Files explorer",
            targetMode = null
        ),
        SlashCommandOption(
            command = "/diff",
            label = "Inspect Diffs",
            description = "View unified diffs for modified workspace files",
            targetMode = null
        ),
        SlashCommandOption(
            command = "/undo",
            label = "Checkpoints & Undo",
            description = "Open workspace checkpoints to snapshot or restore state",
            targetMode = null
        ),
        SlashCommandOption(
            command = "/sessions",
            label = "Session Inbox",
            description = "Switch between foreground and background agent sessions",
            targetMode = null
        ),
        SlashCommandOption(
            command = "/vault",
            label = "Blind Vault",
            description = "Manage encrypted credential handles without LLM exposure",
            targetMode = null
        ),
        SlashCommandOption(
            command = "/watch",
            label = "Proactive Watchers",
            description = "View or create screen/ticket cancellation & status monitors",
            targetMode = null
        ),
        SlashCommandOption(
            command = "/a2a",
            label = "A2A Schedule",
            description = "Negotiate calendar availability privately via A2A protocol",
            targetMode = null
        ),
        SlashCommandOption(
            command = "/notes",
            label = "Notetaker Pad",
            description = "Inspect cross-app extracted working memory & scratchpad",
            targetMode = null
        ),
        SlashCommandOption(
            command = "/help",
            label = "Command Help",
            description = "Show available slash commands and @ context mentions",
            targetMode = null
        )
    )

    fun buildMentionSuggestions(
        workspaceFiles: List<WorkspaceFileEntry>,
        foregroundApp: String
    ): List<MentionSuggestion> {
        val suggestions = mutableListOf(
            MentionSuggestion(
                token = "@screen",
                label = "Current Screen",
                description = "Attach active screen snapshot and Set-of-Marks context",
                category = "Context"
            ),
            MentionSuggestion(
                token = "@app",
                label = "Foreground App",
                description = if (foregroundApp.isNotBlank()) {
                    "Target foreground app: $foregroundApp"
                } else {
                    "Target active foreground application"
                },
                category = "App"
            ),
            MentionSuggestion(
                token = "@tree",
                label = "UI Hierarchy Tree",
                description = "Attach live Accessibility UI node tree",
                category = "Context"
            ),
            MentionSuggestion(
                token = "@diff",
                label = "Workspace Diffs",
                description = "Include recent unified file diffs from the workspace",
                category = "Workspace"
            ),
            MentionSuggestion(
                token = "@agents",
                label = "AGENTS.md",
                description = "Include workspace AGENTS.md operating rules",
                category = "Workspace"
            ),
            MentionSuggestion(
                token = "@vault",
                label = "Vault Handles",
                description = "Attach encrypted vault handle names (never raw secrets)",
                category = "Security"
            ),
            MentionSuggestion(
                token = "@notes",
                label = "Notetaker Memory",
                description = "Include cross-app Notetaker scratchpad entries",
                category = "Memory"
            ),
            MentionSuggestion(
                token = "@watchers",
                label = "Active Watchers",
                description = "Include proactive background UI trigger rules",
                category = "Automation"
            )
        )

        workspaceFiles
            .asSequence()
            .filterNot { it.isDirectory }
            .take(MAX_FILE_MENTIONS)
            .forEach { entry ->
                suggestions.add(
                    MentionSuggestion(
                        token = "@file:${entry.relativePath}",
                        label = entry.relativePath,
                        description = "${entry.lineCount} lines · ${entry.sizeBytes} B",
                        category = "File"
                    )
                )
            }

        return suggestions
    }

    fun parse(input: String, currentMode: AgentWorkMode): ParsedComposerInput {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return ParsedComposerInput(
                rawText = input,
                slashCommand = null,
                resolvedMode = currentMode,
                mentions = emptyList(),
                cleanPrompt = ""
            )
        }

        var working = trimmed
        var matchedSlash: SlashCommandOption? = null
        if (working.startsWith("/")) {
            val firstWhitespace = working.indexOfFirst { it.isWhitespace() }
            val candidateToken = if (firstWhitespace < 0) working else working.substring(0, firstWhitespace)
            val option = defaultSlashCommands.firstOrNull {
                it.command.equals(candidateToken, ignoreCase = true)
            }
            if (option != null) {
                matchedSlash = option
                working = if (firstWhitespace < 0) "" else working.substring(firstWhitespace).trimStart()
            }
        }

        val extractedMentions = LinkedHashSet<String>()
        val promptBuilder = StringBuilder()
        var lastEnd = 0
        for (match in MENTION_TOKEN_REGEX.findAll(working)) {
            val tokenGroup = match.groups[1] ?: continue
            val rawToken = tokenGroup.value
            val cleanToken = trimTrailingMentionPunctuation(rawToken)
            if (cleanToken.length > 1) {
                extractedMentions.add(cleanToken)
                promptBuilder.append(working, lastEnd, tokenGroup.range.first)
                val trailingLen = rawToken.length - cleanToken.length
                val hasProseBefore = promptBuilder.any { !it.isWhitespace() }
                if (trailingLen > 0 && hasProseBefore) {
                    promptBuilder.append(rawToken.substring(cleanToken.length))
                }
                lastEnd = tokenGroup.range.last + 1
                if (!hasProseBefore) {
                    while (lastEnd < working.length && working[lastEnd] in TRAILING_PUNCT_CHARS) {
                        lastEnd++
                    }
                }
            }
        }
        promptBuilder.append(working, lastEnd, working.length)

        val cleanPrompt = promptBuilder
            .toString()
            .replace(Regex("\\s+"), " ")
            .trim()

        return ParsedComposerInput(
            rawText = input,
            slashCommand = matchedSlash?.command,
            resolvedMode = matchedSlash?.targetMode ?: currentMode,
            mentions = extractedMentions.toList(),
            cleanPrompt = cleanPrompt
        )
    }

    fun filterSlashSuggestions(input: String): List<SlashCommandOption> {
        val trimmedStart = input.trimStart()
        if (!trimmedStart.startsWith("/")) return emptyList()
        if (trimmedStart.any { it.isWhitespace() }) return emptyList()

        val query = trimmedStart.lowercase(Locale.ROOT)
        if (query == "/") return defaultSlashCommands

        val prefixMatches = defaultSlashCommands.filter {
            it.command.lowercase(Locale.ROOT).startsWith(query)
        }
        if (prefixMatches.isNotEmpty()) return prefixMatches

        val stripped = query.removePrefix("/")
        return defaultSlashCommands.filter {
            it.label.lowercase(Locale.ROOT).contains(stripped) ||
                it.description.lowercase(Locale.ROOT).contains(stripped)
        }
    }

    fun filterMentionSuggestions(
        input: String,
        suggestions: List<MentionSuggestion>
    ): List<MentionSuggestion> {
        if (input.isEmpty() || input.last().isWhitespace()) return emptyList()

        val lastSpace = input.indexOfLast { it.isWhitespace() }
        val activeToken = if (lastSpace < 0) input else input.substring(lastSpace + 1)
        if (!activeToken.startsWith("@")) return emptyList()

        if (activeToken == "@") return suggestions

        val queryLower = activeToken.lowercase(Locale.ROOT)
        val bareQuery = queryLower.removePrefix("@")

        val exactPrefix = mutableListOf<MentionSuggestion>()
        val substringMatches = mutableListOf<MentionSuggestion>()

        for (item in suggestions) {
            val tokenLower = item.token.lowercase(Locale.ROOT)
            val labelLower = item.label.lowercase(Locale.ROOT)
            val descLower = item.description.lowercase(Locale.ROOT)
            when {
                tokenLower.startsWith(queryLower) -> exactPrefix.add(item)
                tokenLower.contains(bareQuery) ||
                    labelLower.contains(bareQuery) ||
                    descLower.contains(bareQuery) -> substringMatches.add(item)
            }
        }
        return exactPrefix + substringMatches
    }

    private fun trimTrailingMentionPunctuation(token: String): String {
        var end = token.length
        while (end > 1 && token[end - 1] in TRAILING_PUNCT_CHARS) {
            end--
        }
        return token.substring(0, end)
    }
}
