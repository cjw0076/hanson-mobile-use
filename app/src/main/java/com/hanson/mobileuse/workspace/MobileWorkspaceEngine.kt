package com.hanson.mobileuse.workspace

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Arrays
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class WorkspaceFileEntry(
    val relativePath: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val isDirectory: Boolean = false,
    val lineCount: Int = 0
)

data class WorkspaceDiffRecord(
    val relativePath: String,
    val unifiedDiff: String,
    val addedLines: Int,
    val removedLines: Int,
    val timestampMs: Long
)

data class WorkspaceCheckpoint(
    val id: String,
    val label: String,
    val createdAtMs: Long,
    val fileCount: Int
)

/**
 * Summary returned by [MobileWorkspaceEngine.exportEncryptedBackup] and
 * [MobileWorkspaceEngine.importEncryptedBackup] for `.hansonbackup` v1 containers.
 */
data class EncryptedBackupResult(
    val fileCount: Int,
    val totalPlaintextBytes: Long,
    val chunkCount: Int,
    val containerVersion: Int = 1
)

data class WorkspaceUiState(
    val workspaceName: String = "mobile-agent-workspace",
    val workspacePath: String = "",
    val hasAgentsMd: Boolean = false,
    val files: List<WorkspaceFileEntry> = emptyList(),
    val selectedFilePath: String? = null,
    val selectedFileContent: String = "",
    val diffs: List<WorkspaceDiffRecord> = emptyList(),
    val checkpoints: List<WorkspaceCheckpoint> = emptyList(),
    val planArtifactMarkdown: String = "",
    val walkthroughArtifactMarkdown: String = "",
    val lastExecOutput: String = ""
)

data class ToolCommandResult(
    val command: String,
    val succeeded: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String = ""
) {
    fun toFormattedOutput(): String = buildString {
        append("$ ").append(command)
        if (stdout.isNotBlank()) {
            append('\n').append(stdout.trimEnd())
        }
        if (stderr.isNotBlank()) {
            append('\n').append("[stderr] ").append(stderr.trimEnd())
        }
        if (!succeeded && stdout.isBlank() && stderr.isBlank()) {
            append('\n').append("(exit code ").append(exitCode).append(')')
        }
    }
}

/**
 * Genuine LCS (Longest Common Subsequence) unified diff engine.
 * Computes standard unified diff hunks (`@@ -oldStart,oldCount +newStart,newCount @@`)
 * and exact added/removed line counts without external binaries.
 */
object UnifiedDiffComputer {

    data class DiffResult(
        val unifiedDiff: String,
        val addedLines: Int,
        val removedLines: Int
    )

    private enum class OpKind {
        CONTEXT,
        REMOVE,
        ADD
    }

    private data class DiffOp(
        val kind: OpKind,
        val line: String,
        val oldLine: Int,
        val newLine: Int
    )

    private const val MAX_DP_CELLS = 4_000_000L

    internal fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        val raw = normalized.split("\n")
        return if (normalized.endsWith("\n") && raw.isNotEmpty() && raw.last().isEmpty()) {
            raw.dropLast(1)
        } else {
            raw
        }
    }

    fun computeUnifiedDiff(
        relativePath: String,
        oldText: String,
        newText: String,
        contextLines: Int = 3
    ): DiffResult {
        val oldLines = splitLines(oldText)
        val newLines = splitLines(newText)
        if (oldLines == newLines) {
            return DiffResult(unifiedDiff = "", addedLines = 0, removedLines = 0)
        }

        val ops = buildLcsOperations(oldLines, newLines)
        val addedCount = ops.count { it.kind == OpKind.ADD }
        val removedCount = ops.count { it.kind == OpKind.REMOVE }
        if (addedCount == 0 && removedCount == 0) {
            return DiffResult(unifiedDiff = "", addedLines = 0, removedLines = 0)
        }

        val ctx = contextLines.coerceAtLeast(0)
        val changedIndices = ops.indices.filter { ops[it].kind != OpKind.CONTEXT }
        val ranges = mutableListOf<IntRange>()
        for (idx in changedIndices) {
            val start = (idx - ctx).coerceAtLeast(0)
            val end = (idx + ctx).coerceAtMost(ops.lastIndex)
            if (ranges.isEmpty() || start > ranges.last().last + 1) {
                ranges.add(start..end)
            } else {
                val prev = ranges.removeAt(ranges.lastIndex)
                ranges.add(prev.first..maxOf(prev.last, end))
            }
        }

        val cleanPath = relativePath.trimStart('/', '\\').ifBlank { "file" }
        val out = StringBuilder()
        out.append("--- a/").append(cleanPath).append('\n')
        out.append("+++ b/").append(cleanPath).append('\n')

        for (range in ranges) {
            val slice = ops.subList(range.first, range.last + 1)
            val oldCount = slice.count { it.kind != OpKind.ADD }
            val newCount = slice.count { it.kind != OpKind.REMOVE }
            val firstOld = slice.firstOrNull { it.kind != OpKind.ADD }
            val firstNew = slice.firstOrNull { it.kind != OpKind.REMOVE }
            val oldStart = if (oldCount == 0) 0 else firstOld?.oldLine ?: 1
            val newStart = if (newCount == 0) 0 else firstNew?.newLine ?: 1

            out.append("@@ -")
                .append(oldStart).append(',').append(oldCount)
                .append(" +")
                .append(newStart).append(',').append(newCount)
                .append(" @@\n")

            for (op in slice) {
                val prefix = when (op.kind) {
                    OpKind.CONTEXT -> ' '
                    OpKind.REMOVE -> '-'
                    OpKind.ADD -> '+'
                }
                out.append(prefix).append(op.line).append('\n')
            }
        }

        return DiffResult(
            unifiedDiff = out.toString().trimEnd('\n'),
            addedLines = addedCount,
            removedLines = removedCount
        )
    }

    private fun buildLcsOperations(oldLines: List<String>, newLines: List<String>): List<DiffOp> {
        val totalOld = oldLines.size
        val totalNew = newLines.size

        var prefixLen = 0
        val minSize = minOf(totalOld, totalNew)
        while (prefixLen < minSize && oldLines[prefixLen] == newLines[prefixLen]) {
            prefixLen++
        }

        var suffixLen = 0
        while (suffixLen < (minSize - prefixLen) &&
            oldLines[totalOld - 1 - suffixLen] == newLines[totalNew - 1 - suffixLen]
        ) {
            suffixLen++
        }

        val ops = ArrayList<DiffOp>(totalOld + totalNew)
        for (k in 0 until prefixLen) {
            ops.add(DiffOp(OpKind.CONTEXT, oldLines[k], k + 1, k + 1))
        }

        val oldMid = oldLines.subList(prefixLen, totalOld - suffixLen)
        val newMid = newLines.subList(prefixLen, totalNew - suffixLen)
        val m = oldMid.size
        val n = newMid.size

        var oldLineNo = prefixLen + 1
        var newLineNo = prefixLen + 1

        if (m.toLong() * n.toLong() <= MAX_DP_CELLS) {
            val dp = Array(m + 1) { IntArray(n + 1) }
            for (i in m - 1 downTo 0) {
                for (j in n - 1 downTo 0) {
                    dp[i][j] = if (oldMid[i] == newMid[j]) {
                        dp[i + 1][j + 1] + 1
                    } else {
                        maxOf(dp[i + 1][j], dp[i][j + 1])
                    }
                }
            }

            var i = 0
            var j = 0
            while (i < m && j < n) {
                if (oldMid[i] == newMid[j]) {
                    ops.add(DiffOp(OpKind.CONTEXT, oldMid[i], oldLineNo, newLineNo))
                    i++
                    j++
                    oldLineNo++
                    newLineNo++
                } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                    ops.add(DiffOp(OpKind.REMOVE, oldMid[i], oldLineNo, newLineNo))
                    i++
                    oldLineNo++
                } else {
                    ops.add(DiffOp(OpKind.ADD, newMid[j], oldLineNo, newLineNo))
                    j++
                    newLineNo++
                }
            }
            while (i < m) {
                ops.add(DiffOp(OpKind.REMOVE, oldMid[i], oldLineNo, newLineNo))
                i++
                oldLineNo++
            }
            while (j < n) {
                ops.add(DiffOp(OpKind.ADD, newMid[j], oldLineNo, newLineNo))
                j++
                newLineNo++
            }
        } else {
            for (line in oldMid) {
                ops.add(DiffOp(OpKind.REMOVE, line, oldLineNo, newLineNo))
                oldLineNo++
            }
            for (line in newMid) {
                ops.add(DiffOp(OpKind.ADD, line, oldLineNo, newLineNo))
                newLineNo++
            }
        }

        for (k in 0 until suffixLen) {
            val oldIdx = totalOld - suffixLen + k
            ops.add(DiffOp(OpKind.CONTEXT, oldLines[oldIdx], oldLineNo, newLineNo))
            oldLineNo++
            newLineNo++
        }

        return ops
    }
}

/**
 * Sandboxed on-device workspace engine supporting path-contained file CRUD,
 * LCS unified diffs, text search, allowlisted read-only/diagnostic shell-free
 * workspace commands, `AGENTS.md` initialization, checkpoints, and Markdown artifacts.
 */
class MobileWorkspaceEngine {

