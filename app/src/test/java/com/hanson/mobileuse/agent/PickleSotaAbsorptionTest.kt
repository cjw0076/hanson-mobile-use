package com.hanson.mobileuse.agent

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.accessibility.IndexedUiElement
import com.hanson.mobileuse.workspace.AgentSessionStore
import com.hanson.mobileuse.workspace.AgentWorkMode
import com.hanson.mobileuse.workspace.ComposerCommandParser
import com.hanson.mobileuse.workspace.MobileWorkspaceEngine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.GeneralSecurityException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Hanson Mobile Agent Studio v5.0 — Comprehensive Unit & Integration Verification Suite
 * covering Pickle (`pickle.com`) and 2025–2026 SOTA mobile agent absorptions:
 * - BlindCredentialVault (AES-256-GCM encryption + zero-plaintext redaction)
 * - FinancialAndTakeoverGuard (Price Drift + Non-Bypass CAPTCHA/2FA Human Takeover)
 * - ProactiveWatcherEngine (Trigger matching + sold-out suppression + persistence)
 * - PickleA2AScheduleNegotiator (Local busy filtering + JSON-RPC 2.0 proposal)
 * - NotetakerScratchpad (Cross-app extraction + redaction + prompt block)
 * - MetaCognitiveLoopBreaker & ContextorCompactor
 * - AppReverseEngineeringEngine.detectSurfaceCapabilities & ActionTrajectoryVerifierAndHealer
 * - Harness 5 new tools + Engine `@vault`/`@notes`/`@watchers` & `/vault`/`/watch`/`/a2a`/`/notes`
 */
class PickleSotaAbsorptionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDirRoot: File
    private lateinit var workspaceDir: File
    private lateinit var dummyContext: Context
    private lateinit var permissionHub: PermissionHubManager
    private lateinit var workspaceEngine: MobileWorkspaceEngine
    private lateinit var sessionStore: AgentSessionStore
    private lateinit var blindVault: BlindCredentialVault
    private lateinit var watcherEngine: ProactiveWatcherEngine
    private lateinit var scratchpad: NotetakerScratchpad
    private lateinit var harness: AgentToolExecutionHarness
    private lateinit var engine: MobileUseAgentEngine

    private val testMasterKey = ByteArray(32) { idx -> (idx + 17).toByte() }

    @Before
    fun setUp() {
        val root = tempFolder.newFolder("sota_v5_root")
        filesDirRoot = File(root, "files").apply { mkdirs() }
        workspaceDir = File(root, "workspace").apply { mkdirs() }
        val sessionsFile = File(filesDirRoot, "sessions.json")
        val vaultFile = File(filesDirRoot, "blind_vault.json")
        val watcherFile = File(filesDirRoot, "proactive_watchers.json")
        val padFile = File(filesDirRoot, "notetaker_scratchpad.json")

        val prefsMap = ConcurrentHashMap<String, Any>()
        val dummyPrefs = object : SharedPreferences {
            override fun getAll(): MutableMap<String, *> = prefsMap
            override fun getString(key: String?, defValue: String?): String? = (prefsMap[key] as? String) ?: defValue
            override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
                (prefsMap[key] as? MutableSet<String>) ?: defValues
            override fun getInt(key: String?, defValue: Int): Int = (prefsMap[key] as? Int) ?: defValue
            override fun getLong(key: String?, defValue: Long): Long = (prefsMap[key] as? Long) ?: defValue
            override fun getFloat(key: String?, defValue: Float): Float = (prefsMap[key] as? Float) ?: defValue
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = (prefsMap[key] as? Boolean) ?: defValue
            override fun contains(key: String?): Boolean = prefsMap.containsKey(key)
            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
                override fun putString(k: String?, v: String?): SharedPreferences.Editor {
                    if (k != null && v != null) prefsMap[k] = v; return this
                }
                override fun putStringSet(k: String?, v: MutableSet<String>?): SharedPreferences.Editor {
                    if (k != null && v != null) prefsMap[k] = v; return this
                }
                override fun putInt(k: String?, v: Int): SharedPreferences.Editor {
                    if (k != null) prefsMap[k] = v; return this
                }
                override fun putLong(k: String?, v: Long): SharedPreferences.Editor {
                    if (k != null) prefsMap[k] = v; return this
                }
                override fun putFloat(k: String?, v: Float): SharedPreferences.Editor {
                    if (k != null) prefsMap[k] = v; return this
                }
                override fun putBoolean(k: String?, v: Boolean): SharedPreferences.Editor {
                    if (k != null) prefsMap[k] = v; return this
                }
                override fun remove(k: String?): SharedPreferences.Editor { prefsMap.remove(k); return this }
                override fun clear(): SharedPreferences.Editor { prefsMap.clear(); return this }
                override fun commit(): Boolean = true
                override fun apply() {}
            }
            override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        }

        dummyContext = object : ContextWrapper(null) {
            override fun getPackageName(): String = "com.hanson.mobileuse"
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = filesDirRoot
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = dummyPrefs
        }

        permissionHub = PermissionHubManager(dummyContext)
        workspaceEngine = MobileWorkspaceEngine(workspaceDir).also { it.initWorkspace() }
        sessionStore = AgentSessionStore(sessionsFile)
        blindVault = BlindCredentialVault(vaultFile, testMasterKey)
        watcherEngine = ProactiveWatcherEngine(watcherFile)
        scratchpad = NotetakerScratchpad(padFile)
        harness = AgentToolExecutionHarness(dummyContext, workspaceEngine).also {
            it.attachSotaModules(blindVault, watcherEngine, scratchpad)
        }
        engine = MobileUseAgentEngine(
            context = dummyContext,
            permissionHub = permissionHub,
            workspaceEngine = workspaceEngine,
            sessionStore = sessionStore,
            blindVault = blindVault,
            watcherEngine = watcherEngine,
            scratchpad = scratchpad
        )
    }

    @After
    fun tearDown() {
        engine.stopRun("test teardown")
    }

    private fun makeEl(
        index: Int,
        shortClass: String = "Button",
        text: String = "",
        contentDesc: String = "",
        resourceId: String = "id_$index",
        packageName: String = "com.sample.app",
        clickable: Boolean = true,
        editable: Boolean = false,
        focused: Boolean = false,
        scrollable: Boolean = false,
        left: Int = 100,
        top: Int = 200 + index * 120,
        right: Int = 500,
        bottom: Int = 290 + index * 120
    ): IndexedUiElement = IndexedUiElement(
        index = index,
        shortClass = shortClass,
        className = "android.widget.$shortClass",
        packageName = packageName,
        text = text,
        contentDesc = contentDesc,
        resourceId = resourceId,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        centerX = (left + right) / 2,
        centerY = (top + bottom) / 2,
        normX = (((left + right) / 2) * 1000) / 1080,
        normY = (((top + bottom) / 2) * 1000) / 2400,
        clickable = clickable,
        longClickable = false,
        scrollable = scrollable,
        editable = editable,
        checkable = false,
        checked = false,
        focused = focused,
        enabled = true,
        windowType = "APPLICATION",
        windowLayer = 0
    )

    private fun makeSnap(
        pkg: String = "com.sample.app",
        title: String = "Sample Screen",
        elements: List<IndexedUiElement>
    ): AtomicSnapshot = AtomicSnapshot(
        screenWidth = 1080,
        screenHeight = 2400,
        activePackage = pkg,
        activeWindowTitle = title,
        indexedElements = elements
    )

    // =========================================================================
    // 1. BlindCredentialVault
    // =========================================================================

    @Test
    fun blindCredentialVault_encryptsOnDiskAndNeverExposesPlaintext() {
        val vaultFile = File(filesDirRoot, "v_enc.json")
        val vault = BlindCredentialVault(vaultFile, testMasterKey)
        val secret = "SuperSecret!Pass#2026"

        val summary = vault.putSecret("portal_pw", "포털 로그인 비밀번호", secret, VaultSecretCategory.LOGIN)
        assertEquals("vault://portal_pw", summary.handle)
        assertFalse(summary.maskedPreview.contains(secret))

        val diskContent = vaultFile.readText()
        assertFalse("Disk file must never store plaintext secret", diskContent.contains(secret))

        // Reload with same master key -> resolve works locally
        val reloaded = BlindCredentialVault(vaultFile, testMasterKey)
        val list = reloaded.listHandles(VaultSecretCategory.LOGIN)
        assertEquals(1, list.size)
        val resolvedByHandle = reloaded.resolvePlaintextForBlindInjection("vault://portal_pw")
        assertNotNull(resolvedByHandle)
        assertEquals(secret, resolvedByHandle?.second)
        val resolvedByRawKey = reloaded.resolvePlaintextForBlindInjection("portal_pw")
        assertEquals(secret, resolvedByRawKey?.second)
    }

    @Test
    fun blindCredentialVault_deleteAndRedactSensitiveTextWorkAccurately() {
        val vaultFile = File(filesDirRoot, "v_del.json")
        val vault = BlindCredentialVault(vaultFile, testMasterKey)
        vault.putSecret("card_main", "주 결제 카드", "4532-8811-2233-4829", VaultSecretCategory.PAYMENT)
        vault.putSecret("api_tok", "API 토큰", "tok_live_987654321xyz", VaultSecretCategory.LOGIN)

        val redacted = vault.redactSensitiveText(
            "카드번호 4532-8811-2233-4829 및 토큰 tok_live_987654321xyz, 인증번호 482910 입력"
        )
        assertFalse(redacted.contains("4532-8811-2233-4829"))
        assertFalse(redacted.contains("tok_live_987654321xyz"))
        assertTrue(redacted.contains("REDACTED"))

        assertTrue(vault.deleteSecret("vault://card_main"))
        assertNull(vault.getHandleSummary("card_main"))
        assertEquals(1, vault.listHandles().size)
    }

    // =========================================================================
    // 2. FinancialAndTakeoverGuard
    // =========================================================================

    @Test
    fun financialGuard_extractsAmountAndTriggersPriceDriftOnIncrease() {
        val snap = makeSnap(
            pkg = "com.sample.shop",
            title = "주문서 결제",
            elements = listOf(
                makeEl(0, shortClass = "TextView", text = "총 결제 금액: 29,000원", clickable = false),
                makeEl(1, shortClass = "Button", text = "결제하기 (29,000원)", clickable = true)
            )
        )
        val extracted = FinancialAndTakeoverGuard.extractPrimaryCurrencyAmount(snap)
        assertTrue("Expected extracted currency to contain 29,000 but was $extracted", extracted.contains("29,000"))

        val commit = FinancialAndTakeoverGuard.assessBeforeAction(
            toolName = "click",
            args = JSONObject().put("element_index", 1),
            snapshot = snap,
            lastApprovedAmount = null
        )
        assertEquals(HighStakesKind.FINANCIAL_COMMIT, commit.kind)

        val drift = FinancialAndTakeoverGuard.assessBeforeAction(
            toolName = "click",
            args = JSONObject().put("element_index", 1),
            snapshot = snap,
            lastApprovedAmount = "24,000원"
        )
        assertEquals(HighStakesKind.PRICE_DRIFT_ALERT, drift.kind)
        assertTrue(drift.reasonKo.contains("24,000원"))
        assertTrue(drift.reasonKo.contains("29,000"))
    }

    @Test
    fun financialGuard_detectsNonBypassCaptchaAndTwoFactorSurfaces() {
        val captchaSnap = makeSnap(
            pkg = "com.sample.tickets",
            elements = listOf(
                makeEl(0, shortClass = "TextView", text = "Cloudflare Turnstile Verify you are human", clickable = false),
                makeEl(1, shortClass = "Button", text = "계속하기", clickable = true)
            )
        )
        val captchaAssessment = FinancialAndTakeoverGuard.assessBeforeAction(
            toolName = "click",
            args = JSONObject().put("element_index", 1),
            snapshot = captchaSnap,
            lastApprovedAmount = null
        )
        assertEquals(HighStakesKind.CAPTCHA_BOT_GATE, captchaAssessment.kind)

        val keypadSnap = makeSnap(
            pkg = "com.sample.bank",
            elements = listOf(
                makeEl(0, shortClass = "TextView", text = "안심 보안키패드 비밀번호 입력", clickable = false),
                makeEl(1, shortClass = "EditText", text = "", editable = true, clickable = true)
            )
        )
        val twoFaAssessment = FinancialAndTakeoverGuard.assessBeforeAction(
            toolName = "input_text",
            args = JSONObject().put("element_index", 1).put("text", "1234"),
            snapshot = keypadSnap,
            lastApprovedAmount = null
        )
        assertEquals(HighStakesKind.TWO_FACTOR_OR_BIOMETRIC, twoFaAssessment.kind)
    }

    @Test
    fun financialGuard_ignoresReadOnlyToolsAndNonCommitButtons() {
        val normalSnap = makeSnap(
            elements = listOf(
                makeEl(0, text = "장바구니 담기", clickable = true)
            )
        )
        val readCheck = FinancialAndTakeoverGuard.assessBeforeAction(
            toolName = "get_screen_state",
            args = JSONObject(),
            snapshot = normalSnap
        )
        assertEquals(HighStakesKind.NONE, readCheck.kind)

        val browseClick = FinancialAndTakeoverGuard.assessBeforeAction(
            toolName = "click",
            args = JSONObject().put("element_index", 0),
            snapshot = normalSnap
        )
        assertEquals(HighStakesKind.NONE, browseClick.kind)
    }

    // =========================================================================
    // 3. ProactiveWatcherEngine
    // =========================================================================

    @Test
    fun proactiveWatcherEngine_suppressesSoldOutAndTriggersOnAvailabilityWithPersistence() {
        val watchFile = File(filesDirRoot, "w_test.json")
        val wEngine = ProactiveWatcherEngine(watchFile)
        val rule = wEngine.addRule(
            title = "KTX 취소표 감시",
            targetAppOrUrl = "com.korail.talk",
            matchKeywords = listOf("서울", "부산"),
            mode = WatcherActionMode.HOLD_FOR_APPROVAL
        )

        val soldOutSnap = makeSnap(
            pkg = "com.korail.talk",
            elements = listOf(makeEl(0, text = "서울 -> 부산 KTX 103 매진", clickable = false))
        )
        val noHits = wEngine.evaluateSnapshot(soldOutSnap)
        assertTrue("Sold-out screen must NOT fire positive hit", noHits.isEmpty())

        val availableSnap = makeSnap(
            pkg = "com.korail.talk",
            elements = listOf(makeEl(0, text = "서울 -> 부산 KTX 103 예매가능 1석", clickable = true))
        )
        val hits = wEngine.evaluateSnapshot(availableSnap)
        assertEquals(1, hits.size)
        assertEquals(1, hits.first().rule.hitCount)

        // Pause prevents firing
        assertTrue(wEngine.toggleRule(rule.id, false))
        assertTrue(wEngine.evaluateSnapshot(availableSnap).isEmpty())

        // Persistence across reload + remove
        val reloaded = ProactiveWatcherEngine(watchFile)
        assertEquals(1, reloaded.listRules().size)
        assertFalse(reloaded.listRules().first().enabled)
        assertTrue(reloaded.removeRule(rule.id))
        assertTrue(reloaded.listRules().isEmpty())
    }

    // =========================================================================
    // 4. PickleA2AScheduleNegotiator
    // =========================================================================

    @Test
    fun pickleA2ANegotiator_findsNonOverlappingSlotAndHandlesConflict() {
        val slots = PickleA2AScheduleNegotiator.parseSlotsCsv("12:00-14:00, 18:30-21:00", "금요일")
        assertEquals(2, slots.size)
        assertEquals("금요일", slots.first().dayLabel)

        val agreed = PickleA2AScheduleNegotiator.negotiate(
            peerName = "민수",
            durationMinutes = 60,
            preferredArea = "성수역",
            myBusyCsv = "12:00-14:00",
            peerFreeCsv = "12:00-14:00, 18:30-21:00"
        )
        assertTrue(agreed.candidates.isNotEmpty())
        assertEquals("18:30", agreed.candidates.first().startHHmm)
        assertEquals("성수역", agreed.candidates.first().recommendedArea)
        assertEquals("pickle/propose_meeting_slots", agreed.a2aJsonRpcPayload.getString("method"))

        val conflict = PickleA2AScheduleNegotiator.negotiate(
            peerName = "민수",
            durationMinutes = 90,
            preferredArea = "강남역",
            myBusyCsv = "12:00-14:00, 18:00-22:00",
            peerFreeCsv = "12:30-13:30, 18:30-19:30"
        )
        assertTrue(conflict.candidates.isEmpty())
        assertTrue(conflict.summaryKo.contains("겹치는"))
    }

    // =========================================================================
    // 5. NotetakerScratchpad
    // =========================================================================

    @Test
    fun notetakerScratchpad_autoExtractsSafeEntitiesRedactsVaultSecretsAndFormatsPrompt() {
        blindVault.putSecret("pin_secret", "결제 PIN", "998877", VaultSecretCategory.PAYMENT)
        val snap = makeSnap(
            pkg = "com.sample.orders",
            elements = listOf(
                makeEl(0, shortClass = "TextView", text = "주문번호: ORD-884210 완료", clickable = false),
                makeEl(1, shortClass = "TextView", text = "결제금액 34,500원 · PIN 998877", clickable = false)
            )
        )
        val extracted = scratchpad.observeAndAutoExtract(snap, blindVault)
        assertTrue(extracted.any { it.key.contains("주문") && it.value == "ORD-884210" })
        assertTrue(extracted.any { it.key.contains("관측 금액") && it.value.contains("34,500") })

        val manual = scratchpad.put("메모", "사용자 입력값 998877", "manual", autoCaptured = false)
        assertEquals("메모", manual.key)
        assertNotNull(scratchpad.get("메모"))

        val prompt = scratchpad.toPromptBlock(600)
        assertTrue(prompt.contains("[Notetaker Working Memory Scratchpad]"))
        assertTrue(prompt.contains("ORD-884210"))

        assertTrue(scratchpad.remove("메모"))
        assertNull(scratchpad.get("메모"))
        scratchpad.clear()
        assertTrue(scratchpad.listEntries().isEmpty())
    }

    // =========================================================================
    // 6. MetaCognitiveLoopBreaker & ContextorCompactor
    // =========================================================================

    @Test
    fun metaCognitiveLoopBreaker_detectsStaleRepeatAndTwoStepOscillation() {
        val breaker = MetaCognitiveLoopBreaker()
        val snapA = makeSnap(
            title = "Screen A",
            elements = listOf(makeEl(0, text = "다음", clickable = true))
        )
        val snapB = makeSnap(
            title = "Screen B",
            elements = listOf(makeEl(0, text = "이전", clickable = true))
        )

        val d1 = breaker.recordAndDiagnose(snapA, "click", "{\"element_index\":0}", 0.0f)
        assertFalse(d1.isLooping)
        val d2 = breaker.recordAndDiagnose(snapA, "click", "{\"element_index\":0}", 0.0f)
        assertTrue(d2.isLooping)
        assertEquals(LoopPattern.STALE_ACTION_REPEAT, d2.pattern)

        breaker.reset()
        breaker.recordAndDiagnose(snapA, "click", "{\"element_index\":0}", 0.4f)
        breaker.recordAndDiagnose(snapB, "navigate_back", "{}", 0.4f)
        breaker.recordAndDiagnose(snapA, "click", "{\"element_index\":0}", 0.4f)
        val osc = breaker.recordAndDiagnose(snapB, "navigate_back", "{}", 0.4f)
        assertTrue(osc.isLooping)
        assertEquals(LoopPattern.TWO_STEP_OSCILLATION, osc.pattern)
    }

    @Test
    fun contextorCompactor_prioritizesInteractiveElementsAndRedactsSecrets() {
        blindVault.putSecret("secret_id", "비밀 ID", "TOP_SECRET_UID_77", VaultSecretCategory.IDENTITY)
        val snap = makeSnap(
            pkg = "com.sample.login",
            title = "로그인",
            elements = listOf(
                makeEl(0, shortClass = "TextView", text = "단순 안내 배너", clickable = false, editable = false, focused = false),
                makeEl(1, shortClass = "EditText", text = "TOP_SECRET_UID_77", clickable = true, editable = true, focused = true)
            )
        )
        val outline = ContextorCompactor.buildCompactOutline(snap, blindVault, maxElements = 1)
        assertFalse("Secret must be redacted in Contextor outline", outline.contains("TOP_SECRET_UID_77"))
        assertTrue("Focused editable element should rank first when maxElements=1", outline.contains("[1]"))
        assertTrue(outline.contains("REDACTED"))
    }

    // =========================================================================
    // 7. AppReverseEngineeringEngine & ActionTrajectoryVerifierAndHealer
    // =========================================================================

    @Test
    fun appReverseEngineering_detectsAppFunctionsAndSecuritySurfaceHints() {
        val (appFuncs, securityHints) = AppReverseEngineeringEngine.detectSurfaceCapabilities(
            serviceNames = listOf("com.sample.AppFunctionServiceImpl", "com.sample.NormalService"),
            manifestStrings = listOf(
                "androidx.appfunctions.schema.CalendarFunction",
                "FLAG_SECURE_WINDOW",
                "com.google.android.gms.safetynet.TurnstileActivity",
                "securekeypad_view"
            )
        )
        assertTrue(appFuncs.any { it.contains("AppFunction", ignoreCase = true) })
        assertTrue(securityHints.isNotEmpty())
    }

    @Test
    fun trajectoryVerifier_verifiesKoreanAndMaskedBlindInjectDeterministically() {
        val passwordSnap = makeSnap(
            elements = listOf(
                makeEl(0, shortClass = "EditText", text = "●●●●●●●●", editable = true, focused = true)
            )
        )
        assertTrue(ActionTrajectoryVerifierAndHealer.verifyInputTextDeterministic("••••", passwordSnap))

        val koreanSnap = makeSnap(
            elements = listOf(
                makeEl(0, shortClass = "EditText", text = "강남역 맛집", editable = true, focused = true)
            )
        )
        assertTrue(ActionTrajectoryVerifierAndHealer.verifyInputTextDeterministic("강남역 맛집", koreanSnap))

        val healer = ActionTrajectoryVerifierAndHealer(dummyContext)
        val report = healer.evaluateTransitionOnly(
            toolName = "vault_blind_inject",
            args = JSONObject().put("handle", "vault://naver_login"),
            initialOutcomeSucceeded = true,
            pre = makeSnap(elements = emptyList()),
            post = passwordSnap
        )
        assertTrue(report.verifiedSuccess)
        assertTrue(report.diagnosisKo.contains("vault://naver_login"))
    }

    // =========================================================================
    // 8. Harness Tools & Engine Commands / Mentions
    // =========================================================================

    @Test
    fun harness_executesAllFivePickleToolsWithoutLeakingSecrets() {
        val secretVal = "TopSecretCardCode!8821"
        blindVault.putSecret("sample_login", "샘플 로그인", secretVal, VaultSecretCategory.LOGIN)

        val listRes = harness.execute("vault_list_handles", JSONObject(), null)
        assertTrue(listRes.succeeded)
        assertTrue(listRes.outputText.contains("vault://sample_login"))
        assertFalse(listRes.outputText.contains(secretVal))

        val injectWithoutA11y = harness.execute(
            "vault_blind_inject",
            JSONObject().put("handle", "vault://sample_login"),
            makeSnap(elements = listOf(makeEl(0, shortClass = "EditText", editable = true)))
        )
        assertFalse(
            "Inactive Accessibility must NEVER falsely report blind-inject success",
            injectWithoutA11y.succeeded
        )
        assertNull("Blocked blind inject must not emit a fake success receipt", injectWithoutA11y.receipt)
        assertTrue(
            "Blocked blind inject must report explicit ACCESSIBILITY_UNAVAILABLE status",
            injectWithoutA11y.outputText.contains("ACCESSIBILITY_UNAVAILABLE")
        )
        assertFalse(
            "Blocked blind inject output must NEVER leak secret",
            injectWithoutA11y.outputText.contains(secretVal)
        )

        val watchAdd = harness.execute(
            "watcher_manage",
            JSONObject()
                .put("action", "add")
                .put("title", "콘서트 취소표")
                .put("match_keywords", "예매가능, R석")
                .put("target_app_or_url", "com.sample.ticket"),
            null
        )
        assertTrue(watchAdd.succeeded)

        val watchEval = harness.execute(
            "watcher_manage",
            JSONObject().put("action", "evaluate"),
            makeSnap(
                pkg = "com.sample.ticket",
                elements = listOf(makeEl(0, text = "R석 예매가능 2자리"))
            )
        )
        assertTrue(watchEval.succeeded)
        assertTrue(watchEval.outputText.contains("콘서트 취소표"))

        val a2aRes = harness.execute(
            "a2a_negotiate_schedule",
            JSONObject()
                .put("peer_name", "Alex")
                .put("duration_minutes", 45)
                .put("preferred_area", "홍대입구역")
                .put("my_busy_slots", "12:00-14:00")
                .put("peer_free_slots", "18:30-21:00"),
            null
        )
        assertTrue(a2aRes.succeeded)
        assertTrue(a2aRes.outputText.contains("pickle/propose_meeting_slots"))

        val notePut = harness.execute(
            "scratchpad_note",
            JSONObject().put("action", "put").put("key", "게이트").put("value", "B12 · 비밀=$secretVal"),
            null
        )
        assertTrue(notePut.succeeded)
        assertFalse("Scratchpad output must redact vault secret", notePut.outputText.contains(secretVal))
        assertTrue(notePut.outputText.contains("B12"))
    }

    @Test
    fun engine_injectsVaultNotesWatchersMentionsAndHandlesNewSlashCommands() {
        val secretPw = "NeverShowInPrompt!2026"
        blindVault.putSecret("bank_pin", "은행 핀번호", secretPw, VaultSecretCategory.PAYMENT)
        watcherEngine.addRule("배송 추적", "com.sample.delivery", listOf("배송완료"))
        scratchpad.put("송장번호", "TRK-55910", "com.sample.delivery", autoCaptured = false)

        val parsed = ComposerCommandParser.parse(
            "확인해줘 @vault @notes @watchers",
            AgentWorkMode.AUTO
        )
        assertTrue(parsed.mentions.containsAll(listOf("@vault", "@notes", "@watchers")))

        val enriched = engine.buildContextualPrompt(parsed.cleanPrompt, parsed, null)
        assertFalse("Prompt context must NEVER include vault plaintext", enriched.contains(secretPw))
        assertTrue(enriched.contains("vault://bank_pin"))
        assertTrue(enriched.contains("TRK-55910"))
        assertTrue(enriched.contains("배송 추적"))

        // Exercise /vault, /watch, /a2a, /notes synchronously
        engine.startRun("/vault")
        assertTrue(engine.chatMessages.value.any { it.toolBadge == "VAULT" && it.text.contains("vault://bank_pin") && !it.text.contains(secretPw) })

        engine.startRun("/watch 항공권 | 특가오픈, 좌석있음 | com.sample.air")
        assertTrue(engine.chatMessages.value.any { it.toolBadge == "WATCH" && it.text.contains("항공권") })

        engine.startRun("/a2a Mina")
        assertTrue(engine.chatMessages.value.any { it.toolBadge == "A2A" && it.text.contains("Mina") })

        engine.startRun("/notes")
        assertTrue(engine.chatMessages.value.any { it.toolBadge == "NOTES" && it.text.contains("TRK-55910") })
    }

    @Test
    fun blindCredentialVault_rejectsInvalidInputsAndHandlesMissingLookups() {
        val vault = BlindCredentialVault(File(filesDirRoot, "v_invalid.json"), testMasterKey)
        assertNull(vault.resolvePlaintextForBlindInjection("vault://nonexistent"))
        assertFalse(vault.deleteSecret("vault://nonexistent"))
        assertNull(vault.getHandleSummary("missing_key"))

        val rejectedBlank = runCatching {
            vault.putSecret("   ", "빈 키", "secret", VaultSecretCategory.LOGIN)
        }.isFailure
        val rejectedEmptySecret = runCatching {
            vault.putSecret("valid_key", "빈 값", "", VaultSecretCategory.LOGIN)
        }.isFailure
        assertTrue(rejectedBlank)
        assertTrue(rejectedEmptySecret)
    }

    @Test
    fun proactiveWatcherEngine_handlesDisabledRuleToggleAndMissingRule() {
        val wEngine = ProactiveWatcherEngine(File(filesDirRoot, "w_missing.json"))
        assertFalse(wEngine.toggleRule("no_such_id", true))
        assertFalse(wEngine.removeRule("no_such_id"))

        val added = wEngine.addRule(
            title = "특가 오픈",
            targetAppOrUrl = "com.sample.store",
            matchKeywords = listOf("오픈"),
            mode = WatcherActionMode.NOTIFY_ONLY
        )
        assertEquals(WatcherActionMode.NOTIFY_ONLY, added.mode)
        assertTrue(wEngine.toggleRule(added.id, false))
        assertFalse(wEngine.listRules().first().enabled)
    }

    @Test
    fun pickleA2ANegotiator_prefersPrimeEveningSlotOverLunchSlot() {
        val outcome = PickleA2AScheduleNegotiator.negotiate(
            peerName = "Jihoon",
            durationMinutes = 60,
            preferredArea = "판교역",
            myBusyCsv = "09:00-11:00",
            peerFreeCsv = "11:30-13:30, 18:30-20:30"
        )
        assertTrue(outcome.candidates.size >= 2)
        assertEquals("18:30", outcome.candidates.first().startHHmm)
        assertTrue(outcome.candidates.first().score >= outcome.candidates[1].score)
    }

    @Test
    fun notetakerScratchpad_autoExtractsCouponCodesAndRespectsMaxChars() {
        val snap = makeSnap(
            pkg = "com.sample.gift",
            elements = listOf(
                makeEl(0, shortClass = "TextView", text = "쿠폰 코드 AB12-CD34 적용됨", clickable = false),
                makeEl(1, shortClass = "TextView", text = "주문번호 ORD-778899", clickable = false)
            )
        )
        val extracted = scratchpad.observeAndAutoExtract(snap, blindVault)
        assertTrue(extracted.any { it.value == "AB12-CD34" })

        val truncated = scratchpad.toPromptBlock(maxChars = 65)
        assertTrue(truncated.length <= 66)
    }

    @Test
    fun metaCognitiveLoopBreaker_resetsAndAllowsNormalProgress() {
        val breaker = MetaCognitiveLoopBreaker()
        val snap1 = makeSnap(title = "Step 1", elements = listOf(makeEl(0, text = "다음")))
        val snap2 = makeSnap(title = "Step 2", elements = listOf(makeEl(1, text = "확인")))
        val snap3 = makeSnap(title = "Step 3", elements = listOf(makeEl(2, text = "완료")))

        breaker.recordAndDiagnose(snap1, "click", "{\"element_index\":0}", 0.0f)
        val loop = breaker.recordAndDiagnose(snap1, "click", "{\"element_index\":0}", 0.0f)
        assertTrue(loop.isLooping)

        breaker.reset()
        val ok1 = breaker.recordAndDiagnose(snap1, "click", "{\"element_index\":0}", 0.5f)
        val ok2 = breaker.recordAndDiagnose(snap2, "click", "{\"element_index\":1}", 0.5f)
        val ok3 = breaker.recordAndDiagnose(snap3, "click", "{\"element_index\":2}", 0.5f)
        assertFalse(ok1.isLooping)
        assertFalse(ok2.isLooping)
        assertFalse(ok3.isLooping)
    }

    @Test
    fun harness_blindInjectFailsGracefullyOnUnknownOrMissingHandle() {
        val missingArg = harness.execute("vault_blind_inject", JSONObject(), null)
        assertFalse(missingArg.succeeded)

        val unknownHandle = harness.execute(
            "vault_blind_inject",
            JSONObject().put("handle", "vault://missing_key"),
            null
        )
        assertFalse(unknownHandle.succeeded)
        assertTrue(unknownHandle.outputText.contains("vault://missing_key"))
    }

    @Test
    fun toolSpecRegistry_andSlashCommandsExpose42Tools13CommandsAndTakeoverState() {
        val specNames = ToolSpecRegistry.allSpecs.map { it.name }
        assertEquals(42, specNames.size)
        assertTrue(specNames.containsAll(listOf(
            "vault_blind_inject",
            "vault_list_handles",
            "watcher_manage",
            "a2a_negotiate_schedule",
            "scratchpad_note"
        )))
        assertEquals(42, ToolSpecRegistry.toGeminiToolsJson().getJSONObject(0).getJSONArray("functionDeclarations").length())
        assertEquals(42, ToolSpecRegistry.toGeminiInteractionsToolsJson().length())
        assertEquals(13, ComposerCommandParser.defaultSlashCommands.size)
        assertEquals("AWAITING_TAKEOVER", AgentRunStatus.AWAITING_TAKEOVER.name)
        assertTrue(HighStakesKind.values().any { it == HighStakesKind.SECURE_SURFACE_BLOCKED })
    }

    // =========================================================================
    // 9. Studio v5.1 Security & Correctness Hardening Tests (All 5 Audit Gaps)
    // =========================================================================

    @Test
    fun v51_gap1_aesGcmAadRejectsCrossHandleCiphertextSwapAndDowngradeTampering() {
        val dir = tempFolder.newFolder("v51_aad_swap")
        val vaultFile = File(dir, "vault_aad.json")
        val vault = BlindCredentialVault(vaultFile, testMasterKey)

        vault.putSecret("alpha_key", "알파 계정", "AlphaSecret#1111", VaultSecretCategory.LOGIN)
        vault.putSecret("beta_key", "베타 계정", "BetaSecret#2222", VaultSecretCategory.PAYMENT)

        val root = JSONObject(vaultFile.readText(StandardCharsets.UTF_8))
        assertEquals(BlindCredentialVault.VAULT_SCHEMA_VERSION, root.getInt("schemaVersion"))
        assertTrue(root.getInt("kdfIterations") >= 210_000)

        val entries = root.getJSONArray("entries")
        assertEquals(2, entries.length())
        val e0 = entries.getJSONObject(0)
        val e1 = entries.getJSONObject(1)
        assertEquals(BlindCredentialVault.VAULT_SCHEMA_VERSION, e0.getInt("aadVersion"))
        assertEquals(BlindCredentialVault.VAULT_SCHEMA_VERSION, e1.getInt("aadVersion"))

        // Tamper 1: Swap IV + ciphertext between alpha_key and beta_key
        val swappedFile = File(dir, "vault_swapped.json")
        val swappedRoot = JSONObject(root.toString())
        val swappedEntries = swappedRoot.getJSONArray("entries")
        val s0 = swappedEntries.getJSONObject(0)
        val s1 = swappedEntries.getJSONObject(1)
        val origIv0 = s0.getString("ivB64")
        val origCt0 = s0.getString("ctB64")
        s0.put("ivB64", s1.getString("ivB64"))
        s0.put("ctB64", s1.getString("ctB64"))
        s1.put("ivB64", origIv0)
        s1.put("ctB64", origCt0)
        swappedFile.writeText(swappedRoot.toString(2), StandardCharsets.UTF_8)

        val swappedVault = BlindCredentialVault(swappedFile, testMasterKey)
        val swapError = runCatching {
            swappedVault.resolvePlaintextForBlindInjection("vault://alpha_key")
        }.exceptionOrNull()
        assertNotNull("Swapped ciphertext across handles MUST fail AES-GCM AAD verification", swapError)
        assertTrue(
            "Expected AEADBadTagException / GeneralSecurityException, got $swapError",
            swapError is AEADBadTagException || swapError is GeneralSecurityException
        )
        assertTrue(
            "Integrity failure must be logged in audit ledger",
            swappedVault.getAuditEntries().any { it.action == "DECRYPT_INTEGRITY_FAILED" && it.outcome == "AEAD_AUTH_FAILED" }
        )

        // Tamper 2: Attempt aadVersion downgrade (2 -> 1) on a v2 ciphertext
        val downgradedFile = File(dir, "vault_downgraded.json")
        val downgradedRoot = JSONObject(root.toString())
        downgradedRoot.put("schemaVersion", 1)
        downgradedRoot.getJSONArray("entries").getJSONObject(0).put("aadVersion", 1)
        downgradedFile.writeText(downgradedRoot.toString(2), StandardCharsets.UTF_8)

        val downgradedVault = BlindCredentialVault(downgradedFile, testMasterKey)
        val downgradeError = runCatching {
            downgradedVault.resolvePlaintextForBlindInjection("vault://alpha_key")
        }.exceptionOrNull()
        assertNotNull("Downgrade tampering on v2 ciphertext MUST still fail GCM tag check", downgradeError)
        assertTrue(downgradeError is GeneralSecurityException)
    }

    @Test
    fun v51_gap1_legacyV1VaultEntryRemainsBackwardsCompatibleForMigration() {
        val dir = tempFolder.newFolder("v51_legacy_v1")
        val vaultFile = File(dir, "legacy_vault.json")
        val salt = ByteArray(16) { (it + 3).toByte() }
        File(dir, ".vault_salt").writeBytes(salt)

        // Encrypt a genuine legacy v1 entry without AAD using the v5.1 KDF salt + master key
        val seedChars = Base64.getEncoder().encodeToString(testMasterKey).toCharArray()
        val pbe = PBEKeySpec(seedChars, salt, BlindCredentialVault.PBKDF2_ITERATIONS, 256)
        val derivedKey = SecretKeySpec(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(pbe).encoded,
            "AES"
        )
        val iv = ByteArray(12) { (it + 9).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derivedKey, GCMParameterSpec(128, iv))
        val legacySecret = "LegacyPass#V1_2025"
        val ct = cipher.doFinal(legacySecret.toByteArray(StandardCharsets.UTF_8))

        val legacyRoot = JSONObject().apply {
            put("schemaVersion", 1)
            put(
                "entries",
                JSONArray().put(
                    JSONObject().apply {
                        put("key", "legacy_login")
                        put("label", "레거시 로그인")
                        put("category", VaultSecretCategory.LOGIN.name)
                        put("maskedPreview", "••••••••")
                        put("updatedAtMs", 1700000000000L)
                        put("ivB64", Base64.getEncoder().encodeToString(iv))
                        put("ctB64", Base64.getEncoder().encodeToString(ct))
                    }
                )
            )
        }
        vaultFile.writeText(legacyRoot.toString(2), StandardCharsets.UTF_8)

        val loaded = BlindCredentialVault(vaultFile, testMasterKey)
        val resolved = loaded.resolvePlaintextForBlindInjection("vault://legacy_login")
        assertNotNull("Legacy v1 entry must still decrypt cleanly", resolved)
        assertEquals(legacySecret, resolved?.second)

        // Redaction must also index & redact the legacy entry cleanly
        val redacted = loaded.redactSensitiveText("Log leaked $legacySecret here")
        assertFalse(redacted.contains(legacySecret))
        assertTrue(redacted.contains("[REDACTED:vault://legacy_login]"))
    }

    @Test
    fun v51_gap2_pbkdf2AtLeast210kAndEnvMasterKeyOverrideAndFilePermissions() {
        assertTrue(
            "OWASP PBKDF2-HMAC-SHA256 requires at least 210,000 iterations",
            BlindCredentialVault.PBKDF2_ITERATIONS >= 210_000
        )

        // 1. HANSON_VAULT_MASTER_KEY env override: never writes .vault_local_key & isolates keys
        val envDir = tempFolder.newFolder("v51_env_key")
        val envVaultFile = File(envDir, "env_vault.json")
        val envPassphrase = "Hanson-Env-Master-Key-2026!#Strong"
        val envVault = BlindCredentialVault(envVaultFile) { envPassphrase }
        val secret = "EnvProtectedSecret!9981"

        envVault.putSecret("env_api", "환경키 시크릿", secret, VaultSecretCategory.API_TOKEN)
        assertEquals("ENV_MASTER_KEY", envVault.activeKeySourceForTesting)
        assertFalse(
            "When HANSON_VAULT_MASTER_KEY is set, .vault_local_key MUST NOT be created on disk",
            File(envDir, ".vault_local_key").exists()
        )
        assertFalse("toString() must never leak env passphrase", envVault.toString().contains(envPassphrase))
        assertFalse("Vault file must never contain env passphrase", envVaultFile.readText().contains(envPassphrase))

        val reloadedSameEnv = BlindCredentialVault(envVaultFile) { envPassphrase }
        assertEquals(secret, reloadedSameEnv.resolvePlaintextForBlindInjection("vault://env_api")?.second)

        val reloadedWrongEnv = BlindCredentialVault(envVaultFile) { "Wrong-Env-Master-Key" }
        val wrongKeyErr = runCatching {
            reloadedWrongEnv.resolvePlaintextForBlindInjection("vault://env_api")
        }.exceptionOrNull()
        assertNotNull("Wrong HANSON_VAULT_MASTER_KEY must fail decryption", wrongKeyErr)
        assertTrue(wrongKeyErr is GeneralSecurityException)

        // 2. Local fallback key file hardening (owner-only permissions)
        val localDir = tempFolder.newFolder("v51_local_key")
        val localVaultFile = File(localDir, "local_vault.json")
        val localVault = BlindCredentialVault(localVaultFile) { null }
        localVault.putSecret("local_pw", "로컬 키 테스트", "LocalOnlySecret!44", VaultSecretCategory.LOGIN)
        assertEquals("LOCAL_KEY_FILE", localVault.activeKeySourceForTesting)

        val localKeyFile = File(localDir, ".vault_local_key")
        val saltFile = File(localDir, ".vault_salt")
        assertTrue(localKeyFile.isFile)
        assertTrue(saltFile.isFile)
        assertTrue("Newly generated salt must be >= 32 bytes", saltFile.length() >= 32L)
        assertTrue(localKeyFile.canRead() && localKeyFile.canWrite())

        // On POSIX filesystems assert strict 0600 (rw-------) permissions
        runCatching {
            val perms = Files.getPosixFilePermissions(localKeyFile.toPath())
            assertEquals("rw-------", PosixFilePermissions.toString(perms))
            val vaultPerms = Files.getPosixFilePermissions(localVaultFile.toPath())
            assertEquals("rw-------", PosixFilePermissions.toString(vaultPerms))
        }
    }

    @Test
    fun v51_gap3_heapSafeRedactionDoesNotDecryptVaultEntriesAndZeroesCharBuffers() {
        val dir = tempFolder.newFolder("v51_redaction_hygiene")
        val vaultFile = File(dir, "redaction_vault.json")
        val vault = BlindCredentialVault(vaultFile, testMasterKey)

        val s1 = "SharedPrefix_Short99"
        val s2 = "SharedPrefix_Short99_ExtendedLongToken"
        val s3 = "한글비밀번호!2026"
        vault.putSecret("short_tok", "짧은 토큰", s1, VaultSecretCategory.API_TOKEN)
        vault.putSecret("long_tok", "긴 토큰", s2, VaultSecretCategory.API_TOKEN)
        vault.putSecret("ko_pw", "한글 비번", s3, VaultSecretCategory.LOGIN)

        val reloaded = BlindCredentialVault(vaultFile, testMasterKey)
        val decryptsBefore = reloaded.credentialDecryptCountForTesting()
        assertEquals(0L, decryptsBefore)

        // Clean text calls (many iterations) must perform ZERO credential decryptions
        repeat(20) { idx ->
            val clean = "일반 로그 메시지 #$idx: 주문 확인 중 (정상 상태)"
            assertEquals(clean, reloaded.redactSensitiveText(clean))
        }
        assertEquals(
            "redactSensitiveText() must perform ZERO vault entry decryptions on clean text",
            0L,
            reloaded.credentialDecryptCountForTesting()
        )

        // Text containing stored secrets (overlapping prefix + repeated + Korean + card + RRN)
        val leaked = "첫번째=$s2, 두번째=$s1, 반복=$s1, 한글=$s3, 카드=4532-1122-3344-9988, 주민=900101-1234567"
        val redacted = reloaded.redactSensitiveText(leaked)
        assertFalse(redacted.contains(s1))
        assertFalse(redacted.contains(s2))
        assertFalse(redacted.contains(s3))
        assertFalse(redacted.contains("4532-1122-3344-9988"))
        assertFalse(redacted.contains("900101-1234567"))
        assertTrue(redacted.contains("[REDACTED:vault://long_tok]"))
        assertTrue(redacted.contains("[REDACTED:vault://short_tok]"))
        assertTrue(redacted.contains("[REDACTED:vault://ko_pw]"))
        assertTrue(redacted.contains("••••-••••-••••-9988"))
        assertTrue(redacted.contains("••••••-•••••••"))
        assertEquals(
            "redactSensitiveText() must still perform ZERO vault entry decryptions even when secrets match",
            0L,
            reloaded.credentialDecryptCountForTesting()
        )

        // Verify withDecryptedSecretChars zeroes the CharArray immediately after block exit (normal & throwing)
        var capturedBuf: CharArray? = null
        val matchedInside = reloaded.withDecryptedSecretChars("vault://ko_pw") { _, chars ->
            capturedBuf = chars
            String(chars) == s3
        }
        assertEquals(true, matchedInside)
        assertNotNull(capturedBuf)
        assertTrue(
            "Temporary secret CharArray MUST be zeroed immediately after use",
            capturedBuf!!.all { it == '\u0000' }
        )

        var capturedOnError: CharArray? = null
        runCatching {
            reloaded.withDecryptedSecretChars("vault://short_tok") { _, chars ->
                capturedOnError = chars
                throw IllegalStateException("simulated injection error")
            }
        }
        assertNotNull(capturedOnError)
        assertTrue(
            "Temporary secret CharArray MUST be zeroed even when block throws",
            capturedOnError!!.all { it == '\u0000' }
        )
    }

    @Test
    fun v51_gap4_inactiveAccessibilityReturnsHonestFailureWithoutDecryptionAndAuditsEvent() {
        assertNull("Precondition: Accessibility service must be inactive", com.hanson.mobileuse.accessibility.HansonAccessibilityService.instance)

        val secret = "DoNotDecryptWhenA11yOff!2026"
        blindVault.putSecret("bank_pw", "은행 비밀번호", secret, VaultSecretCategory.PAYMENT)
        val decryptsBefore = blindVault.credentialDecryptCountForTesting()

        val outcome = harness.execute(
            "vault_blind_inject",
            JSONObject()
                .put("handle", "vault://bank_pw")
                .put("external_approval_receipt_id", "ext_appr_2026_a11y_01")
                .put("control_epoch", 5L),
            makeSnap(elements = listOf(makeEl(0, shortClass = "EditText", editable = true)))
        )

        assertFalse("vault_blind_inject MUST fail honestly when Accessibility is inactive", outcome.succeeded)
        assertNull("No success receipt may be emitted when Accessibility is inactive", outcome.receipt)
        assertTrue(outcome.outputText.contains("ACCESSIBILITY_UNAVAILABLE"))
        assertTrue(outcome.outputText.contains("Human Takeover") || outcome.outputText.contains("직접 입력"))
        assertFalse("Output must never leak secret", outcome.outputText.contains(secret))
        assertEquals(
            "Secret must NOT even be decrypted when Accessibility service is unavailable",
            decryptsBefore,
            blindVault.credentialDecryptCountForTesting()
        )

        val auditEntry = blindVault.getAuditEntries().lastOrNull()
        assertNotNull(auditEntry)
        assertEquals("BLIND_INJECT_BLOCKED_A11Y", auditEntry?.action)
        assertEquals("vault://bank_pw", auditEntry?.handle)
        assertEquals("ext_appr_2026_a11y_01", auditEntry?.approvedReceiptId)
        assertEquals(5L, auditEntry?.controlEpoch)
        assertEquals("ACCESSIBILITY_UNAVAILABLE", auditEntry?.outcome)
        assertTrue(blindVault.verifyAuditChain().valid)
    }

    @Test
    fun v51_gap5_auditLedgerHashChainDetectsTamperingAndNeverLogsRawSecrets() {
        val dir = tempFolder.newFolder("v51_audit_chain")
        val vaultFile = File(dir, "audit_vault.json")
        val vault = BlindCredentialVault(vaultFile, testMasterKey)

        val secretA = "SuperSensitiveAuditSecret#A1"
        val secretB = "SuperSensitiveAuditSecret#B2"

        vault.putSecret("acct_a", "계정 A", secretA, VaultSecretCategory.LOGIN)
        vault.putSecret("card_b", "카드 B", secretB, VaultSecretCategory.PAYMENT, approvedReceiptId = "ext_appr_put_001", controlEpoch = 3L)
        vault.resolvePlaintextForBlindInjection("vault://card_b", approvedReceiptId = "ext_appr_inj_002", controlEpoch = 3L)
        vault.deleteSecret("vault://acct_a", approvedReceiptId = "ext_appr_del_003", controlEpoch = 4L)

        val entries = vault.getAuditEntries()
        assertEquals(4, entries.size)
        assertEquals(BlindCredentialVault.AUDIT_GENESIS_HASH, entries[0].prevHash)
        for (i in 1 until entries.size) {
            assertEquals(entries[i - 1].entryHash, entries[i].prevHash)
            assertEquals(64, entries[i].entryHash.length)
            assertNotEquals(entries[i - 1].entryHash, entries[i].entryHash)
        }
        assertEquals("ext_appr_inj_002", entries[2].approvedReceiptId)
        assertEquals(3L, entries[2].controlEpoch)
        assertTrue(entries[2].timestampIso.contains("T"))

        val memVerify = vault.verifyAuditChain()
        val diskVerify = vault.verifyAuditLogOnDisk()
        assertTrue("In-memory SHA-256 audit chain must be valid", memVerify.valid)
        assertTrue("On-disk SHA-256 audit chain must be valid", diskVerify.valid)
        assertEquals(4, diskVerify.entryCount)

        val rawAuditText = vault.auditLogFile.readText(StandardCharsets.UTF_8)
        assertFalse("Audit log MUST NEVER store raw secretA", rawAuditText.contains(secretA))
        assertFalse("Audit log MUST NEVER store raw secretB", rawAuditText.contains(secretB))

        // Tamper with entry #2 (change approvedReceiptId on disk)
        val tamperedLines = vault.auditLogFile.readLines(StandardCharsets.UTF_8).toMutableList()
        tamperedLines[2] = tamperedLines[2].replace("ext_appr_inj_002", "ext_appr_FORGED")
        vault.auditLogFile.writeText(tamperedLines.joinToString("\n") + "\n", StandardCharsets.UTF_8)

        val diskAfterTamper = vault.verifyAuditLogOnDisk()
        assertFalse("Tampered audit log MUST fail SHA-256 chain verification", diskAfterTamper.valid)
        assertEquals(2L, diskAfterTamper.brokenAtSequence)

        val reloadedAfterTamper = BlindCredentialVault(vaultFile, testMasterKey)
        val reloadedVerify = reloadedAfterTamper.verifyAuditChain()
        assertFalse("Reloaded vault MUST detect tampered audit ledger", reloadedVerify.valid)
        assertEquals(2L, reloadedVerify.brokenAtSequence)
    }

    @Test
    fun v51_gap5_financialGuardValidatesExternalApprovalReceiptsAndEnforcesNonBypassInvariants() {
        val checkoutSnap = makeSnap(
            pkg = "com.sample.shop",
            title = "주문서 결제",
            elements = listOf(
                makeEl(0, shortClass = "TextView", text = "총 결제 금액: 29,000원", clickable = false),
                makeEl(1, shortClass = "Button", text = "결제하기 (29,000원)", clickable = true)
            )
        )
        val clickArgs = JSONObject().put("element_index", 1)
        val now = 1_795_000_000_000L

        // 1. Valid ExternalApprovalReceipt matching controlEpoch & amount -> validated!
        val validReceipt = ExternalApprovalReceipt(
            receiptId = "ext_appr_serabie_001",
            controlEpoch = 7L,
            approvedToolName = "click",
            approvedAmountText = "29,000원",
            issuedAtMs = now - 5_000L,
            expiresAtMs = now + 60_000L
        )
        val allowed = FinancialAndTakeoverGuard.assessBeforeAction(
            toolName = "click",
            args = clickArgs,
            snapshot = checkoutSnap,
            expectedControlEpoch = 7L,
            externalApprovalReceipt = validReceipt,
            nowMs = now
        )
        assertEquals(HighStakesKind.NONE, allowed.kind)
        assertTrue(allowed.externalApprovalValidated)
        assertEquals("ext_appr_serabie_001", allowed.externalApprovalReceiptId)
        assertEquals(7L, allowed.controlEpoch)

        // 2. Stale control_epoch -> rejected & still gated as FINANCIAL_COMMIT
        val staleEpoch = FinancialAndTakeoverGuard.assessBeforeAction(
            toolName = "click",
            args = clickArgs,
            snapshot = checkoutSnap,
            expectedControlEpoch = 8L,
            externalApprovalReceipt = validReceipt,
            nowMs = now
        )
        assertEquals(HighStakesKind.FINANCIAL_COMMIT, staleEpoch.kind)
        assertFalse(staleEpoch.externalApprovalValidated)
        assertEquals("STALE_CONTROL_EPOCH", staleEpoch.approvalRejectionCode)

        // 3. Replayed single-use receipt -> rejected
        val replayed = FinancialAndTakeoverGuard.assessBeforeAction(
            toolName = "click",
            args = clickArgs,
            snapshot = checkoutSnap,
            expectedControlEpoch = 7L,
            externalApprovalReceipt = validReceipt,
            consumedReceiptIds = setOf("ext_appr_serabie_001"),
            nowMs = now
        )
        assertEquals(HighStakesKind.FINANCIAL_COMMIT, replayed.kind)
        assertFalse(replayed.externalApprovalValidated)
        assertEquals("RECEIPT_REPLAYED", replayed.approvalRejectionCode)

        // 4. Expired receipt -> rejected
        val expired = FinancialAndTakeoverGuard.assessBeforeAction(
            toolName = "click",
            args = clickArgs,
            snapshot = checkoutSnap,
            expectedControlEpoch = 7L,
            externalApprovalReceipt = validReceipt.copy(expiresAtMs = now - 1L),
            nowMs = now
        )
        assertEquals(HighStakesKind.FINANCIAL_COMMIT, expired.kind)
        assertFalse(expired.externalApprovalValidated)
        assertEquals("RECEIPT_EXPIRED", expired.approvalRejectionCode)

        // 5. Price drift vs receipt approvedAmountText -> PRICE_DRIFT_ALERT even with valid epoch!
        val priceDriftReceipt = validReceipt.copy(approvedAmountText = "24,000원")
        val drifted = FinancialAndTakeoverGuard.assessBeforeAction(
            toolName = "click",
            args = clickArgs,
            snapshot = checkoutSnap,
            expectedControlEpoch = 7L,
            externalApprovalReceipt = priceDriftReceipt,
            nowMs = now
        )
        assertEquals(HighStakesKind.PRICE_DRIFT_ALERT, drifted.kind)
        assertFalse(drifted.externalApprovalValidated)
        assertEquals("PRICE_DRIFT_RECEIPT_MISMATCH", drifted.approvalRejectionCode)

        // 6. Strict Non-Bypass Invariant: CAPTCHA and 2FA/Secure Keypad can NEVER be bypassed by an external receipt!
        val captchaSnap = makeSnap(
            pkg = "com.sample.tickets",
            elements = listOf(
                makeEl(0, shortClass = "TextView", text = "Cloudflare Turnstile Verify you are human", clickable = false),
                makeEl(1, shortClass = "Button", text = "결제하기 (29,000원)", clickable = true)
            )
        )
        val captchaAttempt = FinancialAndTakeoverGuard.assessBeforeAction(
            toolName = "click",
            args = clickArgs,
            snapshot = captchaSnap,
            expectedControlEpoch = 7L,
            externalApprovalReceipt = validReceipt,
            nowMs = now
        )
        assertEquals(HighStakesKind.CAPTCHA_BOT_GATE, captchaAttempt.kind)
        assertFalse(captchaAttempt.externalApprovalValidated)
        assertEquals("NON_BYPASSABLE_HUMAN_TAKEOVER", captchaAttempt.approvalRejectionCode)

        val twoFaSnap = makeSnap(
            pkg = "com.sample.bank",
            elements = listOf(
                makeEl(0, shortClass = "TextView", text = "안심 보안키패드 비밀번호 입력", clickable = false),
                makeEl(1, shortClass = "Button", text = "결제하기", clickable = true)
            )
        )
        val twoFaAttempt = FinancialAndTakeoverGuard.assessBeforeAction(
            toolName = "click",
            args = clickArgs,
            snapshot = twoFaSnap,
            expectedControlEpoch = 7L,
            externalApprovalReceipt = validReceipt,
            nowMs = now
        )
        assertEquals(HighStakesKind.TWO_FACTOR_OR_BIOMETRIC, twoFaAttempt.kind)
        assertFalse(twoFaAttempt.externalApprovalValidated)
        assertEquals("NON_BYPASSABLE_HUMAN_TAKEOVER", twoFaAttempt.approvalRejectionCode)

        // 7. Inline JSON args (`external_approval_receipt_id` + `control_epoch`) binding works seamlessly
        val inlineArgs = JSONObject()
            .put("element_index", 1)
            .put("external_approval_receipt_id", "ext_appr_inline_009")
            .put("control_epoch", 11L)
        val inlineAllowed = FinancialAndTakeoverGuard.assessBeforeAction(
            toolName = "click",
            args = inlineArgs,
            snapshot = checkoutSnap,
            expectedControlEpoch = 11L,
            nowMs = now
        )
        assertEquals(HighStakesKind.NONE, inlineAllowed.kind)
        assertTrue(inlineAllowed.externalApprovalValidated)
        assertEquals("ext_appr_inline_009", inlineAllowed.externalApprovalReceiptId)
        assertEquals(11L, inlineAllowed.controlEpoch)
    }
}
