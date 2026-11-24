package com.hanson.mobileuse.accessibility

import com.hanson.mobileuse.agent.PhoneControlGate
import com.hanson.mobileuse.agent.PhoneControlTier
import com.hanson.mobileuse.agent.WirelessAdbPairPhase
import com.hanson.mobileuse.agent.WirelessAdbSelfPairHelper
import com.hanson.mobileuse.service.HansonVirtualDisplayManager
import com.hanson.mobileuse.service.VirtualDisplayMode
import com.hanson.mobileuse.service.VirtualDisplayStatus
import com.hanson.mobileuse.service.VirtualDisplayTakeoverState
import com.hanson.mobileuse.workspace.MobileWorkspaceEngine
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Comprehensive JVM JUnit4 verification suite for Track C Mobile-Harness Android SOTA absorption:
 * - `A11yReadGate` (bounded daemon pool, single-flight coalescing, timeout/saturation degraded fallback)
 * - `DeepLinkInspector` (read-only manifest parser, custom + https links, unsafe scheme rejection)
 * - `IndexedUiElement` & `HierarchyDumper` (`offscreen` / `hidden` computation & JSON serialization)
 * - `HansonVirtualDisplayManager` (lease lock mutual exclusion, 3-state mode, Take Over, banking block)
 * - `MobileWorkspaceEngine` (per-file undo/accept, symlink guard, 3-tier repo map, 80% compaction,
 *   PBKDF2 + 1 MiB segmented AES-256-GCM `.hansonbackup` v1 round-trip & tamper rejection)
 * - `PhoneControlGate` & `WirelessAdbSelfPairHelper` (3-tier risk gate & RFC1918/loopback pairing guard)
 */
class MobileHarnessAbsorptionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        HansonVirtualDisplayManager.resetForTesting()
        DeepLinkInspector.clearCache()
    }

    @After
    fun tearDown() {
        HansonVirtualDisplayManager.resetForTesting()
        DeepLinkInspector.clearCache()
    }

    // =========================================================================
    // 1. A11yReadGate: Single-flight, Timeout -> Degraded Cache, Saturation
    // =========================================================================

    @Test
    fun a11yReadGate_coalescesConcurrentSingleFlightRequests() {
        val gate = A11yReadGate.createEngine(maxWorkers = 4, defaultTimeoutMs = 2_000L)
        val producerCalls = AtomicInteger(0)
        val enteredLatch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        val callersPool = Executors.newFixedThreadPool(3)

        try {
            val expectedSnapshot = AtomicSnapshot(
                timestampMs = 123456L,
                activePackage = "com.kakao.talk",
                windowCount = 1
            )

            val futures = mutableListOf<Future<GatedSnapshotResult>>()
            // Start first caller so it creates the in-flight entry
            futures.add(
                callersPool.submit<GatedSnapshotResult> {
                    gate.executeGated(flightKey = "same-flight", timeoutMs = 2_000L, cacheCleared = true) {
                        producerCalls.incrementAndGet()
                        enteredLatch.countDown()
                        releaseLatch.await(3, TimeUnit.SECONDS)
                        expectedSnapshot
                    }
                }
            )
            assertTrue("First worker should enter producer", enteredLatch.await(2, TimeUnit.SECONDS))

            // Launch 2 more concurrent callers with the identical flightKey
            repeat(2) {
                futures.add(
                    callersPool.submit<GatedSnapshotResult> {
                        gate.executeGated(flightKey = "same-flight", timeoutMs = 2_000L, cacheCleared = true) {
                            producerCalls.incrementAndGet()
                            expectedSnapshot
                        }
                    }
                )
            }

            // Small yield so coalescing callers attach to the in-flight CompletableFuture
            Thread.sleep(60L)
            releaseLatch.countDown()

            for (f in futures) {
                val res = f.get(3, TimeUnit.SECONDS)
                assertFalse("Fresh single-flight snapshot should not be degraded", res.degraded)
                assertNull(res.degradedReason)
                assertEquals(0L, res.snapshotAgeMs)
                assertTrue(res.cacheCleared)
                assertEquals("com.kakao.talk", res.snapshot?.activePackage)
            }
            assertEquals("Single-flight must invoke producer exactly once", 1, producerCalls.get())
        } finally {
            releaseLatch.countDown()
            callersPool.shutdownNow()
            gate.shutdownNow()
        }
    }

    @Test
    fun a11yReadGate_returnsDegradedCachedSnapshotOnTimeout() {
        val fakeNow = AtomicLong(10_000L)
        val gate = A11yReadGate.createEngine(
            maxWorkers = 2,
            defaultTimeoutMs = 80L,
            clockMs = { fakeNow.get() }
        )
        val stuckLatch = CountDownLatch(1)

        try {
            val primedSnap = AtomicSnapshot(
                timestampMs = 10_000L,
                activePackage = "com.nhn.android.nmap",
                windowCount = 2
            )
            val first = gate.executeGated(flightKey = "prime", timeoutMs = 1_000L) { primedSnap }
            assertFalse(first.degraded)
            assertEquals("com.nhn.android.nmap", gate.lastGoodSnapshot()?.activePackage)

            // Advance clock by 2500ms and trigger a binder hang exceeding timeoutMs
            fakeNow.set(12_500L)
            val timedOut = gate.executeGated(flightKey = "hang-call", timeoutMs = 80L, cacheCleared = true) {
                stuckLatch.await(5, TimeUnit.SECONDS)
                AtomicSnapshot(activePackage = "late.pkg")
            }

            assertTrue("Timeout must degrade gracefully", timedOut.degraded)
            assertEquals(A11yReadGate.REASON_TIMEOUT, timedOut.degradedReason)
            assertEquals(2_500L, timedOut.snapshotAgeMs)
            assertEquals("com.nhn.android.nmap", timedOut.snapshot?.activePackage)
            assertTrue(timedOut.cacheCleared)

            val json = timedOut.toJson(includeFullTree = false)
            assertTrue(json.getBoolean("degraded"))
            assertEquals("a11y_timeout", json.getString("degradedReason"))
            assertEquals(2_500L, json.getLong("snapshotAgeMs"))
            assertTrue(json.getBoolean("cacheCleared"))
        } finally {
            stuckLatch.countDown()
            gate.shutdownNow()
        }
    }

    @Test
    fun a11yReadGate_returnsDegradedCachedSnapshotOnPoolSaturationAndCacheClearSafeOnJvm() {
        assertFalse(
            "clearAccessibilityCache(null) must return false without throwing on JVM",
            A11yReadGate.clearAccessibilityCache(null)
        )

        val fakeNow = AtomicLong(50_000L)
        val gate = A11yReadGate.createEngine(
            maxWorkers = 1,
            defaultTimeoutMs = 120L,
            clockMs = { fakeNow.get() }
        )
        val workerEntered = CountDownLatch(1)
        val workerRelease = CountDownLatch(1)
        val bg = Executors.newSingleThreadExecutor()

        try {
            val cachedSnap = AtomicSnapshot(timestampMs = 50_000L, activePackage = "com.coupang.mobile")
            assertFalse(gate.executeGated(flightKey = "init", timeoutMs = 1_000L) { cachedSnap }.degraded)

            // Saturate the single worker thread with a distinct flight key
            bg.submit {
                gate.executeGated(flightKey = "hog-1", timeoutMs = 2_000L) {
                    workerEntered.countDown()
                    workerRelease.await(5, TimeUnit.SECONDS)
                    AtomicSnapshot(activePackage = "hog")
                }
            }
            assertTrue("Worker should be occupied", workerEntered.await(2, TimeUnit.SECONDS))

            fakeNow.set(51_200L)
            val saturated = gate.executeGated(flightKey = "distinct-2", timeoutMs = 1_000L) {
                AtomicSnapshot(activePackage = "never-runs")
            }

            assertTrue("Saturated pool must degrade immediately", saturated.degraded)
            assertEquals(A11yReadGate.REASON_SATURATED, saturated.degradedReason)
            assertEquals(1_200L, saturated.snapshotAgeMs)
            assertEquals("com.coupang.mobile", saturated.snapshot?.activePackage)
        } finally {
            workerRelease.countDown()
            bg.shutdownNow()
            gate.shutdownNow()
        }
    }

    // =========================================================================
    // 2. DeepLinkInspector: Manifest Parsing & Unsafe Scheme Rejection
    // =========================================================================

    @Test
    fun deepLinkInspector_parsesCustomSchemesHttpsAppLinksAndRejectsUnsafeSchemes() {
        val manifestXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.nav">
                <application>
                    <activity android:name=".MapActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.VIEW" />
                            <category android:name="android.intent.category.DEFAULT" />
                            <category android:name="android.intent.category.BROWSABLE" />
                            <data android:scheme="nmap" android:host="route" android:pathPrefix="/car" />
                        </intent-filter>
                        <intent-filter>
                            <action android:name="android.intent.action.VIEW" />
                            <category android:name="android.intent.category.BROWSABLE" />
                            <data android:scheme="https" />
                            <data android:host="map.naver.com" />
                            <data android:pathPrefix="/p/entry" />
                        </intent-filter>
                        <intent-filter>
                            <action android:name="android.intent.action.VIEW" />
                            <data android:scheme="javascript" android:host="xss" />
                            <data android:scheme="file" android:pathPrefix="/etc" />
                            <data android:scheme="content" android:host="sms" />
                            <data android:scheme="data" />
                        </intent-filter>
                    </activity>
                    <activity android:name=".PrivateActivity" android:exported="false">
                        <intent-filter>
                            <action android:name="android.intent.action.VIEW" />
                            <data android:scheme="secretapp" android:host="internal" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent()

        val links = DeepLinkInspector.parseManifestXmlDeepLinks("com.example.nav", manifestXml)
        assertEquals("Exactly 2 safe exported deep links should be extracted", 2, links.size)

        val custom = links.first { it.scheme == "nmap" }
        assertEquals("com.example.nav.MapActivity", custom.activityName)
        assertEquals("route", custom.host)
        assertEquals("/car", custom.pathPrefix)
        assertEquals("nmap://route/car", custom.sampleUri)

        val appLink = links.first { it.scheme == "https" }
        assertEquals("map.naver.com", appLink.host)
        assertEquals("/p/entry", appLink.pathPrefix)
        assertEquals("https://map.naver.com/p/entry", appLink.sampleUri)

        // Verify unsafe schemes and non-exported activities were completely excluded
        assertTrue(links.none { it.scheme in DeepLinkInspector.FORBIDDEN_SCHEMES })
        assertTrue(links.none { it.scheme == "secretapp" })

        // Verify URI dry-run validator
        assertTrue(DeepLinkInspector.validateDeepLinkUri("https://map.naver.com/p/entry").first)
        assertTrue(DeepLinkInspector.validateDeepLinkUri("kakaotalk://open").first)
        assertFalse(DeepLinkInspector.validateDeepLinkUri("javascript:alert(1)").first)
        assertFalse(DeepLinkInspector.validateDeepLinkUri("file:///etc/passwd").first)
        assertFalse(DeepLinkInspector.validateDeepLinkUri("content://sms/inbox").first)
        assertFalse(DeepLinkInspector.validateDeepLinkUri("data:text/html,<h1>x</h1>").first)
        assertFalse(DeepLinkInspector.validateDeepLinkUri("not-a-uri").first)
    }

    // =========================================================================
    // 3. IndexedUiElement & HierarchyDumper: offscreen / hidden Serialization
    // =========================================================================

    @Test
    fun indexedUiElement_serializesOffscreenAndHiddenWithBackwardCompatibleDefaults() {
        val defaultEl = IndexedUiElement(
            index = 0,
            shortClass = "Button",
            className = "android.widget.Button",
            packageName = "com.kakao.talk",
            text = "전송",
            contentDesc = "",
            resourceId = "com.kakao.talk:id/send",
            left = 900,
            top = 2100,
            right = 1050,
            bottom = 2250,
            centerX = 975,
            centerY = 2175,
            normX = 903,
            normY = 906,
            clickable = true,
            longClickable = false,
            scrollable = false,
            editable = false,
            checkable = false,
            checked = false,
            focused = false,
            enabled = true,
            windowType = "APPLICATION",
            windowLayer = 1
        )
        assertFalse(defaultEl.offscreen)
        assertFalse(defaultEl.hidden)
        assertFalse(defaultEl.toJson().getBoolean("offscreen"))
        assertFalse(defaultEl.toJson().getBoolean("hidden"))

        val offscreenHiddenEl = defaultEl.copy(
            index = 1,
            left = 1200,
            right = 1400,
            offscreen = true,
            hidden = true
        )
        val elJson = offscreenHiddenEl.toJson()
        assertTrue(elJson.getBoolean("offscreen"))
        assertTrue(elJson.getBoolean("hidden"))
        assertTrue(offscreenHiddenEl.toPromptLine().contains("offscreen"))
        assertTrue(offscreenHiddenEl.toPromptLine().contains("hidden"))

        val snap = AtomicSnapshot(indexedElements = listOf(defaultEl, offscreenHiddenEl))
        val snapElements = snap.toJson(includeFullTree = false).getJSONArray("elements")
        assertEquals(2, snapElements.length())
        assertTrue(snapElements.getJSONObject(1).getBoolean("offscreen"))
        assertTrue(snapElements.getJSONObject(1).getBoolean("hidden"))

        // Verify pure offscreen bounds computation
        assertFalse(HierarchyDumper.isBoundsOffscreen(100, 200, 300, 400, 1080, 2400))
        assertTrue(HierarchyDumper.isBoundsOffscreen(1080, 200, 1280, 400, 1080, 2400))
        assertTrue(HierarchyDumper.isBoundsOffscreen(100, 2400, 300, 2600, 1080, 2400))
        assertTrue(HierarchyDumper.isBoundsOffscreen(-200, 200, 0, 400, 1080, 2400))
        assertTrue(HierarchyDumper.isBoundsOffscreen(100, 200, 100, 200, 1080, 2400))
    }

    // =========================================================================
    // 4. HansonVirtualDisplayManager: Lease, Mode, Takeover & Banking Refusal
    // =========================================================================

    @Test
    fun virtualDisplayManager_enforcesLeaseExclusionModeSwitchTakeoverAndBankingRefusal() {
        // 1. Lease mutual exclusion (anti-collision lock)
        val (acquiredA, _) = HansonVirtualDisplayManager.acquireLease("crew-alpha", ttlMs = 30_000L, nowMs = 1_000L)
        assertTrue(acquiredA)
        assertEquals("crew-alpha", HansonVirtualDisplayManager.currentLeaseOwner(nowMs = 5_000L))

        val (acquiredBWhileHeld, collisionMsg) = HansonVirtualDisplayManager.acquireLease(
            ownerId = "crew-beta",
            ttlMs = 30_000L,
            nowMs = 5_000L
        )
        assertFalse("Second concurrent owner must be blocked", acquiredBWhileHeld)
        assertTrue(collisionMsg.contains("crew-alpha"))

        // Non-owner cannot release another owner's active lease
        assertFalse(HansonVirtualDisplayManager.releaseLease("crew-beta", nowMs = 5_000L))
        // Owner can renew and release
        assertTrue(HansonVirtualDisplayManager.acquireLease("crew-alpha", ttlMs = 20_000L, nowMs = 5_000L).first)
        assertTrue(HansonVirtualDisplayManager.releaseLease("crew-alpha", nowMs = 6_000L))
        assertNull(HansonVirtualDisplayManager.currentLeaseOwner(nowMs = 6_000L))

        // After release (or expiry), crew-beta can acquire
        assertTrue(HansonVirtualDisplayManager.acquireLease("crew-beta", ttlMs = 5_000L, nowMs = 6_000L).first)
        assertNull("Expired lease must clear automatically", HansonVirtualDisplayManager.currentLeaseOwner(nowMs = 12_000L))

        // 2. Mode switching + Take Over <-> Return Control state machine
        val bgSnap = HansonVirtualDisplayManager.switchMode(VirtualDisplayMode.BACKGROUND)
        assertEquals(VirtualDisplayMode.BACKGROUND, bgSnap.mode)
        assertEquals(VirtualDisplayTakeoverState.RUNNING, bgSnap.takeoverState)

        val fgSnap = HansonVirtualDisplayManager.switchMode(VirtualDisplayMode.FOREGROUND)
        assertEquals(VirtualDisplayMode.FOREGROUND, fgSnap.mode)

        val takeoverSnap = HansonVirtualDisplayManager.requestUserTakeover("결제 전 직접 확인이 필요합니다.")
        assertEquals(VirtualDisplayTakeoverState.AWAITING_USER_TAKEOVER, takeoverSnap.takeoverState)
        assertEquals("결제 전 직접 확인이 필요합니다.", takeoverSnap.reasonKo)
        assertEquals(
            VirtualDisplayTakeoverState.AWAITING_USER_TAKEOVER,
            HansonVirtualDisplayManager.uiSnapshot.value.takeoverState
        )

        // Input injection blocked while AWAITING_USER_TAKEOVER
        val (inputBlocked, _) = HansonVirtualDisplayManager.injectDisplayInput("tap", 200, 300)
        assertFalse("Automated input must be blocked during user takeover", inputBlocked)

        val returnedSnap = HansonVirtualDisplayManager.returnControlFromUser()
        assertEquals(VirtualDisplayTakeoverState.RETURNED_CONTROL, returnedSnap.takeoverState)

        val resumedSnap = HansonVirtualDisplayManager.resumeAgentAfterTakeover()
        assertEquals(VirtualDisplayTakeoverState.RUNNING, resumedSnap.takeoverState)

        // 3. Banking / financial package launch strictly refused
        val bankingPackages = listOf(
            "com.kakaobank.channel",
            "viva.republica.toss",
            "com.kbstar.kbbank",
            "com.chase.sig.android",
            "com.agilebits.onepassword"
        )
        for (pkg in bankingPackages) {
            val (ok, msg) = HansonVirtualDisplayManager.launchAppOnVirtualDisplay(null, pkg)
            assertFalse("Banking/sensitive package '$pkg' must be strictly refused", ok)
            assertTrue("Refusal must explain security policy for $pkg", msg.contains("보안") || msg.contains("차단"))
        }

        // Non-banking package on inactive display fails honestly (never fabricates success)
        val (inactiveOk, inactiveMsg) = HansonVirtualDisplayManager.launchAppOnVirtualDisplay(null, "com.kakao.talk")
        assertFalse(inactiveOk)
        assertTrue(inactiveMsg.contains("VirtualDisplay"))

        // 4. Input validation: bounds & shell metacharacters
        val fakeActive = VirtualDisplayStatus(active = true, displayId = 2, width = 1080, height = 1920)
        assertTrue(
            HansonVirtualDisplayManager.validateDisplayInput(
                action = "tap",
                x = 540,
                y = 960,
                statusOverride = fakeActive,
                takeoverOverride = VirtualDisplayTakeoverState.RUNNING
            ).first
        )
        assertFalse(
            "Out-of-bounds coordinates must fail",
            HansonVirtualDisplayManager.validateDisplayInput(
                action = "tap",
                x = 5000,
                y = 960,
                statusOverride = fakeActive,
                takeoverOverride = VirtualDisplayTakeoverState.RUNNING
            ).first
        )
        assertFalse(
            "Negative coordinates must fail",
            HansonVirtualDisplayManager.validateDisplayInput(
                action = "swipe",
                x = -10,
                y = 100,
                x2 = 200,
                y2 = 300,
                statusOverride = fakeActive,
                takeoverOverride = VirtualDisplayTakeoverState.RUNNING
            ).first
        )
        assertFalse(
            "Shell metacharacters in text must be refused",
            HansonVirtualDisplayManager.validateDisplayInput(
                action = "text",
                x = 0,
                y = 0,
                text = "hello; rm -rf /",
                statusOverride = fakeActive,
                takeoverOverride = VirtualDisplayTakeoverState.RUNNING
            ).first
        )
    }

    // =========================================================================
    // 5. MobileWorkspaceEngine: Undo/Accept, Symlink, RepoMap, Compaction, Backup
    // =========================================================================

    @Test
    fun workspaceEngine_supportsPerFileUndoAcceptAndSymlinkGuard() {
        val root = tempFolder.newFolder("ws_undo_accept")
        val outside = tempFolder.newFile("outside_secret.txt").apply {
            writeText("SECRET_OUTSIDE", StandardCharsets.UTF_8)
        }
        val engine = MobileWorkspaceEngine(root)

        engine.writeFile("src/Alpha.kt", "class Alpha { val v = 1 }")
        engine.writeFile("src/Beta.kt", "class Beta { val v = 1 }")
        engine.captureCheckpoint("baseline-1")

        engine.writeFile("src/Alpha.kt", "class Alpha { val v = 2 }")
        engine.writeFile("src/Beta.kt", "class Beta { val v = 2 }")
        engine.writeFile("src/TempNew.kt", "object TempNew")

        // Undo ONLY Alpha.kt -> Beta.kt must remain at v=2
        assertTrue(engine.undoFileChange("src/Alpha.kt"))
        assertEquals("class Alpha { val v = 1 }", engine.readFile("src/Alpha.kt").getOrThrow())
        assertEquals("class Beta { val v = 2 }", engine.readFile("src/Beta.kt").getOrThrow())

        // Undo newly created file TempNew.kt -> should remove file
        assertTrue(engine.undoFileChange("src/TempNew.kt"))
        assertTrue(engine.readFile("src/TempNew.kt").isFailure)

        // Accept Beta.kt -> updates checkpoint baseline & clears Beta diffs
        assertTrue(engine.acceptFileChange("src/Beta.kt"))
        assertTrue(engine.state.value.diffs.none { it.relativePath == "src/Beta.kt" })
        assertFalse("Subsequent undo after accept must return false", engine.undoFileChange("src/Beta.kt"))
        assertEquals("class Beta { val v = 2 }", engine.readFile("src/Beta.kt").getOrThrow())

        // Symlink guard (gracefully handles Windows environments without symlink privilege)
        val linkPath = File(root, "escape_link.txt").toPath()
        val symlinkCreated = runCatching {
            Files.createSymbolicLink(linkPath, outside.toPath())
            true
        }.getOrDefault(false)

        if (symlinkCreated) {
            val files = engine.listFiles()
            assertTrue("Workspace walk must skip symbolic links", files.none { it.relativePath == "escape_link.txt" })
            val cp = engine.captureCheckpoint("after-symlink")
            assertEquals("Checkpoint must not copy symlinked external files", 2, cp.fileCount)
            try {
                engine.resolveSafePath("escape_link.txt")
                fail("resolveSafePath must reject symlink escaping workspace")
            } catch (_: SecurityException) {
                // expected
            }
        }
    }

    @Test
    fun workspaceEngine_generatesThreeTierRepoMapAndAutoCompactsAt80PercentCeiling() {
        val engine = MobileWorkspaceEngine(tempFolder.newFolder("ws_repomap"))
        engine.writeFile(
            "app/Service.kt",
            """
                package demo
                interface SyncGateway {
                    fun push(payload: String): Boolean
                }
                class HansonSyncService : SyncGateway {
                    override fun push(payload: String): Boolean = true
                }
                object SyncDefaults
            """.trimIndent()
        )
        engine.writeFile(
            "scripts/runner.py",
            """
                class DeviceRunner:
                    def execute_plan(self, goal):
                        return True
                def parse_trace(path):
                    return []
            """.trimIndent()
        )
        engine.writeFile(
            "ios/Portal.swift",
            """
                struct PortalClient {
                    func fetchTree() -> String { "" }
                }
            """.trimIndent()
        )
        engine.writeFile(
            "web/client.ts",
            """
                export interface DeviceState { ready: boolean }
                export function connectDevice(id: string) { return id }
            """.trimIndent()
        )

        val tier1 = engine.generateRepoMap(maxTokens = 1500)
        assertTrue("Expected Tier 1 repo map", tier1.contains("[tier=1"))
        assertTrue(tier1.contains("HansonSyncService"))
        assertTrue(tier1.contains("SyncGateway"))
        assertTrue(tier1.contains("DeviceRunner"))
        assertTrue(tier1.contains("execute_plan"))
        assertTrue(tier1.contains("PortalClient"))
        assertTrue(tier1.contains("connectDevice"))

        // Tighter token budgets degrade cleanly across Tier 2 and Tier 3
        val tier2Map = engine.generateRepoMap(maxTokens = 95)
        assertTrue("Expected Tier 2 compact symbol map at 95 tokens", tier2Map.contains("[tier=2"))
        assertTrue(tier2Map.contains("HansonSyncService"))

        val tier3Map = engine.generateRepoMap(maxTokens = 36)
        assertTrue("Very tight budget must degrade to tier 3", tier3Map.contains("[tier=3"))

        // 80% Auto-compaction tests
        val smallMsgs = listOf("System: Hanson Agent", "User: hi", "Assistant: ready")
        val (uncompacted, didCompactSmall) = engine.autoCompactContext(smallMsgs, maxTokenBudget = 500)
        assertFalse(didCompactSmall)
        assertEquals(smallMsgs, uncompacted)

        val longMsgs = mutableListOf("System: You are Hanson autonomous mobile agent.")
        repeat(16) { idx ->
            longMsgs.add(
                "Turn #$idx: Detailed UI dump observation with repeated telemetry tokens " +
                    "package=com.kakao.talk screen=chatroom_${idx} status=verified action=scroll_no_fling"
            )
        }
        longMsgs.add("User: Please confirm the final reservation time.")

        val originalTokens = longMsgs.sumOf { MobileWorkspaceEngine.estimateTokens(it) }
        val (compacted, didCompactLong) = engine.autoCompactContext(
            messages = longMsgs,
            maxTokenBudget = 240,
            thresholdRatio = 0.80
        )
        assertTrue("Conversation over 80% ceiling must compact", didCompactLong)
        assertTrue("Compacted message list must be shorter", compacted.size < longMsgs.size)
        assertTrue(compacted.first().contains("System:"))
        assertTrue(compacted[1].contains("[Compacted Context Digest"))
        assertEquals("User: Please confirm the final reservation time.", compacted.last())
        val compactedTokens = compacted.sumOf { MobileWorkspaceEngine.estimateTokens(it) }
        assertTrue("Compacted tokens ($compactedTokens) must be < original ($originalTokens)", compactedTokens < originalTokens)
    }

    @Test
    fun workspaceEngine_encryptedBackupRoundTripMultiChunkAndTamperRejection() {
        val srcEngine = MobileWorkspaceEngine(tempFolder.newFolder("ws_backup_src"))
        srcEngine.writeFile("README.md", "# Hanson Encrypted Backup\n한국어 UTF-8 백업 테스트")
        srcEngine.writeFile("src/Config.kt", "object Config { const val VERSION = 670 }")
        // Create > 1 MiB file (~1.19 MiB > 1,048,576 bytes) to exercise >= 2 segmented AES-256-GCM chunks
        val largePayload = buildString(1_250_000) {
            repeat(26_000) { idx ->
                append("line_").append(idx).append("_hanson_segmented_gcm_payload_block\n")
            }
        }
        srcEngine.writeFile("data/large.log", largePayload)

        val backupFile = File(tempFolder.root, "workspace_test.hansonbackup")
        val exportPass = "Hanson-Strong-Passphrase-2026!".toCharArray()
        val exportResult = srcEngine.exportEncryptedBackup(exportPass, backupFile)

        // Verify passphrase buffer was zero-wiped in finally
        assertTrue("Passphrase buffer must be wiped after export", exportPass.all { it == '\u0000' })
        assertEquals(1, exportResult.containerVersion)
        assertEquals(3, exportResult.fileCount)
        assertTrue("Payload > 1 MiB must span >= 2 GCM chunks", exportResult.chunkCount >= 2)

        // Verify magic header "HSNBKP01"
        val rawBytes = backupFile.readBytes()
        assertArrayEquals(MobileWorkspaceEngine.BACKUP_MAGIC, rawBytes.copyOfRange(0, 8))

        // Restore into a fresh workspace and verify exact byte-for-byte contents
        val dstEngine = MobileWorkspaceEngine(tempFolder.newFolder("ws_backup_dst"))
        val importPass = "Hanson-Strong-Passphrase-2026!".toCharArray()
        val importResult = dstEngine.importEncryptedBackup(importPass, backupFile)
        assertTrue("Passphrase buffer must be wiped after import", importPass.all { it == '\u0000' })
        assertEquals(3, importResult.fileCount)
        assertEquals(exportResult.chunkCount, importResult.chunkCount)
        assertEquals(
            "# Hanson Encrypted Backup\n한국어 UTF-8 백업 테스트",
            dstEngine.readFile("README.md").getOrThrow()
        )
        assertEquals(
            "object Config { const val VERSION = 670 }",
            dstEngine.readFile("src/Config.kt").getOrThrow()
        )
        assertEquals(largePayload, File(dstEngine.workspaceRoot, "data/large.log").readText(StandardCharsets.UTF_8))

        // Wrong passphrase must fail closed with SecurityException and NOT mutate target workspace
        val wrongTarget = MobileWorkspaceEngine(tempFolder.newFolder("ws_backup_wrong"))
        try {
            wrongTarget.importEncryptedBackup("Wrong-Passphrase!".toCharArray(), backupFile)
            fail("Wrong passphrase must throw SecurityException")
        } catch (_: SecurityException) {
            // expected
        }
        assertTrue("Failed import must not mutate workspace", wrongTarget.listFiles().isEmpty())

        // Single-bit ciphertext tamper must fail closed with SecurityException
        val tamperedFile = File(tempFolder.root, "tampered.hansonbackup")
        val tamperedBytes = rawBytes.copyOf()
        val tamperIdx = tamperedBytes.size - 24
        tamperedBytes[tamperIdx] = (tamperedBytes[tamperIdx].toInt() xor 0x01).toByte()
        tamperedFile.writeBytes(tamperedBytes)

        val tamperTarget = MobileWorkspaceEngine(tempFolder.newFolder("ws_backup_tamper"))
        try {
            tamperTarget.importEncryptedBackup("Hanson-Strong-Passphrase-2026!".toCharArray(), tamperedFile)
            fail("Tampered ciphertext must throw SecurityException")
        } catch (_: SecurityException) {
            // expected
        }
        assertTrue("Tampered import must not mutate workspace", tamperTarget.listFiles().isEmpty())
    }

    // =========================================================================
    // 6. PhoneControlGate & WirelessAdbSelfPairHelper
    // =========================================================================

    @Test
    fun phoneControlGate_classifiesThreeTiersAndEnforcesPromptPolicy() {
        // Tier 1: READ_ONLY_QUIET
        listOf("snapshot", "dump_xml", "list_apps", "read_file README.md", "grep TODO", "preflight").forEach { cmd ->
            assertEquals("Expected READ_ONLY_QUIET for '$cmd'", PhoneControlTier.READ_ONLY_QUIET, PhoneControlGate.classifyCommandTier(cmd))
            assertFalse(PhoneControlGate.requiresPrompt(cmd, sessionApproved = false))
            assertFalse(PhoneControlGate.requiresPrompt(cmd, sessionApproved = true))
        }

        // Tier 2: PER_CHAT_CONSENT
        listOf("tap 500 500", "open_app com.kakao.talk", "write_file notes.md", "scroll_no_fling down").forEach { cmd ->
            assertEquals("Expected PER_CHAT_CONSENT for '$cmd'", PhoneControlTier.PER_CHAT_CONSENT, PhoneControlGate.classifyCommandTier(cmd))
            assertTrue("Must prompt before session consent for '$cmd'", PhoneControlGate.requiresPrompt(cmd, sessionApproved = false))
            assertFalse("Must allow after session consent for '$cmd'", PhoneControlGate.requiresPrompt(cmd, sessionApproved = true))
        }

        // Tier 3: DESTRUCTIVE_ASK_EVERY_TIME (including shell metacharacter attempts!)
        listOf("rm -rf workspace", "pm uninstall com.demo", "call_phone 01012345678", "send_sms", "결제 진행", "snapshot; id").forEach { cmd ->
            assertEquals("Expected DESTRUCTIVE_ASK_EVERY_TIME for '$cmd'", PhoneControlTier.DESTRUCTIVE_ASK_EVERY_TIME, PhoneControlGate.classifyCommandTier(cmd))
            assertTrue("Destructive command must prompt even before session approval", PhoneControlGate.requiresPrompt(cmd, sessionApproved = false))
            assertTrue("Destructive command must ALWAYS prompt even after session approval", PhoneControlGate.requiresPrompt(cmd, sessionApproved = true))
        }
    }

    @Test
    fun wirelessAdbSelfPairHelper_allowsLoopbackAndRfc1918AndRejectsPublicOrUnsafeInputs() {
        val validEndpoints = listOf(
            "127.0.0.1:37123",
            "localhost:41234",
            "[::1]:37123",
            "192.168.0.15:37891",
            "10.0.0.42:40001",
            "172.16.1.9:35000",
            "172.31.255.1:45555"
        )
        for (ep in validEndpoints) {
            val validation = WirelessAdbSelfPairHelper.validatePairingRequest(ep, "482913")
            assertTrue("Expected valid loopback/RFC1918 endpoint: $ep (${validation.errorKo})", validation.valid)
            assertEquals("482913", validation.sanitizedPairingCode)
        }

        val stateReady = WirelessAdbSelfPairHelper.buildBootstrapState("192.168.1.42:37891", "482913")
        assertEquals(WirelessAdbPairPhase.PAIRING, stateReady.phase)
        assertTrue(stateReady.endpointValid)
        assertTrue(stateReady.pairingCodeValid)
        assertEquals("192.168.1.42:37891", stateReady.pairedHostPort)
        assertTrue(stateReady.guideStepsKo.isNotEmpty())

        val stateMissingCode = WirelessAdbSelfPairHelper.buildBootstrapState("192.168.1.42:37891", "123")
        assertEquals(WirelessAdbPairPhase.AWAITING_PAIR_CODE, stateMissingCode.phase)
        assertTrue(stateMissingCode.endpointValid)
        assertFalse(stateMissingCode.pairingCodeValid)

        // Reject public/routable IPs, link-local, domains, bad ports, and shell metacharacters
        val rejectedEndpoints = listOf(
            "8.8.8.8:37123",
            "1.1.1.1:5555",
            "172.15.0.1:37123",
            "172.32.0.1:37123",
            "169.254.1.2:37123",
            "0.0.0.0:37123",
            "evil.example.com:37123",
            "192.168.1.2:80",
            "192.168.1.2:70000",
            "192.168.1.2:37123;id",
            "127.0.0.1:37123 && whoami"
        )
        for (badEp in rejectedEndpoints) {
            val res = WirelessAdbSelfPairHelper.validatePairingRequest(badEp, "482913")
            assertFalse("Endpoint '$badEp' must be rejected", res.valid)
            assertEquals(
                WirelessAdbPairPhase.FAILED,
                WirelessAdbSelfPairHelper.buildBootstrapState(badEp, "482913").phase
            )
        }

        val rejectedCodes = listOf("", "12345", "1234567", "12ab56", "482913;id", "482 913")
        for (badCode in rejectedCodes) {
            assertFalse(
                "Pairing code '$badCode' must be rejected",
                WirelessAdbSelfPairHelper.validatePairingCode(badCode).first
            )
        }
    }
}