    companion object {
        private const val DEFAULT_WORKSPACE_DIR = "agent_workspace"
        private const val DEFAULT_WORKSPACE_NAME = "mobile-agent-workspace"
        private const val CHECKPOINTS_DIR = ".checkpoints"
        private const val MAX_DIFF_HISTORY = 50
        private const val MAX_SEARCH_RESULTS = 200
        private const val MAX_SEARCH_FILE_BYTES = 2 * 1024 * 1024L
        private const val MAX_REPOMAP_FILE_BYTES = 512 * 1024L
        private const val MAX_EXEC_OUTPUT_CHARS = 4_000

        /** `.hansonbackup` v1 container constants (PBKDF2WithHmacSHA256 + 1 MiB AES-256-GCM chunks). */
        val BACKUP_MAGIC: ByteArray = "HSNBKP01".toByteArray(StandardCharsets.US_ASCII)
        const val BACKUP_CONTAINER_VERSION: Int = 1
        const val BACKUP_CHUNK_SIZE_BYTES: Int = 1024 * 1024
        const val BACKUP_PBKDF2_ITERATIONS: Int = 120_000
        private const val BACKUP_SALT_BYTES: Int = 16
        private const val BACKUP_NONCE_BYTES: Int = 12
        private const val BACKUP_GCM_TAG_BITS: Int = 128
        private const val BACKUP_MAX_ARCHIVE_BYTES: Long = 128L * 1024L * 1024L

        private val IGNORED_DIRS = setOf(CHECKPOINTS_DIR, ".git", ".gradle", "build")
        private val SHELL_METACHAR_REGEX = Regex("[;&|`$()><\\n\\r\\u0000\\\\]")
        private val CHECKPOINT_ID_REGEX = Regex("^[a-zA-Z0-9_-]+$")
        private val ALLOWED_COMMANDS = setOf(
            "ls", "cat", "wc", "grep", "tree", "pwd", "diff", "echo", "status"
        )

        private val KT_SYMBOL_REGEX = Regex(
            """^\s*(?:(?:public|private|internal|protected|open|final|abstract|sealed|data|enum|annotation|value|inline|inner|override|suspend|operator|infix|tailrec|external|actual|expect|const)\s+)*(class|object|interface|typealias|fun)\s+([A-Za-z_][A-Za-z0-9_<>]*(?:\([^)]*\))?(?:\s*:\s*[A-Za-z0-9_<>?.,\s]+?)?)(?=\s*[{=]|$)"""
        )
        private val JAVA_TYPE_REGEX = Regex(
            """^\s*(?:(?:public|private|protected|abstract|final|static|sealed|non-sealed)\s+)*(class|interface|enum|record)\s+([A-Za-z_][A-Za-z0-9_<>]*)"""
        )
        private val JAVA_METHOD_REGEX = Regex(
            """^\s*(?:public|private|protected)\s+(?:static\s+)?(?:final\s+)?(?:synchronized\s+)?[A-Za-z0-9_<>?\[\],\s]+\s+([A-Za-z_][A-Za-z0-9_]*\s*\([^;{}]*\))\s*(?:throws\s+[A-Za-z0-9_.,\s]+)?\{"""
        )
        private val PY_SYMBOL_REGEX = Regex(
            """^\s*(?:async\s+)?(class|def)\s+([A-Za-z_][A-Za-z0-9_]*(?:\([^)]*\))?)"""
        )
        private val SWIFT_SYMBOL_REGEX = Regex(
            """^\s*(?:(?:public|private|fileprivate|internal|open|final|static|class|override|mutating|async)\s+)*(class|struct|enum|protocol|actor|extension|func)\s+([A-Za-z_][A-Za-z0-9_<>]*(?:\([^)]*\))?)"""
        )
        private val TS_JS_SYMBOL_REGEX = Regex(
            """^\s*(?:export\s+)?(?:default\s+)?(?:declare\s+)?(?:abstract\s+)?(?:async\s+)?(class|interface|type|enum|function)\s+([A-Za-z_$][A-Za-z0-9_$<>]*(?:\([^)]*\))?)"""
        )
        private val TS_JS_ARROW_REGEX = Regex(
            """^\s*(?:export\s+)?const\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*=\s*(?:async\s+)?\([^)]*\)\s*(?::[^=]+)?=>"""
        )

        /**
         * Estimates token count with CJK/Hangul awareness (~4 ASCII chars/token, ~1.5 non-ASCII chars/token).
         */
        fun estimateTokens(text: String): Int {
            if (text.isEmpty()) return 0
            var ascii = 0
            var nonAscii = 0
            for (ch in text) {
                if (ch.code <= 0x7F) ascii++ else nonAscii++
            }
            val est = (ascii / 4.0) + (nonAscii / 1.5)
            return kotlin.math.ceil(est).toInt().coerceAtLeast(1)
        }

        /**
         * 80% context-ceiling auto-compaction absorbed from `Sanuu7/AndroidHarness` (`AutoCompaction.kt`).
         * Preserves the head anchor message, the most recent tail messages, and a concise summary
         * digest of compacted middle messages whenever total estimated tokens exceed `maxTokenBudget * thresholdRatio`.
         */
        fun autoCompactContext(
            messages: List<String>,
            maxTokenBudget: Int,
            thresholdRatio: Double = 0.80
        ): Pair<List<String>, Boolean> {
            if (messages.size <= 2) return messages to false
            val safeBudget = maxTokenBudget.coerceAtLeast(32)
            val safeRatio = thresholdRatio.coerceIn(0.10, 0.99)
            val thresholdTokens = (safeBudget * safeRatio).toInt().coerceAtLeast(16)

            val totalTokens = messages.sumOf { estimateTokens(it) }
            if (totalTokens <= thresholdTokens) {
                return messages to false
            }

            val headBudget = (thresholdTokens * 0.25).toInt().coerceAtLeast(12)
            val rawHead = messages.first()
            val head = trimToTokenLimit(rawHead, headBudget)
            val headTokens = estimateTokens(head)

            val tailBudget = (thresholdTokens * 0.50).toInt().coerceAtLeast(16)
            val keptTailReversed = mutableListOf<String>()
            var tailTokens = 0
            var tailStartIndex = messages.size

            for (idx in messages.lastIndex downTo 2) {
                val msg = messages[idx]
                val tok = estimateTokens(msg)
                if (keptTailReversed.isEmpty()) {
                    val trimmedLast = trimToTokenLimit(msg, tailBudget)
                    keptTailReversed.add(trimmedLast)
                    tailTokens += estimateTokens(trimmedLast)
                    tailStartIndex = idx
                } else if (tailTokens + tok <= tailBudget) {
                    keptTailReversed.add(msg)
                    tailTokens += tok
                    tailStartIndex = idx
                } else {
                    break
                }
            }

            val dropped = messages.subList(1, tailStartIndex.coerceAtLeast(2))
            val remainingDigestBudget = (thresholdTokens - headTokens - tailTokens).coerceAtLeast(16)
            val digest = buildCompactionDigest(dropped, remainingDigestBudget)

            val compacted = ArrayList<String>(2 + keptTailReversed.size)
            compacted.add(head)
            compacted.add(digest)
            compacted.addAll(keptTailReversed.asReversed())
            return compacted to true
        }

        private fun trimToTokenLimit(text: String, maxTokens: Int): String {
            if (estimateTokens(text) <= maxTokens) return text
            val approxChars = (maxTokens * 3).coerceAtLeast(12)
            if (text.length <= approxChars) return text
            return text.take(approxChars).trimEnd() + " …"
        }

        private fun buildCompactionDigest(dropped: List<String>, maxTokens: Int): String {
            val header = "[Compacted Context Digest: ${dropped.size} earlier turns summarized]"
            val sb = StringBuilder(header)
            val perItemChars = ((maxTokens * 3) / dropped.size.coerceAtLeast(1)).coerceIn(24, 140)
            for ((i, msg) in dropped.withIndex()) {
                val oneLine = msg.replace(Regex("\\s+"), " ").trim()
                val snippet = if (oneLine.length > perItemChars) oneLine.take(perItemChars) + "…" else oneLine
                val candidate = "\n- #${i + 1}: $snippet"
                if (estimateTokens(sb.toString() + candidate) > maxTokens && i > 0) {
                    sb.append("\n- ... (+${dropped.size - i} more turns)")
                    break
                }
                sb.append(candidate)
            }
            return sb.toString()
        }
    }

    val workspaceRoot: File
    private val rootCanonical: File
    private val lock = Any()
    private val checkpointSequence = AtomicInteger(0)
    private val filePreEditBaselines = LinkedHashMap<String, String?>()
    private val _state: MutableStateFlow<WorkspaceUiState>
    val state: StateFlow<WorkspaceUiState>

    constructor(context: Context) : this(
        File((context.applicationContext ?: context).filesDir, DEFAULT_WORKSPACE_DIR)
    )

    constructor(workspaceRootDir: File) {
        if (!workspaceRootDir.exists()) {
            workspaceRootDir.mkdirs()
        }
        val canonical = workspaceRootDir.canonicalFile
        this.workspaceRoot = canonical
        this.rootCanonical = canonical
        val displayName = if (canonical.name == DEFAULT_WORKSPACE_DIR) {
            DEFAULT_WORKSPACE_NAME
        } else {
            canonical.name.ifBlank { DEFAULT_WORKSPACE_NAME }
        }
        this._state = MutableStateFlow(
            WorkspaceUiState(
                workspaceName = displayName,
                workspacePath = canonical.absolutePath
            )
        )
        this.state = _state.asStateFlow()
        synchronized(lock) {
            refreshIndexLocked()
        }
    }

