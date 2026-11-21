package com.hanson.mobileuse.workspace

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class MobileWorkspaceAndSessionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createEngineWithSibling(): Pair<MobileWorkspaceEngine, File> {
        val container = tempFolder.newFolder("sandbox_container")
        val workspaceDir = File(container, "workspace")
        val siblingDir = File(container, "workspace2")
        siblingDir.mkdirs()
        File(siblingDir, "secret.txt").writeText("top-secret-outside")
        File(container, "outside.txt").writeText("parent-secret")
        return MobileWorkspaceEngine(workspaceDir) to siblingDir
    }

    // =========================================================================
    // 1. Path Containment & SecurityException Tests
    // =========================================================================

    @Test
    fun resolveSafePath_rejectsBlankEmptyAndNullBytePaths() {
        val (engine, _) = createEngineWithSibling()

        listOf("", "   ", "\t", "bad\u0000file.txt").forEach { badPath ->
            try {
                engine.resolveSafePath(badPath)
                fail("Expected SecurityException for path: '$badPath'")
            } catch (_: SecurityException) {
                // expected
            }
        }
    }

    @Test
    fun resolveSafePath_rejectsParentTraversalAndAbsolutePaths() {
        val (engine, _) = createEngineWithSibling()

        val unsafeInputs = listOf(
            "..",
            "../outside.txt",
            "sub/../../outside.txt",
            "..\\outside.txt",
            "/etc/passwd",
            "\\Windows\\System32\\drivers\\etc\\hosts",
            "C:\\temp\\escape.txt",
            "D:/escape.txt"
        )

        for (unsafe in unsafeInputs) {
            try {
                engine.resolveSafePath(unsafe)
                fail("Expected SecurityException for unsafe path: '$unsafe'")
            } catch (_: SecurityException) {
                // expected
            }
        }
    }

    @Test
    fun resolveSafePath_rejectsSiblingPrefixCollisionDir() {
        val (engine, siblingDir) = createEngineWithSibling()
        assertTrue(File(siblingDir, "secret.txt").isFile)

        try {
            engine.resolveSafePath("../workspace2/secret.txt")
            fail("Expected SecurityException for sibling prefix collision '../workspace2/secret.txt'")
        } catch (_: SecurityException) {
            // expected
        }
    }

    @Test
    fun resolveSafePath_allowsNormalizedNestedPathInsideRoot() {
        val (engine, _) = createEngineWithSibling()
        val resolved = engine.resolveSafePath("src/nested/../main/App.kt")
        assertTrue(resolved.canonicalPath.startsWith(engine.workspaceRoot.canonicalPath))
        assertEquals("App.kt", resolved.name)
    }

    @Test
    fun fileOperations_enforceSandboxContainmentAcrossReadWriteEditListSearch() {
        val (engine, _) = createEngineWithSibling()

        try {
            engine.writeFile("../outside.txt", "pwned")
            fail("writeFile should reject parent traversal")
        } catch (_: SecurityException) {
        }

        val readFailure = engine.readFile("../workspace2/secret.txt")
        assertTrue(readFailure.isFailure)
        assertTrue(readFailure.exceptionOrNull() is SecurityException)

        val editFailure = engine.editFile("../outside.txt", "parent", "hacked")
        assertTrue(editFailure.isFailure)
        assertTrue(editFailure.exceptionOrNull() is SecurityException)

        try {
            engine.listFiles("../workspace2")
            fail("listFiles should reject sibling traversal")
        } catch (_: SecurityException) {
        }

        try {
            engine.searchFiles("secret", pathPrefix = "../workspace2")
            fail("searchFiles should reject sibling traversal")
        } catch (_: SecurityException) {
        }
    }

    // =========================================================================
    // 2. LCS Unified Diff Computer Tests
    // =========================================================================

    @Test
    fun unifiedDiffComputer_returnsEmptyForIdenticalContent() {
        val text = "line 1\nline 2\nline 3\n"
        val diff = UnifiedDiffComputer.computeUnifiedDiff("sample.txt", text, text)
        assertEquals("", diff.unifiedDiff)
        assertEquals(0, diff.addedLines)
        assertEquals(0, diff.removedLines)
    }

    @Test
    fun unifiedDiffComputer_handlesBrandNewAndFullyDeletedFiles() {
        val created = UnifiedDiffComputer.computeUnifiedDiff(
            "new.kt",
            "",
            "package demo\nfun main() {}\n"
        )
        assertEquals(2, created.addedLines)
        assertEquals(0, created.removedLines)
        assertTrue(created.unifiedDiff.contains("--- a/new.kt"))
        assertTrue(created.unifiedDiff.contains("+++ b/new.kt"))
        assertTrue(created.unifiedDiff.contains("@@ -0,0 +1,2 @@"))
        assertTrue(created.unifiedDiff.contains("+package demo"))

        val deleted = UnifiedDiffComputer.computeUnifiedDiff(
            "old.kt",
            "alpha\nbeta\n",
            ""
        )
        assertEquals(0, deleted.addedLines)
        assertEquals(2, deleted.removedLines)
        assertTrue(deleted.unifiedDiff.contains("@@ -1,2 +0,0 @@"))
        assertTrue(deleted.unifiedDiff.contains("-alpha"))
        assertTrue(deleted.unifiedDiff.contains("-beta"))
    }

    @Test
    fun unifiedDiffComputer_usesGenuineLcsForShiftedLinesAndMultipleHunks() {
        val oldLines = (1..20).map { "line $it" }.toMutableList()
        val newLines = oldLines.toMutableList()
        // Insert a header line at top (shifts everything down) and edit line 18 far below
        newLines.add(0, "header 0")
        newLines[18] = "line 18 modified"

        val diff = UnifiedDiffComputer.computeUnifiedDiff(
            "multi.txt",
            oldLines.joinToString("\n"),
            newLines.joinToString("\n"),
            contextLines = 2
        )

        // Genuine LCS recognizes the 19 unchanged lines instead of marking everything changed
        assertEquals(2, diff.addedLines)
        assertEquals(1, diff.removedLines)
        val hunkCount = diff.unifiedDiff.lines().count { it.startsWith("@@ ") }
        assertEquals(2, hunkCount)
        assertTrue(diff.unifiedDiff.contains("+header 0"))
        assertTrue(diff.unifiedDiff.contains("-line 18"))
        assertTrue(diff.unifiedDiff.contains("+line 18 modified"))
    }

    // =========================================================================
    // 3. Workspace File CRUD, Search, Selection, Init & AGENTS.md
    // =========================================================================

    @Test
    fun initWorkspace_populatesStarterFilesOnceWithoutOverwritingAndLeavesAgentsMdMissing() {
        val dir = tempFolder.newFolder("ws_init")
        val engine = MobileWorkspaceEngine(dir)

        assertTrue(engine.state.value.files.isEmpty())
        assertFalse(engine.state.value.hasAgentsMd)

        val initialized = engine.initWorkspace()
        assertFalse(initialized.hasAgentsMd)
        assertTrue(initialized.files.any { it.relativePath == "README.md" })
        assertTrue(initialized.files.any { it.relativePath == "notes/scratchpad.md" })
        assertEquals("README.md", initialized.selectedFilePath)

        // Modify README and call initWorkspace again — must NOT overwrite user edits
        engine.writeFile("README.md", "# Custom User Readme\n")
        val refreshed = engine.initWorkspace()
        assertEquals("# Custom User Readme\n", engine.readFile("README.md").getOrThrow() + "\n")
        assertTrue(refreshed.files.any { it.relativePath == "README.md" })
    }

    @Test
    fun writeReadSelectAndFormatFiles_workEndToEnd() {
        val dir = tempFolder.newFolder("ws_crud")
        val engine = MobileWorkspaceEngine(dir)

        val diff = engine.writeFile(
            "src/Calculator.kt",
            "class Calculator {\n    fun add(a: Int, b: Int) = a + b\n    fun sub(a: Int, b: Int) = a - b\n}\n"
        )
        assertEquals("src/Calculator.kt", diff.relativePath)
        assertEquals(4, diff.addedLines)
        assertEquals(0, diff.removedLines)

        val slice = engine.readFile("src/Calculator.kt", startLine = 2, endLine = 3, includeLineNumbers = true)
        assertEquals("2:     fun add(a: Int, b: Int) = a + b\n3:     fun sub(a: Int, b: Int) = a - b", slice.getOrThrow())

        val selected = engine.selectFile("src/Calculator.kt").getOrThrow()
        assertTrue(selected.contains("class Calculator"))
        assertEquals("src/Calculator.kt", engine.state.value.selectedFilePath)
        assertEquals(selected, engine.state.value.selectedFileContent)

        val textSummary = engine.formatFilesAsText()
        assertTrue(textSummary.contains("src/Calculator.kt (4 lines"))

        val jsonArr = JSONArray(engine.formatFilesAsJson())
        assertEquals(1, jsonArr.length())
        assertEquals("src/Calculator.kt", jsonArr.getJSONObject(0).getString("path"))
        assertEquals(4, jsonArr.getJSONObject(0).getInt("lineCount"))
    }

    @Test
    fun editFile_replacesUniqueMatchAndRejectsMissingOrAmbiguousMatches() {
        val dir = tempFolder.newFolder("ws_edit")
        val engine = MobileWorkspaceEngine(dir)
        engine.writeFile("app.kt", "val tag = \"v1\"\nval duplicate = 1\nval duplicate = 1\n")
        engine.selectFile("app.kt")

        val ok = engine.editFile("app.kt", "val tag = \"v1\"", "val tag = \"v2\"")
        assertTrue(ok.isSuccess)
        val record = ok.getOrThrow()
        assertEquals(1, record.addedLines)
        assertEquals(1, record.removedLines)
        assertTrue(engine.state.value.selectedFileContent.contains("val tag = \"v2\""))

        val notFound = engine.editFile("app.kt", "nonexistent snippet", "x")
        assertTrue(notFound.isFailure)

        val ambiguous = engine.editFile("app.kt", "val duplicate = 1", "val duplicate = 2")
        assertTrue(ambiguous.isFailure)
        assertTrue(ambiguous.exceptionOrNull()?.message?.contains("2 locations") == true)
    }

    @Test
    fun searchFiles_returnsFormattedLineMatchesAndRespectsPathPrefix() {
        val dir = tempFolder.newFolder("ws_search")
        val engine = MobileWorkspaceEngine(dir)
        engine.writeFile("docs/guide.md", "First line\nMobile Studio Guide\nEnd")
        engine.writeFile("src/Main.kt", "fun main() {\n    println(\"Mobile\")\n}")

        val allMatches = engine.searchFiles("mobile")
        assertEquals(2, allMatches.size)
        assertTrue(allMatches.any { it == "docs/guide.md:2: Mobile Studio Guide" })
        assertTrue(allMatches.any { it == "src/Main.kt:2:     println(\"Mobile\")" })

        val scoped = engine.searchFiles("mobile", pathPrefix = "src")
        assertEquals(1, scoped.size)
        assertEquals("src/Main.kt:2:     println(\"Mobile\")", scoped.first())
    }

    @Test
    fun initAgentsMd_createsCleanAgentsGuideAndUpdatesStateFlag() {
        val dir = tempFolder.newFolder("ws_agents")
        val engine = MobileWorkspaceEngine(dir)
        assertFalse(engine.state.value.hasAgentsMd)

        val diff = engine.initAgentsMd("Automated Android UI testing sandbox")
        assertEquals("AGENTS.md", diff.relativePath)
        assertTrue(engine.state.value.hasAgentsMd)

        val text = engine.readFile("AGENTS.md").getOrThrow()
        assertTrue(text.contains("# AGENTS.md"))
        assertTrue(text.contains("Automated Android UI testing sandbox"))
        assertTrue(text.contains("PLAN.md"))
        assertFalse(text.contains("SAMSUNG"))
    }

    // =========================================================================
    // 4. Allowlisted Workspace Commands & Shell Injection Prevention
    // =========================================================================

    @Test
    fun execWorkspaceCommand_runsAllowlistedCommandsAndUpdatesLastExecOutput() {
        val dir = tempFolder.newFolder("ws_exec")
        val engine = MobileWorkspaceEngine(dir)
        engine.writeFile("a.txt", "alpha beta\ngamma")
        engine.writeFile("sub/b.txt", "alpha delta")

        val lsRes = engine.execWorkspaceCommand("ls -l")
        assertTrue(lsRes.succeeded)
        assertEquals(0, lsRes.exitCode)
        assertTrue(lsRes.stdout.contains("a.txt"))
        assertTrue(lsRes.stdout.contains("sub/b.txt"))

        val catRes = engine.execWorkspaceCommand("cat a.txt")
        assertTrue(catRes.succeeded)
        assertEquals("alpha beta\ngamma", catRes.stdout)

        val wcRes = engine.execWorkspaceCommand("wc a.txt")
        assertTrue(wcRes.succeeded)
        assertTrue(wcRes.stdout.startsWith("2 3 "))

        val grepRes = engine.execWorkspaceCommand("grep \"alpha\"")
        assertTrue(grepRes.succeeded)
        assertTrue(grepRes.stdout.contains("a.txt:1: alpha beta"))
        assertTrue(grepRes.stdout.contains("sub/b.txt:1: alpha delta"))

        val treeRes = engine.execWorkspaceCommand("tree")
        assertTrue(treeRes.succeeded)
        assertTrue(treeRes.stdout.contains("sub"))
        assertTrue(treeRes.stdout.contains("b.txt"))

        val statusRes = engine.execWorkspaceCommand("status")
        assertTrue(statusRes.succeeded)
        assertTrue(statusRes.stdout.contains("files: 2"))
        assertTrue(engine.state.value.lastExecOutput.contains("status"))

        val diffRes = engine.execWorkspaceCommand("diff a.txt sub/b.txt")
        assertTrue(diffRes.succeeded)
        assertTrue(diffRes.stdout.contains("--- a/sub/b.txt"))
        assertTrue(diffRes.stdout.contains("-alpha beta"))
        assertTrue(diffRes.stdout.contains("+alpha delta"))
    }

    @Test
    fun execWorkspaceCommand_blocksShellMetacharactersNonAllowlistedAndEscapes() {
        val (engine, _) = createEngineWithSibling()
        engine.writeFile("hello.txt", "hi")

        val dangerous = listOf(
            "cat hello.txt; rm -rf /",
            "ls && echo pwn",
            "cat hello.txt | grep hi",
            "echo \$(whoami)",
            "echo `id`",
            "echo hi > escape.txt",
            "cat ..\\outside.txt"
        )
        for (cmd in dangerous) {
            val res = engine.execWorkspaceCommand(cmd)
            assertFalse("Expected failure for dangerous command: $cmd", res.succeeded)
            assertEquals(126, res.exitCode)
        }

        val disallowed = engine.execWorkspaceCommand("rm hello.txt")
        assertFalse(disallowed.succeeded)
        assertEquals(127, disallowed.exitCode)

        val escapedCat = engine.execWorkspaceCommand("cat ../workspace2/secret.txt")
        assertFalse(escapedCat.succeeded)
        assertEquals(126, escapedCat.exitCode)
    }

    // =========================================================================
    // 5. Checkpoints, Restore & Markdown Artifacts
    // =========================================================================

    @Test
    fun captureAndRestoreCheckpoint_revertsModifiedDeletedAndAddedFilesWithDiffs() {
        val dir = tempFolder.newFolder("ws_cp")
        val engine = MobileWorkspaceEngine(dir)
        engine.writeFile("keep.txt", "v1\n")
        engine.writeFile("remove_later.txt", "original\n")

        val cp = engine.captureCheckpoint("Before refactor")
        assertEquals(2, cp.fileCount)
        assertEquals("Before refactor", cp.label)
        assertFalse(engine.listFiles().any { it.relativePath.contains(".checkpoints") })

        // Mutate workspace: modify keep.txt, delete remove_later.txt, add extra.txt
        engine.writeFile("keep.txt", "v2-broken\n")
        File(dir, "remove_later.txt").delete()
        engine.writeFile("extra/new_file.txt", "unwanted\n")
        engine.selectFile("keep.txt")

        assertTrue(engine.restoreCheckpoint(cp.id))

        assertEquals("v1", engine.readFile("keep.txt").getOrThrow())
        assertEquals("original", engine.readFile("remove_later.txt").getOrThrow())
        assertFalse(File(dir, "extra/new_file.txt").exists())
        assertEquals("v1\n", engine.state.value.selectedFileContent)
        assertTrue(engine.state.value.diffs.any { it.relativePath == "keep.txt" && it.unifiedDiff.contains("+v1") })

        // Verify checkpoints persist across engine re-instantiation
        val reloaded = MobileWorkspaceEngine(dir)
        assertTrue(reloaded.state.value.checkpoints.any { it.id == cp.id && it.label == "Before refactor" })
        assertFalse(reloaded.restoreCheckpoint("../evil"))
        assertFalse(reloaded.restoreCheckpoint("cp_missing"))
    }

    @Test
    fun updatePlanAndWalkthroughArtifacts_updatesUiStateAndOptionalFiles() {
        val dir = tempFolder.newFolder("ws_artifacts")
        val engine = MobileWorkspaceEngine(dir)

        engine.updatePlanArtifact("# Plan\n- [ ] Step 1")
        engine.updateWalkthroughArtifact("# Walkthrough\n- Done")
        assertEquals("# Plan\n- [ ] Step 1", engine.state.value.planArtifactMarkdown)
        assertEquals("# Walkthrough\n- Done", engine.state.value.walkthroughArtifactMarkdown)
        assertTrue(engine.state.value.files.isEmpty())

        engine.updatePlanArtifact("# Persisted Plan", persistToFile = true)
        assertTrue(engine.state.value.files.any { it.relativePath == "PLAN.md" })
    }

    // =========================================================================
    // 6. ComposerCommandParser Tests
    // =========================================================================

    @Test
    fun composerParser_exposesAllDefaultSlashCommands() {
        val commands = ComposerCommandParser.defaultSlashCommands.map { it.command }
        assertEquals(
            listOf(
                "/auto", "/plan", "/build", "/init", "/files", "/diff",
                "/undo", "/sessions", "/vault", "/watch", "/a2a", "/notes", "/help"
            ),
            commands
        )
    }

    @Test
    fun composerParser_parsesModeSlashCommandsAndMentionsCleanly() {
        val parsed = ComposerCommandParser.parse(
            "/plan @screen @file:src/App.kt, Refactor login flow.",
            currentMode = AgentWorkMode.AUTO
        )
        assertEquals("/plan", parsed.slashCommand)
        assertEquals(AgentWorkMode.PLAN, parsed.resolvedMode)
        assertEquals(listOf("@screen", "@file:src/App.kt"), parsed.mentions)
        assertEquals("Refactor login flow.", parsed.cleanPrompt)

        val buildParsed = ComposerCommandParser.parse("/BUILD run tests", AgentWorkMode.PLAN)
        assertEquals("/build", buildParsed.slashCommand)
        assertEquals(AgentWorkMode.BUILD, buildParsed.resolvedMode)
        assertEquals("run tests", buildParsed.cleanPrompt)
    }

    @Test
    fun composerParser_preservesCurrentModeForNonModeSlashAndIgnoresEmailsAndUnixPaths() {
        val nonMode = ComposerCommandParser.parse("/diff @agents", AgentWorkMode.BUILD)
        assertEquals("/diff", nonMode.slashCommand)
        assertEquals(AgentWorkMode.BUILD, nonMode.resolvedMode)
        assertEquals(listOf("@agents"), nonMode.mentions)
        assertEquals("", nonMode.cleanPrompt)

        val withEmailAndPath = ComposerCommandParser.parse(
            "/data/local/tmp email user@example.com about @screen.",
            AgentWorkMode.AUTO
        )
        assertNull(withEmailAndPath.slashCommand)
        assertEquals(AgentWorkMode.AUTO, withEmailAndPath.resolvedMode)
        assertEquals(listOf("@screen"), withEmailAndPath.mentions)
        assertEquals("/data/local/tmp email user@example.com about .", withEmailAndPath.cleanPrompt)

        val empty = ComposerCommandParser.parse("   ", AgentWorkMode.PLAN)
        assertNull(empty.slashCommand)
        assertEquals(AgentWorkMode.PLAN, empty.resolvedMode)
        assertTrue(empty.mentions.isEmpty())
        assertEquals("", empty.cleanPrompt)
    }

    @Test
    fun composerParser_filtersSlashAndMentionSuggestionsAccurately() {
        assertEquals(13, ComposerCommandParser.filterSlashSuggestions("/").size)
        val planFiltered = ComposerCommandParser.filterSlashSuggestions("  /pl")
        assertEquals(listOf("/plan"), planFiltered.map { it.command })
        assertTrue(ComposerCommandParser.filterSlashSuggestions("/plan next words").isEmpty())
        assertTrue(ComposerCommandParser.filterSlashSuggestions("hello /plan").isEmpty())

        val files = listOf(
            WorkspaceFileEntry("AGENTS.md", 120, 1L, isDirectory = false, lineCount = 10),
            WorkspaceFileEntry("src/MainActivity.kt", 420, 1L, isDirectory = false, lineCount = 24),
            WorkspaceFileEntry("src", 0, 1L, isDirectory = true, lineCount = 0)
        )
        val suggestions = ComposerCommandParser.buildMentionSuggestions(files, "com.example.shop")
        assertTrue(suggestions.any { it.token == "@screen" })
        assertTrue(suggestions.any { it.token == "@app" && it.description.contains("com.example.shop") })
        assertTrue(suggestions.any { it.token == "@tree" })
        assertTrue(suggestions.any { it.token == "@diff" })
        assertTrue(suggestions.any { it.token == "@agents" })
        assertTrue(suggestions.any { it.token == "@file:src/MainActivity.kt" })
        assertFalse(suggestions.any { it.token == "@file:src" })

        assertEquals(suggestions.size, ComposerCommandParser.filterMentionSuggestions("Check @", suggestions).size)
        val screenHits = ComposerCommandParser.filterMentionSuggestions("Check @scre", suggestions)
        assertEquals(listOf("@screen"), screenHits.map { it.token })
        assertTrue(ComposerCommandParser.filterMentionSuggestions("Check @va", suggestions).any { it.token == "@vault" })
        assertTrue(ComposerCommandParser.filterMentionSuggestions("Check @no", suggestions).any { it.token == "@notes" })
        assertTrue(ComposerCommandParser.filterMentionSuggestions("Check @wa", suggestions).any { it.token == "@watchers" })

        val fileHits = ComposerCommandParser.filterMentionSuggestions("Edit @Main", suggestions)
        assertEquals(listOf("@file:src/MainActivity.kt"), fileHits.map { it.token })

        assertTrue(ComposerCommandParser.filterMentionSuggestions("Check @screen ", suggestions).isEmpty())
        assertTrue(ComposerCommandParser.filterMentionSuggestions("mail user@test.com", suggestions).isEmpty())
    }

    // =========================================================================
    // 7. AgentSessionStore & JSON Persistence Tests
    // =========================================================================

    @Test
    fun sessionStore_initializesDefaultSessionAndManagesMultiSessionLifecycle() {
        val storeFile = File(tempFolder.newFolder("sessions_1"), "sessions.json")
        val store = AgentSessionStore(storeFile)

        val initial = store.state.value
        assertEquals("default-session", initial.activeSessionId)
        assertEquals(1, initial.sessions.size)
        assertNotNull(initial.activeSession)

        val bgSession = store.createSession("Background Runner", mode = AgentWorkMode.BUILD, isForeground = false)
        assertEquals(bgSession.id, store.state.value.activeSessionId)
        assertFalse(bgSession.isForeground)
        assertEquals(AgentWorkMode.BUILD, bgSession.mode)

        // Switch back to default session so bgSession becomes inactive background thread
        assertTrue(store.selectSession("default-session"))
        assertFalse(store.selectSession("nonexistent-session"))
        assertEquals("default-session", store.state.value.activeSessionId)

        val modeUpdated = store.updateActiveMode(AgentWorkMode.PLAN)
        assertEquals(AgentWorkMode.PLAN, modeUpdated?.mode)

        val toggled = store.toggleForeground(bgSession.id)
        assertTrue(toggled?.isForeground == true)
    }

    @Test
    fun sessionStore_updatesStatusUnreadApprovalArtifactsAndPersistsJsonRoundTrip() {
        val storeFile = File(tempFolder.newFolder("sessions_2"), "nested/sessions.json")
        val store = AgentSessionStore(storeFile)

        val worker = store.createSession("Code Fixer", mode = AgentWorkMode.BUILD, isForeground = false)
        store.selectSession("default-session")

        // Background status update should increment unreadCount & flag approval
        val updated = store.updateSessionStatus(
            sessionId = worker.id,
            statusBadge = "AWAITING_APPROVAL",
            previewLine = "Approve edit to Main.kt",
            foregroundPackage = "com.android.settings",
            hasPendingApproval = true
        )
        assertNotNull(updated)
        assertEquals(1, updated?.unreadCount)
        assertTrue(updated?.hasPendingApproval == true)
        assertEquals("com.android.settings", updated?.foregroundPackage)

        // Updating plan only preserves walkthrough and vice-versa
        store.updateArtifacts(worker.id, planMarkdown = "# Step 1")
        val withBoth = store.updateArtifacts(worker.id, walkthroughMarkdown = "# Verified")
        assertEquals("# Step 1", withBoth?.planMarkdown)
        assertEquals("# Verified", withBoth?.walkthroughMarkdown)

        // Clear unread
        val cleared = store.markAllRead(worker.id)
        assertEquals(0, cleared?.unreadCount)

        // Verify JSON persisted cleanly on disk and reloads identically
        val rawJson = JSONObject(storeFile.readText())
        assertEquals("default-session", rawJson.getString("activeSessionId"))
        assertEquals(2, rawJson.getJSONArray("sessions").length())

        val reloaded = AgentSessionStore(storeFile)
        val reloadedWorker = reloaded.state.value.sessions.firstOrNull { it.id == worker.id }
        assertNotNull(reloadedWorker)
        assertEquals("AWAITING_APPROVAL", reloadedWorker?.statusBadge)
        assertEquals("Approve edit to Main.kt", reloadedWorker?.previewLine)
        assertEquals("com.android.settings", reloadedWorker?.foregroundPackage)
        assertTrue(reloadedWorker?.hasPendingApproval == true)
        assertEquals("# Step 1", reloadedWorker?.planMarkdown)
        assertEquals("# Verified", reloadedWorker?.walkthroughMarkdown)
        assertEquals(0, reloadedWorker?.unreadCount)
    }

    @Test
    fun sessionStore_recoversGracefullyFromCorruptJsonFile() {
        val dir = tempFolder.newFolder("sessions_corrupt")
        val storeFile = File(dir, "sessions.json")
        storeFile.writeText("{not valid json!!!")

        val store = AgentSessionStore(storeFile)
        assertEquals("default-session", store.state.value.activeSessionId)
        assertEquals(1, store.state.value.sessions.size)
        // Rewritten file should now be valid JSON
        val parsed = JSONObject(storeFile.readText())
        assertEquals(1, parsed.getJSONArray("sessions").length())
    }

    // =========================================================================
    // 8. Additional Edge Cases & Regression Coverage
    // =========================================================================

    @Test
    fun resolveSafePath_rejectsSymlinkEscapingSandboxWhenSupportedByOs() {
        val (engine, siblingDir) = createEngineWithSibling()
        val outsideTarget = File(siblingDir, "secret.txt").toPath()
        val linkPath = File(engine.workspaceRoot, "escape_link.txt").toPath()

        val created = runCatching { Files.createSymbolicLink(linkPath, outsideTarget) }.isSuccess
        if (created) {
            try {
                engine.resolveSafePath("escape_link.txt")
                fail("Expected SecurityException for symlink escaping workspace")
            } catch (_: SecurityException) {
                // expected
            }
        }
    }

    @Test
    fun listFiles_respectsMaxDepthSubDirAndIgnoresInternalDirs() {
        val dir = tempFolder.newFolder("ws_depth")
        val engine = MobileWorkspaceEngine(dir)
        engine.writeFile("root.txt", "1")
        engine.writeFile("a/d2.txt", "2")
        engine.writeFile("a/b/d3.txt", "3")
        engine.writeFile("a/b/c/d4.txt", "4")
        File(dir, ".git").mkdirs()
        File(dir, ".git/config").writeText("ignored")
        File(dir, ".checkpoints/cp_1/files").mkdirs()
        File(dir, ".checkpoints/cp_1/files/snap.txt").writeText("ignored")

        val depth2 = engine.listFiles(maxDepth = 2).map { it.relativePath }
        assertEquals(listOf("a/d2.txt", "root.txt"), depth2)

        val subFiles = engine.listFiles(subDir = "a/b", maxDepth = 4).map { it.relativePath }
        assertEquals(listOf("a/b/c/d4.txt", "a/b/d3.txt"), subFiles)
        assertTrue(engine.listFiles(subDir = "missing_dir").isEmpty())
    }

    @Test
    fun readFile_handlesEmptyOutOfRangeMissingAndDirectorySafely() {
        val dir = tempFolder.newFolder("ws_read_edges")
        val engine = MobileWorkspaceEngine(dir)
        engine.writeFile("empty.txt", "")
        engine.writeFile("sub/two_lines.txt", "first\nsecond")

        assertEquals("", engine.readFile("empty.txt").getOrThrow())
        assertEquals("", engine.readFile("sub/two_lines.txt", startLine = 10, endLine = 20).getOrThrow())
        assertTrue(engine.readFile("does_not_exist.txt").isFailure)
        assertTrue(engine.readFile("sub").isFailure)
    }

    @Test
    fun searchFiles_ignoresBinaryFilesAndRespectsIgnoreCaseFlag() {
        val dir = tempFolder.newFolder("ws_search_binary")
        val engine = MobileWorkspaceEngine(dir)
        engine.writeFile("notes.txt", "AlphaBeta\nlowercase")
        File(dir, "binary.bin").writeBytes(byteArrayOf(0x41, 0x00, 0x42, 0x43))

        assertTrue(engine.searchFiles("ABC").isEmpty())
        assertEquals(1, engine.searchFiles("alphabeta", ignoreCase = true).size)
        assertTrue(engine.searchFiles("alphabeta", ignoreCase = false).isEmpty())
        assertEquals(1, engine.searchFiles("AlphaBeta", ignoreCase = false).size)
    }

    @Test
    fun execWorkspaceCommand_coversEchoPwdTreeSubdirWcFlagsAndSyntaxErrors() {
        val dir = tempFolder.newFolder("ws_exec_more")
        val engine = MobileWorkspaceEngine(dir)
        engine.writeFile("sub/file.txt", "one two\nthree")

        val pwdRes = engine.execWorkspaceCommand("pwd")
        assertTrue(pwdRes.succeeded)
        assertEquals(dir.canonicalPath, pwdRes.stdout)

        val echoRes = engine.execWorkspaceCommand("echo \"studio ready\"")
        assertTrue(echoRes.succeeded)
        assertEquals("studio ready", echoRes.stdout)

        val treeRes = engine.execWorkspaceCommand("tree sub")
        assertTrue(treeRes.succeeded)
        assertTrue(treeRes.stdout.contains("file.txt"))

        val wcLinesRes = engine.execWorkspaceCommand("wc -l sub/file.txt")
        assertTrue(wcLinesRes.succeeded)
        assertEquals("2 sub/file.txt", wcLinesRes.stdout)

        val diffsSummary = engine.execWorkspaceCommand("diff")
        assertTrue(diffsSummary.succeeded)
        assertTrue(diffsSummary.stdout.contains("+++ b/sub/file.txt"))

        val unclosedQuote = engine.execWorkspaceCommand("echo \"unclosed")
        assertFalse(unclosedQuote.succeeded)
        assertEquals(2, unclosedQuote.exitCode)
    }

    @Test
    fun unifiedDiffComputer_handlesWindowsCrlfAndSingleMiddleDeletion() {
        val oldText = "l1\r\nl2\r\nl3\r\nl4\r\nl5\r\n"
        val newText = "l1\nl2\nl4\nl5\n"
        val diff = UnifiedDiffComputer.computeUnifiedDiff("crlf.txt", oldText, newText, contextLines = 1)
        assertEquals(0, diff.addedLines)
        assertEquals(1, diff.removedLines)
        assertTrue(diff.unifiedDiff.contains("@@ -2,3 +2,2 @@"))
        assertTrue(diff.unifiedDiff.contains("-l3"))
    }

    @Test
    fun sessionStore_handlesBlankTitleFallbackPreserveStatusAndFallbackActiveSession() {
        val storeFile = File(tempFolder.newFolder("sessions_edges"), "sessions.json")
        val store = AgentSessionStore(storeFile)

        val untitled = store.createSession("   ", mode = AgentWorkMode.PLAN)
        assertTrue(untitled.title.startsWith("Session #"))
        assertEquals(AgentWorkMode.PLAN, untitled.mode)

        store.updateSessionStatus(untitled.id, "RUNNING", previewLine = "Initial preview", foregroundPackage = "com.demo.app")
        val preserved = store.updateSessionStatus(untitled.id, "DONE", previewLine = "", foregroundPackage = "")
        assertEquals("DONE", preserved?.statusBadge)
        assertEquals("Initial preview", preserved?.previewLine)
        assertEquals("com.demo.app", preserved?.foregroundPackage)
        assertNull(store.markAllRead("missing-id"))

        val orphanState = SessionStoreState(
            activeSessionId = "deleted-id",
            sessions = listOf(SessionThread(id = "s1", title = "Fallback"))
        )
        assertEquals("s1", orphanState.activeSession?.id)
    }
}
