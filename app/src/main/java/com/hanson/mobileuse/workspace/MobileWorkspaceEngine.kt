package com.hanson.mobileuse.workspace

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

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
        private const val MAX_EXEC_OUTPUT_CHARS = 4_000

        private val IGNORED_DIRS = setOf(CHECKPOINTS_DIR, ".git", ".gradle", "build")
        private val SHELL_METACHAR_REGEX = Regex("[;&|`$()><\\n\\r\\u0000\\\\]")
        private val CHECKPOINT_ID_REGEX = Regex("^[a-zA-Z0-9_-]+$")
        private val ALLOWED_COMMANDS = setOf(
            "ls", "cat", "wc", "grep", "tree", "pwd", "diff", "echo", "status"
        )
    }

    val workspaceRoot: File
    private val rootCanonical: File
    private val lock = Any()
    private val checkpointSequence = AtomicInteger(0)
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
        for (entry in currentEntries) {
            val src = File(rootCanonical, entry.relativePath)
            val dst = File(filesSnapshotDir, entry.relativePath)
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
        }

        val checkpoint = WorkspaceCheckpoint(
            id = id,
            label = cleanLabel,
            createdAtMs = now,
            fileCount = currentEntries.size
        )
        val metaJson = JSONObject().apply {
            put("id", checkpoint.id)
            put("label", checkpoint.label)
            put("createdAtMs", checkpoint.createdAtMs)
            put("fileCount", checkpoint.fileCount)
        }
        File(cpRoot, "meta.json").writeText(metaJson.toString(2), StandardCharsets.UTF_8)

        refreshIndexLocked()
        checkpoint
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
        val oldContent = if (target.isFile) target.readText(StandardCharsets.UTF_8) else ""
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
            val children = dir.listFiles()?.sortedBy { it.name.lowercase(Locale.ROOT) } ?: return
            for (child in children) {
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
            val children = dir.listFiles() ?: return
            for (child in children) {
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
            val children = dir.listFiles()
                ?.filterNot { it.isDirectory && it.name in IGNORED_DIRS }
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
}