    /**
     * Resolves [relativePath] strictly inside [rootCanonical], rejecting empty/blank inputs,
     * absolute/drive paths, null bytes, symlink escapes, and sibling prefix collisions
     * (e.g. `../workspace2`) with [SecurityException].
     */
    fun resolveSafePath(relativePath: String): File {
        if (relativePath.isBlank()) {
            throw SecurityException("Workspace path must not be empty or blank.")
        }
        if (relativePath.indexOf('\u0000') >= 0) {
            throw SecurityException("Workspace path contains forbidden null byte.")
        }
        val trimmed = relativePath.trim()
        if (trimmed.startsWith("/") || trimmed.startsWith("\\")) {
            throw SecurityException("Absolute paths are forbidden inside workspace: $relativePath")
        }
        if (trimmed.length >= 2 && trimmed[0].isLetter() && trimmed[1] == ':') {
            throw SecurityException("Drive-qualified paths are forbidden inside workspace: $relativePath")
        }
        if (File(trimmed).isAbsolute) {
            throw SecurityException("Absolute paths are forbidden inside workspace: $relativePath")
        }

        val normalized = trimmed.replace('\\', '/')
        if (File(normalized).isAbsolute) {
            throw SecurityException("Absolute paths are forbidden inside workspace: $relativePath")
        }

        val candidateCanonical = File(rootCanonical, normalized).canonicalFile
        val rootPath = rootCanonical.path
        val candidatePath = candidateCanonical.path
        val rootPrefix = if (rootPath.endsWith(File.separator)) rootPath else rootPath + File.separator
        val insideByPath = candidatePath == rootPath || candidatePath.startsWith(rootPrefix)
        val insideByNio = candidateCanonical.toPath().startsWith(rootCanonical.toPath())

        if (!insideByPath || !insideByNio) {
            throw SecurityException("Path escapes workspace sandbox: $relativePath")
        }
        return candidateCanonical
    }

    /**
     * Ensures workspace directory exists and populates starter workspace files when empty,
     * or refreshes the indexed file/checkpoint state when files already exist.
     */
    fun initWorkspace(): WorkspaceUiState = synchronized(lock) {
        if (!rootCanonical.exists()) {
            rootCanonical.mkdirs()
        }
        val existingFiles = collectFileEntriesLocked(rootCanonical, maxDepth = 8)
        if (existingFiles.isEmpty()) {
            val readmeContent = buildString {
                appendLine("# Mobile Agent Workspace")
                appendLine()
                appendLine("Sandboxed workspace for planning, file editing, diff inspection, and verification.")
                appendLine()
                appendLine("## Quick Commands")
                appendLine("- `/plan` — Draft an execution blueprint before applying changes")
                appendLine("- `/build` — Apply workspace edits and run mobile actions")
                appendLine("- `/init` — Generate `AGENTS.md` workspace guidance")
                appendLine("- `/diff` — Inspect unified diffs for recent edits")
            }
            writeFileLocked("README.md", readmeContent)

            val notesContent = buildString {
                appendLine("# Session Scratchpad")
                appendLine()
                appendLine("- Status: Ready")
                appendLine("- Tip: Use `@file:README.md` or `@screen` in the composer to attach context.")
            }
            writeFileLocked("notes/scratchpad.md", notesContent)
            selectFileInternal("README.md")
        } else {
            refreshIndexLocked()
        }
        _state.value
    }

    fun listFiles(subDir: String = "", maxDepth: Int = 4): List<WorkspaceFileEntry> {
        val baseDir = if (subDir.isBlank() || subDir.trim() == ".") {
            rootCanonical
        } else {
            resolveSafePath(subDir)
        }
        return synchronized(lock) {
            if (!baseDir.exists() || !baseDir.isDirectory) {
                emptyList()
            } else {
                collectFileEntriesLocked(baseDir, maxDepth = maxDepth.coerceAtLeast(1))
            }
        }
    }

    fun formatFilesAsText(subDir: String = "", maxDepth: Int = 4): String {
        val entries = listFiles(subDir, maxDepth)
        if (entries.isEmpty()) return "(workspace is empty)"
        return entries.joinToString("\n") { entry ->
            "${entry.relativePath} (${entry.lineCount} lines, ${entry.sizeBytes} bytes)"
        }
    }

    fun formatFilesAsJson(subDir: String = "", maxDepth: Int = 4): String {
        val entries = listFiles(subDir, maxDepth)
        val arr = JSONArray()
        for (entry in entries) {
            arr.put(
                JSONObject().apply {
                    put("path", entry.relativePath)
                    put("sizeBytes", entry.sizeBytes)
                    put("lineCount", entry.lineCount)
                    put("lastModifiedMs", entry.lastModifiedMs)
                    put("isDirectory", entry.isDirectory)
                }
            )
        }
        return arr.toString()
    }

    fun readFile(
        relativePath: String,
        startLine: Int = 1,
        endLine: Int = 200,
        includeLineNumbers: Boolean = false
    ): Result<String> = runCatching {
        val target = resolveSafePath(relativePath)
        synchronized(lock) {
            if (!target.exists() || !target.isFile) {
                throw FileNotFoundException("File not found in workspace: $relativePath")
            }
            val rawText = target.readText(StandardCharsets.UTF_8)
            val lines = UnifiedDiffComputer.splitLines(rawText)
            if (lines.isEmpty()) {
                return@runCatching ""
            }
            val safeStart = startLine.coerceAtLeast(1)
            val safeEnd = endLine.coerceAtLeast(safeStart)
            if (safeStart > lines.size) {
                return@runCatching ""
            }
            val sliceEnd = minOf(safeEnd, lines.size)
            val selected = (safeStart..sliceEnd).map { lineNo ->
                val text = lines[lineNo - 1]
                if (includeLineNumbers) "$lineNo: $text" else text
            }
            selected.joinToString("\n")
        }
    }

    fun selectFile(relativePath: String): Result<String> = runCatching {
        val target = resolveSafePath(relativePath)
        synchronized(lock) {
            selectFileInternal(toRelativePath(target))
        }
    }

    fun writeFile(relativePath: String, content: String): WorkspaceDiffRecord {
        val target = resolveSafePath(relativePath)
        return synchronized(lock) {
            if (target == rootCanonical || (target.exists() && target.isDirectory)) {
                throw IllegalArgumentException("Target path is a directory, not a file: $relativePath")
            }
            val normalizedPath = toRelativePath(target)
            writeFileLocked(normalizedPath, content)
        }
    }

    fun editFile(
        relativePath: String,
        oldText: String,
        newText: String
    ): Result<WorkspaceDiffRecord> = runCatching {
        val target = resolveSafePath(relativePath)
        synchronized(lock) {
            if (!target.exists() || !target.isFile) {
                throw FileNotFoundException("Cannot edit missing file: $relativePath")
            }
            if (oldText.isEmpty()) {
                throw IllegalArgumentException("oldText must not be empty when editing $relativePath.")
            }
            val current = target.readText(StandardCharsets.UTF_8)
            val matches = countOccurrences(current, oldText)
            when {
                matches == 0 -> throw IllegalArgumentException(
                    "Target snippet not found in '$relativePath'. Read the file again before editing."
                )
                matches > 1 -> throw IllegalStateException(
                    "Target snippet matched $matches locations in '$relativePath'; provide more context so oldText is unique."
                )
            }
            val updated = current.replaceFirst(oldText, newText)
            val normalizedPath = toRelativePath(target)
            writeFileLocked(normalizedPath, updated)
        }
    }

    fun searchFiles(
        query: String,
        pathPrefix: String = "",
        ignoreCase: Boolean = true
    ): List<String> {
        if (query.isBlank()) return emptyList()
        val normalizedPrefix = if (pathPrefix.isBlank() || pathPrefix.trim() == ".") {
            ""
        } else {
            val safeTarget = resolveSafePath(pathPrefix)
            toRelativePath(safeTarget)
        }

        return synchronized(lock) {
            val matches = mutableListOf<String>()
            val entries = collectFileEntriesLocked(rootCanonical, maxDepth = 8)
            for (entry in entries) {
                if (normalizedPrefix.isNotEmpty() &&
                    entry.relativePath != normalizedPrefix &&
                    !entry.relativePath.startsWith("$normalizedPrefix/")
                ) {
                    continue
                }
                val file = File(rootCanonical, entry.relativePath)
                if (!file.isFile || file.length() > MAX_SEARCH_FILE_BYTES || isLikelyBinary(file)) {
                    continue
                }
                val lines = file.readLines(StandardCharsets.UTF_8)
                for ((idx, line) in lines.withIndex()) {
                    if (line.contains(query, ignoreCase = ignoreCase)) {
                        matches.add("${entry.relativePath}:${idx + 1}: ${line.trimEnd()}")
                        if (matches.size >= MAX_SEARCH_RESULTS) {
                            return@synchronized matches
                        }
                    }
                }
            }
            matches
        }
    }

    /**
     * Executes allowlisted, shell-free workspace inspection commands inside the sandbox.
     * Rejects shell metacharacters, command chaining, and path escapes cleanly.
     */
    fun execWorkspaceCommand(command: String): ToolCommandResult {
        val raw = command.trim()
        val result = executeCommandInternal(raw)
        synchronized(lock) {
            val summary = result.toFormattedOutput().take(MAX_EXEC_OUTPUT_CHARS)
            _state.update { it.copy(lastExecOutput = summary) }
        }
        return result
    }

    /**
     * Generates a clean starter `AGENTS.md` inside the workspace root.
     */
    fun initAgentsMd(projectSummary: String = ""): WorkspaceDiffRecord {
        val summary = projectSummary.trim().ifBlank {
            "On-device mobile agent workspace for structured planning, sandboxed file editing, diff inspection, and app automation."
        }
        val content = buildString {
            appendLine("# AGENTS.md")
            appendLine()
            appendLine("## Workspace Overview")
            appendLine(summary)
            appendLine()
            appendLine("## Operating Modes")
            appendLine("- **Auto**: Balances planning, workspace edits, and mobile actions automatically.")
            appendLine("- **Plan**: Drafts `PLAN.md` and waits for user confirmation before mutating files or apps.")
            appendLine("- **Build**: Executes sandboxed file edits, workspace checks, and approved device actions.")
            appendLine()
            appendLine("## Verification & Safety Rules")
            appendLine("- Keep all workspace paths relative to the sandbox root; never use absolute or parent (`..`) escapes.")
            appendLine("- Capture a checkpoint before multi-file edits so changes can be restored cleanly.")
            appendLine("- Inspect unified diffs and run safe workspace commands (`status`, `tree`, `grep`, `diff`, `wc`) after edits.")
            appendLine("- Never include private credentials, tokens, or personal host paths in workspace artifacts.")
            appendLine()
            appendLine("## Artifacts")
            appendLine("- `PLAN.md` — Active task plan and acceptance checklist")
            appendLine("- `WALKTHROUGH.md` — Summary of verified changes and command output")
        }
        return writeFile("AGENTS.md", content)
    }

    fun captureCheckpoint(label: String): WorkspaceCheckpoint = synchronized(lock) {
        val now = System.currentTimeMillis()
        val seq = checkpointSequence.incrementAndGet()
        val id = "cp_${now}_$seq"
        val cleanLabel = label.trim().ifBlank { "Snapshot #$seq" }

        val cpRoot = File(rootCanonical, "$CHECKPOINTS_DIR/$id")
        val filesSnapshotDir = File(cpRoot, "files")
        filesSnapshotDir.mkdirs()

        val currentEntries = collectFileEntriesLocked(rootCanonical, maxDepth = 12)
        var copiedCount = 0
        for (entry in currentEntries) {
            val src = File(rootCanonical, entry.relativePath)
            if (isSymbolicLinkSafe(src) || !src.isFile) continue
            val dst = File(filesSnapshotDir, entry.relativePath)
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
            copiedCount++
        }

        val checkpoint = WorkspaceCheckpoint(
            id = id,
            label = cleanLabel,
            createdAtMs = now,
            fileCount = copiedCount
        )
        val metaJson = JSONObject().apply {
            put("id", checkpoint.id)
            put("label", checkpoint.label)
            put("createdAtMs", checkpoint.createdAtMs)
            put("fileCount", checkpoint.fileCount)
        }
        File(cpRoot, "meta.json").writeText(metaJson.toString(2), StandardCharsets.UTF_8)

        filePreEditBaselines.clear()
        refreshIndexLocked()
        checkpoint
    }

    /**
     * Per-file undo absorbed from `techjarves/Mobile-Harness` (`WorkspaceCheckpoints.kt`).
     * Restores ONLY [relativePath] to its baseline from the latest (or specified) checkpoint, or
     * from its pre-edit session baseline when no checkpoint exists. Deletes newly created files
     * that were not present in the baseline.
     */
    fun undoFileChange(relativePath: String, checkpointId: String? = null): Boolean {
        val target = resolveSafePath(relativePath)
        val normalizedPath = toRelativePath(target)
        return synchronized(lock) {
            val checkpointDir = resolveCheckpointDirLocked(checkpointId)
            if (checkpointDir != null) {
                val filesDir = File(checkpointDir, "files")
                val snapFile = File(filesDir, normalizedPath)
                val snapCanonical = runCatching { snapFile.canonicalFile }.getOrNull()
                val filesDirCanonical = runCatching { filesDir.canonicalFile }.getOrNull()
                val snapSafe = snapCanonical != null &&
                    filesDirCanonical != null &&
                    snapCanonical.toPath().startsWith(filesDirCanonical.toPath()) &&
                    !isSymbolicLinkSafe(snapFile)

                if (snapSafe && snapFile.isFile) {
                    val sameBytes = target.isFile &&
                        !isSymbolicLinkSafe(target) &&
                        target.readBytes().contentEquals(snapFile.readBytes())
                    if (sameBytes) {
                        return@synchronized false
                    }
                    target.parentFile?.mkdirs()
                    snapFile.copyTo(target, overwrite = true)
                    filePreEditBaselines.remove(normalizedPath)
                    clearDiffsForPathLocked(normalizedPath)
                    refreshSelectedContentIfMatchesLocked(normalizedPath, target)
                    refreshIndexLocked()
                    return@synchronized true
                } else {
                    if (target.isFile && !isSymbolicLinkSafe(target)) {
                        target.delete()
                        pruneEmptyParents(target.parentFile)
                        filePreEditBaselines.remove(normalizedPath)
                        clearDiffsForPathLocked(normalizedPath)
                        refreshSelectedContentIfMatchesLocked(normalizedPath, target)
                        refreshIndexLocked()
                        return@synchronized true
                    }
                    return@synchronized false
                }
            }

            if (!filePreEditBaselines.containsKey(normalizedPath)) {
                return@synchronized false
            }
            val baseline = filePreEditBaselines.remove(normalizedPath)
            if (baseline == null) {
                if (target.isFile && !isSymbolicLinkSafe(target)) {
                    target.delete()
                    pruneEmptyParents(target.parentFile)
                }
            } else {
                target.parentFile?.mkdirs()
                target.writeText(baseline, StandardCharsets.UTF_8)
            }
            clearDiffsForPathLocked(normalizedPath)
            refreshSelectedContentIfMatchesLocked(normalizedPath, target)
            refreshIndexLocked()
            true
        }
    }

    /**
     * Per-file keep/accept absorbed from `techjarves/Mobile-Harness` (`WorkspaceCheckpoints.kt`).
     * Promotes the current state of [relativePath] into the latest checkpoint baseline and clears
     * its pending unreviewed diff records.
     */
    fun acceptFileChange(relativePath: String, checkpointId: String? = null): Boolean {
        val target = resolveSafePath(relativePath)
        val normalizedPath = toRelativePath(target)
        return synchronized(lock) {
            var updated = false
            val checkpointDir = resolveCheckpointDirLocked(checkpointId)
            if (checkpointDir != null) {
                val filesDir = File(checkpointDir, "files")
                val snapFile = File(filesDir, normalizedPath)
                val snapCanonical = runCatching { snapFile.canonicalFile }.getOrNull()
                val filesDirCanonical = runCatching { filesDir.canonicalFile }.getOrNull()
                val snapSafe = snapCanonical != null &&
                    filesDirCanonical != null &&
                    snapCanonical.toPath().startsWith(filesDirCanonical.toPath())

                if (snapSafe) {
                    if (target.isFile && !isSymbolicLinkSafe(target)) {
                        snapFile.parentFile?.mkdirs()
                        target.copyTo(snapFile, overwrite = true)
                        updated = true
                    } else if (snapFile.isFile) {
                        snapFile.delete()
                        updated = true
                    }
                    val metaFile = File(checkpointDir, "meta.json")
                    if (metaFile.isFile) {
                        runCatching {
                            val meta = JSONObject(metaFile.readText(StandardCharsets.UTF_8))
                            meta.put("fileCount", collectRawFiles(filesDir).size)
                            metaFile.writeText(meta.toString(2), StandardCharsets.UTF_8)
                        }
                    }
                }
            }

            if (filePreEditBaselines.remove(normalizedPath) != null) {
                updated = true
            }
            val hadDiffs = _state.value.diffs.any { it.relativePath == normalizedPath }
            if (hadDiffs) {
                clearDiffsForPathLocked(normalizedPath)
                updated = true
            }
            if (target.isFile && !isSymbolicLinkSafe(target)) {
                updated = true
            }
            if (updated) {
                refreshIndexLocked()
            }
            updated
        }
    }

    /**
     * 3-tier token-budgeted Repository Map generator absorbed from `Sanuu7/AndroidHarness`
     * (`RepoMapGenerator.kt`). Extracts top-level symbols across Kotlin, Java, Python, Swift,
     * and TypeScript/JavaScript files and degrades gracefully across:
     * - Tier 1: Full symbol signatures per file
     * - Tier 2: Compact single-line symbol names per file
     * - Tier 3: Token-bounded file directory outline
     */
    fun generateRepoMap(maxTokens: Int = 1500): String = synchronized(lock) {
        val budget = maxTokens.coerceAtLeast(32)
        val entries = collectFileEntriesLocked(rootCanonical, maxDepth = 10)
        if (entries.isEmpty()) {
            return@synchronized "# Repo Map [tier=1, budget=${budget}tok]\n(workspace is empty)"
        }

        val extracted = entries.map { entry ->
            val file = File(rootCanonical, entry.relativePath)
            val symbols = if (file.isFile && !isSymbolicLinkSafe(file) &&
                file.length() <= MAX_REPOMAP_FILE_BYTES && !isLikelyBinary(file)
            ) {
                extractTopLevelSymbols(entry.relativePath, file)
            } else {
                emptyList()
            }
            entry to symbols
        }

        // Tier 1: full symbol signatures
        val tier1 = buildString {
            appendLine("# Repo Map [tier=1, budget=${budget}tok, files=${entries.size}]")
            for ((entry, symbols) in extracted) {
                appendLine(entry.relativePath)
                if (symbols.isEmpty()) {
                    appendLine("  (${entry.lineCount} lines)")
                } else {
                    for (sym in symbols.take(40)) {
                        appendLine("  - $sym")
                    }
                }
            }
        }.trimEnd()
        if (estimateTokens(tier1) <= budget) {
            return@synchronized tier1
        }

        // Tier 2: compact one-line symbol names per file
        val tier2 = buildString {
            appendLine("# Repo Map [tier=2, budget=${budget}tok, files=${entries.size}]")
            for ((entry, symbols) in extracted) {
                if (symbols.isEmpty()) {
                    appendLine("${entry.relativePath} (${entry.lineCount}L)")
                } else {
                    val compactNames = symbols.take(12).map { compactSymbolName(it) }
                    appendLine("${entry.relativePath}: ${compactNames.joinToString(", ")}")
                }
            }
        }.trimEnd()
        if (estimateTokens(tier2) <= budget) {
            return@synchronized tier2
        }

        // Tier 3: bounded file outline within maxTokens
        val out = StringBuilder()
        out.append("# Repo Map [tier=3, budget=${budget}tok, files=${entries.size}]")
        var included = 0
        for ((entry, _) in extracted) {
            val line = "\n${entry.relativePath} (${entry.lineCount}L)"
            val footerReserve = "\n... (+${entries.size - included} more files)"
            if (estimateTokens(out.toString() + line + footerReserve) > budget) {
                if (included < entries.size) {
                    out.append("\n... (+${entries.size - included} more files)")
                }
                break
            }
            out.append(line)
            included++
        }
        out.toString()
    }

    /**
     * Convenience instance delegator to [MobileWorkspaceEngine.autoCompactContext].
     */
    fun autoCompactContext(
        messages: List<String>,
        maxTokenBudget: Int,
        thresholdRatio: Double = 0.80
    ): Pair<List<String>, Boolean> = Companion.autoCompactContext(
        messages = messages,
        maxTokenBudget = maxTokenBudget,
        thresholdRatio = thresholdRatio
    )

    /**
     * Exports all regular non-symlink workspace files into a `.hansonbackup` v1 encrypted container
     * (`techjarves/Mobile-Harness` `BackupCrypto.kt` pattern):
     * - Key derivation: PBKDF2WithHmacSHA256 (120,000 iterations, random 16-byte salt, 256-bit key)
     * - Encryption: Segmented 1 MiB AES-256-GCM chunks with unique per-chunk nonces and header+index AAD
     * - Passphrase & derived key buffers are explicitly zero-wiped inside `finally`.
     */
    fun exportEncryptedBackup(
        passphraseChars: CharArray,
        destFile: File,
        wipePassphrase: Boolean = true
    ): EncryptedBackupResult {
        if (passphraseChars.isEmpty()) {
            throw IllegalArgumentException("Backup passphrase must not be empty.")
        }
        val localPass = passphraseChars.copyOf()
        var pbeSpec: PBEKeySpec? = null
        var keyBytes: ByteArray? = null
        try {
            val archiveBytesAndCount = synchronized(lock) { buildWorkspaceArchiveBytesLocked() }
            val archiveBytes = archiveBytesAndCount.first
            val fileCount = archiveBytesAndCount.second

            val rng = SecureRandom()
            val salt = ByteArray(BACKUP_SALT_BYTES).also { rng.nextBytes(it) }
            val baseNonce = ByteArray(BACKUP_NONCE_BYTES).also { rng.nextBytes(it) }

            pbeSpec = PBEKeySpec(localPass, salt, BACKUP_PBKDF2_ITERATIONS, 256)
            val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            keyBytes = skf.generateSecret(pbeSpec).encoded
            val secretKey = SecretKeySpec(keyBytes, "AES")

            val headerBytes = buildBackupHeaderBytes(
                version = BACKUP_CONTAINER_VERSION,
                iterations = BACKUP_PBKDF2_ITERATIONS,
                chunkSize = BACKUP_CHUNK_SIZE_BYTES,
                salt = salt,
                baseNonce = baseNonce
            )

            destFile.parentFile?.mkdirs()
            val tmpFile = File(destFile.parentFile, "${destFile.name}.tmp.${System.nanoTime()}")
            var chunkCount = 0
            try {
                DataOutputStream(BufferedOutputStream(FileOutputStream(tmpFile))).use { out ->
                    out.write(headerBytes)
                    val totalLen = archiveBytes.size
                    var offset = 0
                    while (true) {
                        val remaining = totalLen - offset
                        val sliceLen = minOf(BACKUP_CHUNK_SIZE_BYTES, remaining)
                        val isFinal = (offset + sliceLen) >= totalLen
                        val iv = deriveChunkNonce(baseNonce, chunkCount)
                        val aad = buildChunkAad(headerBytes, chunkCount, isFinal)

                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(BACKUP_GCM_TAG_BITS, iv))
                        cipher.updateAAD(aad)
                        val ciphertext = cipher.doFinal(archiveBytes, offset, sliceLen)

                        out.writeInt(ciphertext.size)
                        out.writeByte(if (isFinal) 1 else 0)
                        out.write(ciphertext)

                        chunkCount++
                        offset += sliceLen
                        if (isFinal) break
                    }
                    out.flush()
                }
                if (destFile.exists() && !destFile.delete()) {
                    // Fallback overwrite if platform prevents delete before rename
                    tmpFile.copyTo(destFile, overwrite = true)
                    tmpFile.delete()
                } else if (!tmpFile.renameTo(destFile)) {
                    tmpFile.copyTo(destFile, overwrite = true)
                    tmpFile.delete()
                }
            } catch (t: Throwable) {
                tmpFile.delete()
                throw t
            }

            return EncryptedBackupResult(
                fileCount = fileCount,
                totalPlaintextBytes = archiveBytes.size.toLong(),
                chunkCount = chunkCount,
                containerVersion = BACKUP_CONTAINER_VERSION
            )
        } finally {
            pbeSpec?.clearPassword()
            Arrays.fill(localPass, '\u0000')
            keyBytes?.let { Arrays.fill(it, 0.toByte()) }
            if (wipePassphrase) {
                Arrays.fill(passphraseChars, '\u0000')
            }
        }
    }

    /**
     * Decrypts, authenticates, and restores a `.hansonbackup` v1 container into this workspace.
     * Validates every chunk's AES-256-GCM tag and container framing BEFORE mutating any file;
     * wrong passphrases or tampered containers fail closed with [SecurityException].
     */
    fun importEncryptedBackup(
        passphraseChars: CharArray,
        srcFile: File,
        wipePassphrase: Boolean = true
    ): EncryptedBackupResult {
        if (passphraseChars.isEmpty()) {
            throw IllegalArgumentException("Backup passphrase must not be empty.")
        }
        if (!srcFile.isFile) {
            throw FileNotFoundException("Encrypted backup file not found: ${srcFile.path}")
        }

        val localPass = passphraseChars.copyOf()
        var pbeSpec: PBEKeySpec? = null
        var keyBytes: ByteArray? = null
        try {
            val decryptedStream = ByteArrayOutputStream()
            var chunkCount = 0
            val containerVersion: Int

            DataInputStream(BufferedInputStream(FileInputStream(srcFile))).use { input ->
                val magic = ByteArray(BACKUP_MAGIC.size)
                input.readFully(magic)
                if (!magic.contentEquals(BACKUP_MAGIC)) {
                    throw SecurityException("Invalid backup container header (expected .hansonbackup v1).")
                }
                containerVersion = input.readUnsignedShort()
                if (containerVersion != BACKUP_CONTAINER_VERSION) {
                    throw SecurityException("Unsupported .hansonbackup container version: $containerVersion")
                }
                val iterations = input.readInt()
                val chunkSize = input.readInt()
                if (iterations !in 10_000..1_000_000) {
                    throw SecurityException("Invalid PBKDF2 iteration count in backup header: $iterations")
                }
                if (chunkSize !in 4_096..(4 * 1024 * 1024)) {
                    throw SecurityException("Invalid chunk size in backup header: $chunkSize")
                }
                val salt = ByteArray(BACKUP_SALT_BYTES).also { input.readFully(it) }
                val baseNonce = ByteArray(BACKUP_NONCE_BYTES).also { input.readFully(it) }

                val headerBytes = buildBackupHeaderBytes(
                    version = containerVersion,
                    iterations = iterations,
                    chunkSize = chunkSize,
                    salt = salt,
                    baseNonce = baseNonce
                )

                pbeSpec = PBEKeySpec(localPass, salt, iterations, 256)
                val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                keyBytes = skf.generateSecret(pbeSpec).encoded
                val secretKey = SecretKeySpec(keyBytes, "AES")

                val maxCipherChunkBytes = chunkSize + 32
                var totalPlain = 0L
                var sawFinal = false

                while (!sawFinal) {
                    val cipherLen = input.readInt()
                    if (cipherLen < 16 || cipherLen > maxCipherChunkBytes) {
                        throw SecurityException("Corrupted backup chunk length: $cipherLen")
                    }
                    val finalFlagByte = input.readUnsignedByte()
                    if (finalFlagByte != 0 && finalFlagByte != 1) {
                        throw SecurityException("Corrupted backup chunk final flag: $finalFlagByte")
                    }
                    val isFinal = finalFlagByte == 1
                    val ciphertext = ByteArray(cipherLen).also { input.readFully(it) }

                    val iv = deriveChunkNonce(baseNonce, chunkCount)
                    val aad = buildChunkAad(headerBytes, chunkCount, isFinal)

                    val plainChunk = try {
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(BACKUP_GCM_TAG_BITS, iv))
                        cipher.updateAAD(aad)
                        cipher.doFinal(ciphertext)
                    } catch (e: AEADBadTagException) {
                        throw SecurityException(
                            "Encrypted backup authentication failed: wrong passphrase or tampered container.",
                            e
                        )
                    } catch (e: Exception) {
                        throw SecurityException("Encrypted backup decryption failed: ${e.message}", e)
                    }

                    totalPlain += plainChunk.size
                    if (totalPlain > BACKUP_MAX_ARCHIVE_BYTES) {
                        throw SecurityException("Decrypted backup exceeds maximum allowed archive size.")
                    }
                    decryptedStream.write(plainChunk)
                    chunkCount++
                    sawFinal = isFinal
                }

                // Refuse trailing unauthenticated garbage after the final chunk
                if (input.read() != -1) {
                    throw SecurityException("Unexpected trailing bytes after final authenticated backup chunk.")
                }
            }

            val archiveBytes = decryptedStream.toByteArray()
            val restoredFiles = parseAndValidateArchiveEntries(archiveBytes)

            synchronized(lock) {
                for ((relPath, contentBytes) in restoredFiles) {
                    val target = resolveSafePath(relPath)
                    if (isSymbolicLinkSafe(target)) {
                        throw SecurityException("Refused restoring over symbolic link: $relPath")
                    }
                    target.parentFile?.mkdirs()
                    target.writeBytes(contentBytes)
                }
                filePreEditBaselines.clear()
                refreshIndexLocked()
            }

            return EncryptedBackupResult(
                fileCount = restoredFiles.size,
                totalPlaintextBytes = archiveBytes.size.toLong(),
                chunkCount = chunkCount,
                containerVersion = containerVersion
            )
        } catch (se: SecurityException) {
            throw se
        } catch (eof: java.io.EOFException) {
            throw SecurityException("Truncated or corrupted encrypted backup container.", eof)
        } finally {
            pbeSpec?.clearPassword()
            Arrays.fill(localPass, '\u0000')
            keyBytes?.let { Arrays.fill(it, 0.toByte()) }
            if (wipePassphrase) {
                Arrays.fill(passphraseChars, '\u0000')
            }
        }
    }

    fun restoreCheckpoint(checkpointId: String): Boolean = synchronized(lock) {
        if (!CHECKPOINT_ID_REGEX.matches(checkpointId)) {
            return@synchronized false
        }
        val filesSnapshotDir = File(rootCanonical, "$CHECKPOINTS_DIR/$checkpointId/files")
        if (!filesSnapshotDir.exists() || !filesSnapshotDir.isDirectory) {
            return@synchronized false
        }

        val currentEntries = collectFileEntriesLocked(rootCanonical, maxDepth = 12)
            .associateBy { it.relativePath }
        val snapshotFiles = collectRawFiles(filesSnapshotDir)
            .associateBy { filesSnapshotDir.toPath().relativize(it.toPath()).toString().replace('\\', '/') }

        val allPaths = (currentEntries.keys + snapshotFiles.keys).sorted()
        val recordedDiffs = mutableListOf<WorkspaceDiffRecord>()
        val now = System.currentTimeMillis()

        for (relPath in allPaths) {
            val liveFile = resolveSafePath(relPath)
            val snapFile = snapshotFiles[relPath]
            if (isSymbolicLinkSafe(liveFile) || (snapFile != null && isSymbolicLinkSafe(snapFile))) {
                continue
            }
            val oldText = if (liveFile.isFile) liveFile.readText(StandardCharsets.UTF_8) else ""

            if (snapFile != null) {
                val newText = snapFile.readText(StandardCharsets.UTF_8)
                val bytesDiffer = !liveFile.isFile || !liveFile.readBytes().contentEquals(snapFile.readBytes())
                if (bytesDiffer) {
                    liveFile.parentFile?.mkdirs()
                    snapFile.copyTo(liveFile, overwrite = true)
                    val diff = UnifiedDiffComputer.computeUnifiedDiff(relPath, oldText, newText)
                    if (diff.unifiedDiff.isNotEmpty()) {
                        recordedDiffs.add(
                            WorkspaceDiffRecord(
                                relativePath = relPath,
                                unifiedDiff = diff.unifiedDiff,
                                addedLines = diff.addedLines,
                                removedLines = diff.removedLines,
                                timestampMs = now
                            )
                        )
                    }
                }
            } else if (liveFile.isFile) {
                liveFile.delete()
                pruneEmptyParents(liveFile.parentFile)
                val diff = UnifiedDiffComputer.computeUnifiedDiff(relPath, oldText, "")
                if (diff.unifiedDiff.isNotEmpty()) {
                    recordedDiffs.add(
                        WorkspaceDiffRecord(
                            relativePath = relPath,
                            unifiedDiff = diff.unifiedDiff,
                            addedLines = diff.addedLines,
                            removedLines = diff.removedLines,
                            timestampMs = now
                        )
                    )
                }
            }
        }

        if (recordedDiffs.isNotEmpty()) {
            _state.update { current ->
                current.copy(diffs = (current.diffs + recordedDiffs).takeLast(MAX_DIFF_HISTORY))
            }
        }
        refreshIndexLocked()

        val selected = _state.value.selectedFilePath
        if (selected != null) {
            val selectedFile = File(rootCanonical, selected)
            val newContent = if (selectedFile.isFile) selectedFile.readText(StandardCharsets.UTF_8) else ""
            _state.update { it.copy(selectedFileContent = newContent) }
        }
        true
    }

    fun updatePlanArtifact(markdown: String, persistToFile: Boolean = false) {
        synchronized(lock) {
            _state.update { it.copy(planArtifactMarkdown = markdown) }
            if (persistToFile) {
                writeFileLocked("PLAN.md", markdown)
            }
        }
    }

    fun updateWalkthroughArtifact(markdown: String, persistToFile: Boolean = false) {
        synchronized(lock) {
            _state.update { it.copy(walkthroughArtifactMarkdown = markdown) }
            if (persistToFile) {
                writeFileLocked("WALKTHROUGH.md", markdown)
            }
        }
    }

    private fun writeFileLocked(normalizedPath: String, content: String): WorkspaceDiffRecord {
        val target = resolveSafePath(normalizedPath)
        val existedBefore = target.isFile && !isSymbolicLinkSafe(target)
        val oldContent = if (existedBefore) target.readText(StandardCharsets.UTF_8) else ""
        if (!filePreEditBaselines.containsKey(normalizedPath)) {
            filePreEditBaselines[normalizedPath] = if (existedBefore) oldContent else null
        }
        target.parentFile?.mkdirs()
        target.writeText(content, StandardCharsets.UTF_8)

        val diff = UnifiedDiffComputer.computeUnifiedDiff(normalizedPath, oldContent, content)
        val record = WorkspaceDiffRecord(
            relativePath = normalizedPath,
            unifiedDiff = diff.unifiedDiff,
            addedLines = diff.addedLines,
            removedLines = diff.removedLines,
            timestampMs = System.currentTimeMillis()
        )
        _state.update { current ->
            val updatedDiffs = if (diff.unifiedDiff.isNotEmpty() || oldContent != content) {
                (current.diffs + record).takeLast(MAX_DIFF_HISTORY)
            } else {
                current.diffs
            }
            val updatedSelectedContent = if (current.selectedFilePath == normalizedPath) {
                content
            } else {
                current.selectedFileContent
            }
            current.copy(
                diffs = updatedDiffs,
                selectedFileContent = updatedSelectedContent
            )
        }
        refreshIndexLocked()
        return record
    }

    private fun selectFileInternal(normalizedPath: String): String {
        val target = resolveSafePath(normalizedPath)
        if (!target.exists() || !target.isFile) {
            throw FileNotFoundException("File not found: $normalizedPath")
        }
        val content = target.readText(StandardCharsets.UTF_8)
        _state.update {
            it.copy(
                selectedFilePath = normalizedPath,
                selectedFileContent = content
            )
        }
        return content
    }

    private fun refreshIndexLocked() {
        val entries = collectFileEntriesLocked(rootCanonical, maxDepth = 8)
        val hasAgents = entries.any { it.relativePath == "AGENTS.md" }
        val checkpoints = loadCheckpointsLocked()
        _state.update { current ->
            current.copy(
                hasAgentsMd = hasAgents,
                files = entries,
                checkpoints = checkpoints
            )
        }
    }

    private fun loadCheckpointsLocked(): List<WorkspaceCheckpoint> {
        val cpBase = File(rootCanonical, CHECKPOINTS_DIR)
        if (!cpBase.exists() || !cpBase.isDirectory) return emptyList()
        val list = mutableListOf<WorkspaceCheckpoint>()
        val children = cpBase.listFiles() ?: return emptyList()
        for (dir in children) {
            if (!dir.isDirectory || !CHECKPOINT_ID_REGEX.matches(dir.name)) continue
            val metaFile = File(dir, "meta.json")
            val filesDir = File(dir, "files")
            if (metaFile.isFile) {
                val parsed = runCatching { JSONObject(metaFile.readText(StandardCharsets.UTF_8)) }.getOrNull()
                if (parsed != null) {
                    list.add(
                        WorkspaceCheckpoint(
                            id = parsed.optString("id", dir.name),
                            label = parsed.optString("label", dir.name),
                            createdAtMs = parsed.optLong("createdAtMs", dir.lastModified()),
                            fileCount = parsed.optInt("fileCount", 0)
                        )
                    )
                    continue
                }
            }
            val count = if (filesDir.isDirectory) collectRawFiles(filesDir).size else 0
            list.add(
                WorkspaceCheckpoint(
                    id = dir.name,
                    label = dir.name,
                    createdAtMs = dir.lastModified(),
                    fileCount = count
                )
            )
        }
        return list.sortedWith(
            compareByDescending<WorkspaceCheckpoint> { it.createdAtMs }.thenByDescending { it.id }
        )
    }

    private fun collectFileEntriesLocked(baseDir: File, maxDepth: Int): List<WorkspaceFileEntry> {
        val results = mutableListOf<WorkspaceFileEntry>()
        fun walk(dir: File, depth: Int) {
            if (isSymbolicLinkSafe(dir)) return
            val children = dir.listFiles()?.sortedBy { it.name.lowercase(Locale.ROOT) } ?: return
            for (child in children) {
                if (isSymbolicLinkSafe(child)) continue
                if (child.isDirectory) {
                    if (child.name in IGNORED_DIRS) continue
                    if (depth < maxDepth) {
                        walk(child, depth + 1)
                    }
                } else if (child.isFile) {
                    val rel = toRelativePath(child)
                    val lines = if (child.length() <= MAX_SEARCH_FILE_BYTES && !isLikelyBinary(child)) {
                        countFileLines(child)
                    } else {
                        0
                    }
                    results.add(
                        WorkspaceFileEntry(
                            relativePath = rel,
                            sizeBytes = child.length(),
                            lastModifiedMs = child.lastModified(),
                            isDirectory = false,
                            lineCount = lines
                        )
                    )
                }
            }
        }
        walk(baseDir, 1)
        return results.sortedBy { it.relativePath }
    }

    private fun collectRawFiles(baseDir: File): List<File> {
        val files = mutableListOf<File>()
        fun walk(dir: File) {
            if (isSymbolicLinkSafe(dir)) return
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (isSymbolicLinkSafe(child)) continue
                if (child.isDirectory) {
                    walk(child)
                } else if (child.isFile) {
                    files.add(child)
                }
            }
        }
        walk(baseDir)
        return files
    }

    private fun executeCommandInternal(raw: String): ToolCommandResult {
        if (raw.isEmpty()) {
            return ToolCommandResult(raw, succeeded = false, exitCode = 2, stdout = "", stderr = "Empty command.")
        }
        if (SHELL_METACHAR_REGEX.containsMatchIn(raw)) {
            return ToolCommandResult(
                command = raw,
                succeeded = false,
                exitCode = 126,
                stdout = "",
                stderr = "Rejected: dangerous shell metacharacters or escape sequences are forbidden."
            )
        }

        val tokens = tokenizeCommand(raw)
            ?: return ToolCommandResult(
                command = raw,
                succeeded = false,
                exitCode = 2,
                stdout = "",
                stderr = "Syntax error: unclosed quote in command."
            )
        if (tokens.isEmpty()) {
            return ToolCommandResult(raw, succeeded = false, exitCode = 2, stdout = "", stderr = "Empty command.")
        }

        val cmd = tokens.first().lowercase(Locale.ROOT)
        val args = tokens.drop(1)
        if (cmd !in ALLOWED_COMMANDS) {
            return ToolCommandResult(
                command = raw,
                succeeded = false,
                exitCode = 127,
                stdout = "",
                stderr = "Command '$cmd' is not allowlisted. Allowed: ${ALLOWED_COMMANDS.sorted().joinToString(", ")}"
            )
        }

        return try {
            when (cmd) {
                "pwd" -> ToolCommandResult(raw, true, 0, rootCanonical.canonicalPath)
                "echo" -> ToolCommandResult(raw, true, 0, args.joinToString(" "))
                "status" -> handleStatusCommand(raw)
                "ls" -> handleLsCommand(raw, args)
                "tree" -> handleTreeCommand(raw, args)
                "cat" -> handleCatCommand(raw, args)
                "wc" -> handleWcCommand(raw, args)
                "grep" -> handleGrepCommand(raw, args)
                "diff" -> handleDiffCommand(raw, args)
                else -> ToolCommandResult(raw, false, 127, "", "Unsupported command: $cmd")
            }
        } catch (se: SecurityException) {
            ToolCommandResult(raw, false, 126, "", se.message ?: "Security violation")
        } catch (t: Throwable) {
            ToolCommandResult(raw, false, 1, "", t.message ?: "Command failed")
        }
    }

    private fun handleStatusCommand(raw: String): ToolCommandResult {
        val current = _state.value
        val totalBytes = current.files.sumOf { it.sizeBytes }
        val totalLines = current.files.sumOf { it.lineCount }
        val out = buildString {
            appendLine("workspace: ${current.workspaceName}")
            appendLine("files: ${current.files.size} ($totalLines lines, $totalBytes bytes)")
            appendLine("AGENTS.md: ${if (current.hasAgentsMd) "present" else "missing"}")
            appendLine("diffs: ${current.diffs.size}")
            append("checkpoints: ${current.checkpoints.size}")
        }
        return ToolCommandResult(raw, true, 0, out)
    }

    private fun handleLsCommand(raw: String, args: List<String>): ToolCommandResult {
        val longFormat = args.any { it.startsWith("-") && it.contains('l') }
        val pathArgs = args.filterNot { it.startsWith("-") }
        val subDir = pathArgs.firstOrNull().orEmpty()
        val entries = listFiles(subDir, maxDepth = 4)
        val out = if (entries.isEmpty()) {
            "(empty)"
        } else if (longFormat) {
            entries.joinToString("\n") { "${it.sizeBytes}B\t${it.lineCount}L\t${it.relativePath}" }
        } else {
            entries.joinToString("\n") { it.relativePath }
        }
        return ToolCommandResult(raw, true, 0, out)
    }

    private fun handleTreeCommand(raw: String, args: List<String>): ToolCommandResult {
        val pathArg = args.firstOrNull { !it.startsWith("-") }.orEmpty()
        val base = if (pathArg.isBlank() || pathArg == ".") rootCanonical else resolveSafePath(pathArg)
        if (!base.exists()) {
            return ToolCommandResult(raw, false, 1, "", "Path not found: $pathArg")
        }
        val sb = StringBuilder()
        sb.append(if (base == rootCanonical) "." else toRelativePath(base))
        fun render(dir: File, prefix: String) {
            if (isSymbolicLinkSafe(dir)) return
            val children = dir.listFiles()
                ?.filterNot { isSymbolicLinkSafe(it) || (it.isDirectory && it.name in IGNORED_DIRS) }
                ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })
                ?: return
            children.forEachIndexed { idx, child ->
                val isLast = idx == children.lastIndex
                val connector = if (isLast) "└── " else "├── "
                sb.append('\n').append(prefix).append(connector).append(child.name)
                if (child.isDirectory) {
                    render(child, prefix + if (isLast) "    " else "│   ")
                }
            }
        }
        if (base.isDirectory) {
            render(base, "")
        }
        return ToolCommandResult(raw, true, 0, sb.toString())
    }

    private fun handleCatCommand(raw: String, args: List<String>): ToolCommandResult {
        val paths = args.filterNot { it.startsWith("-") }
        if (paths.isEmpty()) {
            return ToolCommandResult(raw, false, 2, "", "Usage: cat <relative-path> [...]")
        }
        val outputs = mutableListOf<String>()
        for (path in paths) {
            val target = resolveSafePath(path)
            if (!target.exists() || !target.isFile) {
                return ToolCommandResult(raw, false, 1, "", "cat: $path: No such file")
            }
            outputs.add(target.readText(StandardCharsets.UTF_8))
        }
        return ToolCommandResult(raw, true, 0, outputs.joinToString("\n"))
    }

    private fun handleWcCommand(raw: String, args: List<String>): ToolCommandResult {
        val linesOnly = args.any { it.startsWith("-") && it.contains('l') }
        val pathArgs = args.filterNot { it.startsWith("-") }
        val targets = if (pathArgs.isEmpty()) {
            listFiles().map { it.relativePath }
        } else {
            pathArgs
        }
        if (targets.isEmpty()) {
            return ToolCommandResult(raw, true, 0, "0 0 0 (no files)")
        }
        val out = mutableListOf<String>()
        for (p in targets) {
            val f = resolveSafePath(p)
            if (!f.isFile) {
                return ToolCommandResult(raw, false, 1, "", "wc: $p: No such file")
            }
            val text = f.readText(StandardCharsets.UTF_8)
            val lines = UnifiedDiffComputer.splitLines(text).size
            val words = if (text.isBlank()) 0 else text.trim().split(Regex("\\s+")).size
            val bytes = f.length()
            val rel = toRelativePath(f)
            if (linesOnly) {
                out.add("$lines $rel")
            } else {
                out.add("$lines $words $bytes $rel")
            }
        }
        return ToolCommandResult(raw, true, 0, out.joinToString("\n"))
    }

    private fun handleGrepCommand(raw: String, args: List<String>): ToolCommandResult {
        var ignoreCase = true
        val positional = mutableListOf<String>()
        for (arg in args) {
            if (arg.startsWith("-")) {
                if (arg.contains('I')) ignoreCase = false
            } else {
                positional.add(arg)
            }
        }
        if (positional.isEmpty()) {
            return ToolCommandResult(raw, false, 2, "", "Usage: grep <pattern> [pathPrefix]")
        }
        val query = positional[0]
        val prefix = positional.getOrNull(1).orEmpty()
        val matches = searchFiles(query, prefix, ignoreCase = ignoreCase)
        return ToolCommandResult(raw, true, 0, matches.joinToString("\n"))
    }

    private fun handleDiffCommand(raw: String, args: List<String>): ToolCommandResult {
        val pathArgs = args.filterNot { it.startsWith("-") }
        if (pathArgs.isEmpty()) {
            val diffs = _state.value.diffs
            if (diffs.isEmpty()) {
                return ToolCommandResult(raw, true, 0, "(no recorded diffs)")
            }
            val out = diffs.joinToString("\n\n") { it.unifiedDiff.ifBlank { "${it.relativePath}: +${it.addedLines}/-${it.removedLines}" } }
            return ToolCommandResult(raw, true, 0, out)
        }
        if (pathArgs.size != 2) {
            return ToolCommandResult(raw, false, 2, "", "Usage: diff [<fileA> <fileB>]")
        }
        val fileA = resolveSafePath(pathArgs[0])
        val fileB = resolveSafePath(pathArgs[1])
        if (!fileA.isFile || !fileB.isFile) {
            return ToolCommandResult(raw, false, 1, "", "diff: both arguments must be existing files")
        }
        val result = UnifiedDiffComputer.computeUnifiedDiff(
            relativePath = toRelativePath(fileB),
            oldText = fileA.readText(StandardCharsets.UTF_8),
            newText = fileB.readText(StandardCharsets.UTF_8)
        )
        return ToolCommandResult(raw, true, 0, result.unifiedDiff.ifBlank { "(files are identical)" })
    }

    private fun tokenizeCommand(input: String): List<String>? {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quoteChar: Char? = null
        var hasToken = false

        for (ch in input) {
            when {
                quoteChar != null -> {
                    if (ch == quoteChar) {
                        quoteChar = null
                    } else {
                        current.append(ch)
                    }
                }
                ch == '\'' || ch == '"' -> {
                    quoteChar = ch
                    hasToken = true
                }
                ch.isWhitespace() -> {
                    if (hasToken) {
                        tokens.add(current.toString())
                        current.clear()
                        hasToken = false
                    }
                }
                else -> {
                    current.append(ch)
                    hasToken = true
                }
            }
        }
        if (quoteChar != null) return null
        if (hasToken) tokens.add(current.toString())
        return tokens
    }

    private fun toRelativePath(canonicalFile: File): String =
        rootCanonical.toPath().relativize(canonicalFile.canonicalFile.toPath())
            .toString()
            .replace('\\', '/')

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var startIndex = 0
        while (true) {
            val found = haystack.indexOf(needle, startIndex)
            if (found < 0) break
            count++
            startIndex = found + needle.length
        }
        return count
    }

    private fun countFileLines(file: File): Int {
        val text = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrDefault("")
        return UnifiedDiffComputer.splitLines(text).size
    }

    private fun isLikelyBinary(file: File): Boolean {
        val buf = ByteArray(512)
        val read = runCatching {
            file.inputStream().use { it.read(buf) }
        }.getOrDefault(0)
        for (i in 0 until read) {
            if (buf[i].toInt() == 0) return true
        }
        return false
    }

    private fun pruneEmptyParents(dir: File?) {
        var cursor = dir
        while (cursor != null && cursor.canonicalFile != rootCanonical) {
            val children = cursor.listFiles()
            if (children != null && children.isEmpty()) {
                cursor.delete()
                cursor = cursor.parentFile
            } else {
                break
            }
        }
    }

    private fun isSymbolicLinkSafe(file: File): Boolean =
        runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    private fun resolveCheckpointDirLocked(checkpointId: String?): File? {
        val id = if (checkpointId != null) {
            if (!CHECKPOINT_ID_REGEX.matches(checkpointId)) return null
            checkpointId
        } else {
            loadCheckpointsLocked().firstOrNull()?.id ?: return null
        }
        val dir = File(rootCanonical, "$CHECKPOINTS_DIR/$id")
        return if (dir.isDirectory && File(dir, "files").isDirectory) dir else null
    }

    private fun clearDiffsForPathLocked(normalizedPath: String) {
        _state.update { current ->
            current.copy(diffs = current.diffs.filterNot { it.relativePath == normalizedPath })
        }
    }

    private fun refreshSelectedContentIfMatchesLocked(normalizedPath: String, file: File) {
        if (_state.value.selectedFilePath == normalizedPath) {
            val content = if (file.isFile && !isSymbolicLinkSafe(file)) {
                file.readText(StandardCharsets.UTF_8)
            } else {
                ""
            }
            _state.update { it.copy(selectedFileContent = content) }
        }
    }

    private fun extractTopLevelSymbols(relativePath: String, file: File): List<String> {
        val ext = relativePath.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val lines = runCatching { file.readLines(StandardCharsets.UTF_8) }.getOrDefault(emptyList())
        if (lines.isEmpty()) return emptyList()
        val symbols = mutableListOf<String>()

        for (rawLine in lines) {
            if (symbols.size >= 60) break
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("*")) {
                continue
            }
            when (ext) {
                "kt", "kts" -> {
                    val m = KT_SYMBOL_REGEX.find(rawLine)
                    if (m != null) {
                        val kind = m.groupValues[1]
                        val sig = m.groupValues[2].trim().trimEnd('{', '=').trim()
                        if (sig.isNotEmpty()) symbols.add("$kind $sig")
                    }
                }
                "java" -> {
                    val tm = JAVA_TYPE_REGEX.find(rawLine)
                    if (tm != null) {
                        symbols.add("${tm.groupValues[1]} ${tm.groupValues[2]}")
                    } else {
                        val mm = JAVA_METHOD_REGEX.find(rawLine)
                        if (mm != null) {
                            val sig = mm.groupValues[1].trim()
                            if (!sig.startsWith("if") && !sig.startsWith("for") &&
                                !sig.startsWith("while") && !sig.startsWith("switch")
                            ) {
                                symbols.add("fun $sig")
                            }
                        }
                    }
                }
                "py" -> {
                    val m = PY_SYMBOL_REGEX.find(rawLine)
                    if (m != null) {
                        symbols.add("${m.groupValues[1]} ${m.groupValues[2].trim()}")
                    }
                }
                "swift" -> {
                    val m = SWIFT_SYMBOL_REGEX.find(rawLine)
                    if (m != null) {
                        symbols.add("${m.groupValues[1]} ${m.groupValues[2].trim()}")
                    }
                }
                "ts", "tsx", "js", "jsx", "mjs" -> {
                    val m = TS_JS_SYMBOL_REGEX.find(rawLine)
                    if (m != null) {
                        symbols.add("${m.groupValues[1]} ${m.groupValues[2].trim()}")
                    } else {
                        val am = TS_JS_ARROW_REGEX.find(rawLine)
                        if (am != null) {
                            symbols.add("function ${am.groupValues[1]}")
                        }
                    }
                }
            }
        }
        return symbols.distinct()
    }

    private fun compactSymbolName(symbolSignature: String): String {
        val beforeParen = symbolSignature.substringBefore('(').substringBefore(':').trim()
        return beforeParen.take(48)
    }

    private fun buildWorkspaceArchiveBytesLocked(): Pair<ByteArray, Int> {
        val entries = collectFileEntriesLocked(rootCanonical, maxDepth = 16)
        val regularFiles = entries.mapNotNull { entry ->
            val f = File(rootCanonical, entry.relativePath)
            if (f.isFile && !isSymbolicLinkSafe(f)) entry.relativePath to f else null
        }
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { dos ->
            dos.writeInt(regularFiles.size)
            for ((relPath, file) in regularFiles) {
                val pathBytes = relPath.toByteArray(StandardCharsets.UTF_8)
                val fileBytes = file.readBytes()
                dos.writeInt(pathBytes.size)
                dos.write(pathBytes)
                dos.writeLong(fileBytes.size.toLong())
                dos.write(fileBytes)
            }
            dos.flush()
        }
        return baos.toByteArray() to regularFiles.size
    }

    private fun parseAndValidateArchiveEntries(archiveBytes: ByteArray): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        DataInputStream(ByteArrayInputStream(archiveBytes)).use { dis ->
            val fileCount = dis.readInt()
            if (fileCount < 0 || fileCount > 50_000) {
                throw SecurityException("Corrupted archive file count: $fileCount")
            }
            for (i in 0 until fileCount) {
                val pathLen = dis.readInt()
                if (pathLen <= 0 || pathLen > 4096) {
                    throw SecurityException("Corrupted archive entry path length: $pathLen")
                }
                val pathBytes = ByteArray(pathLen).also { dis.readFully(it) }
                val relPath = String(pathBytes, StandardCharsets.UTF_8)
                val firstSegment = relPath.replace('\\', '/').substringBefore('/')
                if (firstSegment in IGNORED_DIRS) {
                    throw SecurityException("Refused restoring forbidden workspace directory entry: $relPath")
                }
                // Validate sandbox containment strictly before returning
                resolveSafePath(relPath)

                val contentLen = dis.readLong()
                if (contentLen < 0L || contentLen > BACKUP_MAX_ARCHIVE_BYTES) {
                    throw SecurityException("Corrupted archive file size for $relPath: $contentLen")
                }
                val contentBytes = ByteArray(contentLen.toInt()).also { dis.readFully(it) }
                out.add(relPath to contentBytes)
            }
        }
        return out
    }

    private fun buildBackupHeaderBytes(
        version: Int,
        iterations: Int,
        chunkSize: Int,
        salt: ByteArray,
        baseNonce: ByteArray
    ): ByteArray {
        val buf = ByteBuffer.allocate(BACKUP_MAGIC.size + 2 + 4 + 4 + salt.size + baseNonce.size)
        buf.put(BACKUP_MAGIC)
        buf.putShort(version.toShort())
        buf.putInt(iterations)
        buf.putInt(chunkSize)
        buf.put(salt)
        buf.put(baseNonce)
        return buf.array()
    }

    private fun deriveChunkNonce(baseNonce: ByteArray, chunkIndex: Int): ByteArray {
        val iv = baseNonce.copyOf()
        val idxBytes = ByteBuffer.allocate(4).putInt(chunkIndex).array()
        val start = iv.size - 4
        for (i in 0 until 4) {
            iv[start + i] = (iv[start + i].toInt() xor idxBytes[i].toInt()).toByte()
        }
        return iv
    }

    private fun buildChunkAad(headerBytes: ByteArray, chunkIndex: Int, isFinal: Boolean): ByteArray {
        val buf = ByteBuffer.allocate(headerBytes.size + 5)
        buf.put(headerBytes)
        buf.putInt(chunkIndex)
        buf.put(if (isFinal) 1.toByte() else 0.toByte())
        return buf.array()
    }
}
