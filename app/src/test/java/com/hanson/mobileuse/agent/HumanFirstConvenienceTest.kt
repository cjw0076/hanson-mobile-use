package com.hanson.mobileuse.agent

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.accessibility.IndexedUiElement
import com.hanson.mobileuse.notification.InterceptedNotification
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class HumanFirstConvenienceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testMasterKey = ByteArray(32) { idx -> (idx + 29).toByte() }

    private fun makeEl(
        index: Int,
        shortClass: String = "Button",
        text: String = "",
        contentDesc: String = "",
        resourceId: String = "id_$index",
        packageName: String = "com.sample.target",
        clickable: Boolean = true,
        editable: Boolean = false,
        focused: Boolean = false,
        enabled: Boolean = true,
        top: Int = 200 + index * 110
    ): IndexedUiElement = IndexedUiElement(
        index = index,
        shortClass = shortClass,
        className = "android.widget.$shortClass",
        packageName = packageName,
        text = text,
        contentDesc = contentDesc,
        resourceId = resourceId,
        left = 80,
        top = top,
        right = 920,
        bottom = top + 90,
        centerX = 500,
        centerY = top + 45,
        normX = 463,
        normY = ((top + 45) * 1000) / 2400,
        clickable = clickable,
        longClickable = false,
        scrollable = false,
        editable = editable,
        checkable = false,
        checked = false,
        focused = focused,
        enabled = enabled,
        windowType = "APPLICATION",
        windowLayer = 0
    )

    private fun makeSnap(
        pkg: String = "com.sample.target",
        title: String = "Target Screen",
        elements: List<IndexedUiElement>
    ): AtomicSnapshot = AtomicSnapshot(
        screenWidth = 1080,
        screenHeight = 2400,
        activePackage = pkg,
        activeWindowTitle = title,
        indexedElements = elements
    )

    // =========================================================================
    // 1. UserExperienceMode
    // =========================================================================

    @Test
    fun userExperienceMode_exposesExpectedIdsLabelsAndFromIdFallback() {
        assertEquals("human", UserExperienceMode.SIMPLE_HUMAN.id)
        assertEquals("일상 모드", UserExperienceMode.SIMPLE_HUMAN.labelKo)
        assertEquals("일상", UserExperienceMode.SIMPLE_HUMAN.badgeKo)

        assertEquals("studio", UserExperienceMode.STUDIO_PRO.id)
        assertEquals("스튜디오", UserExperienceMode.STUDIO_PRO.labelKo)
        assertEquals("PRO", UserExperienceMode.STUDIO_PRO.badgeKo)

        assertEquals(UserExperienceMode.STUDIO_PRO, UserExperienceMode.fromId("STUDIO"))
        assertEquals(UserExperienceMode.SIMPLE_HUMAN, UserExperienceMode.fromId("human"))
        assertEquals(UserExperienceMode.SIMPLE_HUMAN, UserExperienceMode.fromId("unknown"))
        assertEquals(UserExperienceMode.SIMPLE_HUMAN, UserExperienceMode.fromId(null))
    }

    // =========================================================================
    // 2. HumanMemoryBubblesStore
    // =========================================================================

    @Test
    fun memoryBubblesStore_crudSeedIdempotencyAndAtomicPersistence() {
        val file = File(tempFolder.newFolder("bubbles_crud"), "hanson_memory_bubbles.json")
        val store = HumanMemoryBubblesStore(file)

        // Modernized (P0-4): seedDefaultsIfEmpty() is an idempotent no-op by default so personal memory
        // stays empty until the user explicitly opts in during onboarding or adds bubbles manually.
        store.seedDefaultsIfEmpty()
        assertEquals(0, store.bubbles.value.size)
        assertEquals("", store.formatBubblesForSystemPrompt())
        store.seedDefaultsIfEmpty()
        assertEquals("seedDefaultsIfEmpty must remain idempotent and empty", 0, store.bubbles.value.size)

        val added = store.addBubble(factKo = "음식은 덜 맵게 먹는 편이에요", category = "food", emoji = "🍜")
        assertNotNull(added)
        assertEquals("FOOD", added!!.category)
        assertTrue(added.enabled)

        assertTrue(store.toggleBubble(added.id))
        assertFalse(store.bubbles.value.first { it.id == added.id }.enabled)
        assertTrue(store.toggleBubble(added.id, true))
        assertTrue(store.bubbles.value.first { it.id == added.id }.enabled)
        assertFalse(store.toggleBubble("non_existent_id"))

        val reloaded = HumanMemoryBubblesStore(file)
        assertEquals(1, reloaded.bubbles.value.size)
        assertTrue(reloaded.bubbles.value.any { it.factKo == "음식은 덜 맵게 먹는 편이에요" })

        assertTrue(reloaded.removeBubble(added.id))
        assertFalse(reloaded.removeBubble(added.id))
        assertEquals(0, reloaded.bubbles.value.size)

        reloaded.clearAll()
        assertTrue(reloaded.bubbles.value.isEmpty())
        assertTrue(HumanMemoryBubblesStore(file).bubbles.value.isEmpty())
    }

    @Test
    fun memoryBubblesStore_extractsExplicitKoreanPreferencesAndIgnoresNormalCommands() {
        val file = File(tempFolder.newFolder("bubbles_learn"), "hanson_memory_bubbles.json")
        val store = HumanMemoryBubblesStore(file)

        val b1 = store.extractAndLearnFromUserUtterance("기억해줘: 아이스 아메리카노는 연하게 마셔요")
        assertNotNull(b1)
        assertTrue(b1!!.factKo.contains("아이스 아메리카노"))
        assertEquals("FOOD", b1.category)

        val b2 = store.extractAndLearnFromUserUtterance("조용한 창가 자리를 취향으로 저장해줘")
        assertNotNull(b2)
        assertTrue(b2!!.factKo.contains("조용한 창가 자리"))

        val b3 = store.extractAndLearnFromUserUtterance("집 주소는 서울 성동구 왕십리로 125 기억해둬")
        assertNotNull(b3)
        assertEquals("PLACE", b3!!.category)

        val b4 = store.extractAndLearnFromUserUtterance("선호 시간은 평일 오후 4시 이후 외워줘")
        assertNotNull(b4)
        assertEquals("TIME", b4!!.category)

        val b5 = store.extractAndLearnFromUserUtterance("땅콩이랑 고수는 못 먹는 편 기억 버블에 추가해줘")
        assertNotNull(b5)
        assertEquals("FOOD", b5!!.category)

        // Non-explicit utterances & knowledge questions MUST NEVER be speculatively saved as memory bubbles
        assertNull(store.extractAndLearnFromUserUtterance("땅콩 알레르기 증상 알려줘"))
        assertNull(store.extractAndLearnFromUserUtterance("내 취향은 조용한 창가 자리야"))
        assertNull(store.extractAndLearnFromUserUtterance("유튜브에서 음악 틀어줘"))
        assertNull(store.extractAndLearnFromUserUtterance("오늘 날씨 어때?"))
    }

    @Test
    fun memoryBubblesStore_rejectsBlankSecretsCardsRrnsOtpsApiKeysAndVaultSecrets() {
        val dir = tempFolder.newFolder("bubbles_secrets")
        val store = HumanMemoryBubblesStore(File(dir, "bubbles.json"))
        val vault = BlindCredentialVault(File(dir, "vault.json"), testMasterKey)
        vault.putSecret("door_code", "현관 비번", "SecretDoorCode#99", VaultSecretCategory.LOGIN)

        val rejectedInputs = listOf(
            "",
            "   \n\t  ",
            "기억해줘: ",
            "기억해줘: 내 비밀번호는 myPass2026!",
            "기억해줘: 4532-1111-2222-3333",
            "기억해줘: 4532111122223333",
            "기억해줘: 900101-1234567",
            "기억해줘: OTP 482910",
            "482910",
            "기억해줘: AIzaSyDemoApiKey1234567890",
            "기억해줘: sk-proj-secretKey12345678",
            "기억해줘: Bearer token_secret_abc12345",
            "기억해줘: SecretDoorCode#99"
        )

        for (raw in rejectedInputs) {
            assertNull(
                "Sensitive or blank utterance must be rejected: '$raw'",
                store.extractAndLearnFromUserUtterance(raw, vault)
            )
        }
        assertNull(store.addBubble("   "))
        assertNull(store.addBubble("AIzaSyDirectAddKey123456"))
        assertTrue(store.bubbles.value.isEmpty())
    }

    @Test
    fun memoryBubblesStore_toPromptContextBlockFiltersDisabledCapsMaxItemsAndRedactsVault() {
        val dir = tempFolder.newFolder("bubbles_prompt")
        val store = HumanMemoryBubblesStore(File(dir, "bubbles.json"))
        val vault = BlindCredentialVault(File(dir, "vault.json"), testMasterKey)
        vault.putSecret("vip_pin", "PIN", "778899", VaultSecretCategory.IDENTITY)

        val b1 = store.addBubble("매운 음식은 약하게", "FOOD", "🌶️")!!
        val b2 = store.addBubble("아침 알람은 7시 30분", "TIME", "⏰")!!
        val b3 = store.addBubble("단골 카페는 성수동", "PLACE", "☕")!!
        store.toggleBubble(b2.id, false)

        val block = store.toPromptContextBlock(vault = vault, maxItems = 1)
        assertTrue(block.contains(b3.factKo))
        assertFalse("Disabled bubble must not appear", block.contains(b2.factKo))
        assertFalse("maxItems=1 must omit older bubbles", block.contains(b1.factKo))
        assertEquals("", store.toPromptContextBlock(maxItems = 0))
    }

    // =========================================================================
    // 3. ProactiveDailyBriefEngine
    // =========================================================================

    @Test
    fun dailyBriefEngine_adaptsHeadlinesAcrossAllHoursIncludingBoundaryValues() {
        val morning = ProactiveDailyBriefEngine.buildBrief(8, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val noon = ProactiveDailyBriefEngine.buildBrief(12, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val afternoon = ProactiveDailyBriefEngine.buildBrief(15, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val evening = ProactiveDailyBriefEngine.buildBrief(19, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val night = ProactiveDailyBriefEngine.buildBrief(23, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val wrapNegative = ProactiveDailyBriefEngine.buildBrief(-1, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val wrapOver = ProactiveDailyBriefEngine.buildBrief(26, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

        assertTrue(morning.headlineKo.contains("아침"))
        assertTrue(noon.headlineKo.contains("점심"))
        assertTrue(afternoon.headlineKo.contains("오후"))
        assertTrue(evening.headlineKo.contains("저녁"))
        assertTrue(night.headlineKo.contains("밤"))
        assertTrue(wrapNegative.headlineKo.contains("밤"))
        assertTrue(wrapOver.headlineKo.contains("밤"))
        assertTrue(morning.quickActions.isNotEmpty())
    }

    @Test
    fun dailyBriefEngine_aggregatesNotificationsWatchersScratchpadBubblesAndRoutines() {
        val notifs = listOf(
            InterceptedNotification(
                sessionId = "s1",
                notificationKey = "k1",
                packageName = "com.kakao.talk",
                appLabel = "카카오톡",
                sender = "김민수",
                roomOrTitle = "김민수",
                messageText = "오늘 6시 강남역 괜찮아?",
                canQuickReply = true
            )
        )
        val watchers = listOf(
            WatcherRule(
                id = "w1",
                title = "KTX 취소표 감시",
                targetAppOrUrl = "com.korail.talk",
                matchKeywords = listOf("서울", "부산"),
                enabled = true
            ),
            WatcherRule(
                id = "w2",
                title = "비활성 규칙",
                targetAppOrUrl = "",
                matchKeywords = listOf("매진"),
                enabled = false
            )
        )
        val scratchpad = listOf(
            ScratchpadEntry(key = "예약번호", value = "RES-2026", sourcePackage = "catchtable")
        )
        val bubbles = listOf(
            HumanMemoryBubble(id = "b1", emoji = "☕", category = "FOOD", factKo = "아이스 아메리카노 선호", enabled = true)
        )
        val routines = listOf(
            PersonalRoutine(
                id = "r1",
                titleKo = "퇴근길 뉴스 요약",
                emoji = "🗞️",
                goalPrompt = "오늘 주요 IT 뉴스 요약해줘",
                sourceBadge = "내 루틴",
                pinned = true
            )
        )

        val brief = ProactiveDailyBriefEngine.buildBrief(
            hourOfDay = 18,
            notifications = notifs,
            watcherRules = watchers,
            scratchpadEntries = scratchpad,
            bubbles = bubbles,
            pinnedRoutines = routines
        )

        assertTrue(brief.highlights.any { it.contains("김민수") && it.contains("1건") })
        assertTrue(brief.highlights.any { it.contains("KTX 취소표 감시") })
        assertFalse("Disabled watcher must not be reported as active", brief.highlights.any { it.contains("비활성 규칙") })
        assertTrue(brief.highlights.any { it.contains("예약번호") })
        assertTrue(brief.highlights.any { it.contains("아이스 아메리카노") })
        assertTrue(brief.quickActions.any { it.labelKo.contains("김민수") })
        assertTrue(brief.quickActions.any { it.labelKo.contains("퇴근길 뉴스 요약") })
    }

    @Test
    fun dailyBriefEngine_handlesCompletelyEmptyStateCleanly() {
        val brief = ProactiveDailyBriefEngine.buildBrief(
            hourOfDay = 9,
            notifications = emptyList(),
            watcherRules = emptyList(),
            scratchpadEntries = emptyList(),
            bubbles = emptyList(),
            pinnedRoutines = emptyList()
        )
        assertTrue(brief.summaryKo.isNotBlank())
        assertTrue(brief.highlights.isNotEmpty())
        assertTrue(brief.quickActions.isNotEmpty())
    }

    // =========================================================================
    // 4. SmartClipboardAndShareIntentDetector
    // =========================================================================

    @Test
    fun clipboardDetector_detectsAllSixIntentKindsWithoutFalsePositives() {
        val urlResults = SmartClipboardAndShareIntentDetector.analyzeText("https://news.ycombinator.com/item?id=42")
        assertTrue(urlResults.any { it.kind == QuickIntentKind.URL_SUMMARY })
        assertFalse("Bare URL must not be classified as foreign prose", urlResults.any { it.kind == QuickIntentKind.TRANSLATE_REPLY })

        val scheduleResults = SmartClipboardAndShareIntentDetector.analyzeText("내일 오후 3시에 회의 시간 어때?")
        assertTrue(scheduleResults.any { it.kind == QuickIntentKind.SCHEDULE_A2A })

        val placeResults = SmartClipboardAndShareIntentDetector.analyzeText("서울특별시 성동구 왕십리로 125")
        assertTrue(placeResults.any { it.kind == QuickIntentKind.PLACE_OR_MAP })

        val phoneResults = SmartClipboardAndShareIntentDetector.analyzeText("식당 예약 번호 010-1234-5678")
        assertTrue(phoneResults.any { it.kind == QuickIntentKind.PHONE_CONCIERGE })

        val foreignResults = SmartClipboardAndShareIntentDetector.analyzeText("Hello, can we reschedule our meeting to Friday afternoon?")
        assertTrue(foreignResults.any { it.kind == QuickIntentKind.TRANSLATE_REPLY })

        val cmdResults = SmartClipboardAndShareIntentDetector.analyzeText("손전등 켜줘")
        assertTrue(cmdResults.any { it.kind == QuickIntentKind.DIRECT_COMMAND })
    }

    @Test
    fun clipboardDetector_supportsMultiIntentCompoundMeetupText() {
        val results = SmartClipboardAndShareIntentDetector.analyzeText("이번주 금요일 18:30 강남역 10번 출구에서 만나자")
        val kinds = results.map { it.kind }.toSet()
        assertTrue(QuickIntentKind.SCHEDULE_A2A in kinds)
        assertTrue(QuickIntentKind.PLACE_OR_MAP in kinds)
    }

    @Test
    fun clipboardDetector_rejectsBlankOverLengthAndAllSensitiveTextPatterns() {
        val rejected = listOf(
            "",
            "   \t\n ",
            "a".repeat(2001),
            "password: mySecretPassword123",
            "비밀번호는 abcd1234",
            "4532-1111-2222-3333",
            "4532111122223333",
            "900101-1234567",
            "482910",
            "12345678",
            "인증번호 [482910] 을 입력하세요",
            "OTP: 839201",
            "AIzaSyTestApiKeyValue987654321",
            "sk-proj-testkey1234567890",
            "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
        )
        for (sample in rejected) {
            assertTrue(
                "Clipboard text must return emptyList for sensitive/invalid input (${sample.take(24)})",
                SmartClipboardAndShareIntentDetector.analyzeText(sample).isEmpty()
            )
        }

        // Exact boundary 2000 chars is allowed
        val valid2000 = "Please check this summary note. ".repeat(62) + "end."
        assertTrue(valid2000.length <= 2000)
        assertTrue(SmartClipboardAndShareIntentDetector.analyzeText(valid2000).isNotEmpty())
    }

    @Test
    fun clipboardDetector_rejectsTextAlteredByBlindCredentialVault() {
        val vault = BlindCredentialVault(
            File(tempFolder.newFolder("clip_vault"), "vault.json"),
            testMasterKey
        )
        vault.putSecret("corp_token", "사내 토큰", "corp_live_secret_9911", VaultSecretCategory.API_TOKEN)

        val hit = SmartClipboardAndShareIntentDetector.analyzeText(
            "https://example.com/?token=corp_live_secret_9911 요약해줘",
            vault
        )
        assertTrue("Vault-redacted clipboard input must be rejected", hit.isEmpty())

        val safe = SmartClipboardAndShareIntentDetector.analyzeText("https://example.com/news", vault)
        assertTrue(safe.any { it.kind == QuickIntentKind.URL_SUMMARY })
    }

    // =========================================================================
    // 5. AppSpecificTipBank + ArtemisDeliberativeAligner
    // =========================================================================

    @Test
    fun appSpecificTipBank_coversAllThirteenAppFamiliesWithoutShadowing() {
        val packagesAndExpected = listOf(
            "com.kakao.talk" to "카카오톡",
            "com.sampleapp" to "배달의민족",
            "com.coupang.mobile" to "쿠팡",
            "com.coupang.mobile.eats" to "쿠팡이츠",
            "com.nhn.android.search" to "네이버",
            "com.nhn.android.nmap" to "네이버 지도",
            "com.kakao.taxi" to "카카오 T",
            "viva.republica.toss" to "토스",
            "com.google.android.youtube" to "유튜브",
            "co.kr.catchtable.android.catchtable_app" to "캐치테이블",
            "com.korail.talk" to "코레일톡",
            "com.android.settings" to "설정",
            "com.sec.android.app.clockpackage" to "시계"
        )
        for ((pkg, expectedKeyword) in packagesAndExpected) {
            val tip = AppSpecificTipBank.getTipForPackageOrGoal(pkg, "")
            assertTrue("Expected tip for $pkg to contain '$expectedKeyword' but was '$tip'", tip.contains(expectedKeyword))
        }

        // Goal-based lookup when activePackage is empty
        assertTrue(AppSpecificTipBank.getTipForPackageOrGoal("", "배민에서 족발 찾아줘").contains("배달의민족"))
        assertTrue(AppSpecificTipBank.getTipForPackageOrGoal("", "쿠팡이츠 배달 확인").contains("쿠팡이츠"))
        assertTrue(AppSpecificTipBank.getTipForPackageOrGoal("", "네이버 지도로 강남역 길찾기").contains("네이버 지도"))
        assertEquals("", AppSpecificTipBank.getTipForPackageOrGoal("com.unknown.pkg", "아무 작업"))
    }

    @Test
    fun artemisAligner_isPurelyAdvisoryAndNeverMutatesInputTextArgs() {
        val topPlainEdit = makeEl(0, shortClass = "EditText", text = "", resourceId = "memo_box", editable = true, focused = false, top = 120)
        val searchEdit = makeEl(1, shortClass = "EditText", text = "검색어 입력", resourceId = "search_input", editable = true, focused = false, top = 400)
        val focusedEdit = makeEl(2, shortClass = "EditText", text = "", resourceId = "reply_box", editable = true, focused = true, top = 1800)
        val nonEditableBtn = makeEl(3, shortClass = "Button", text = "확인", editable = false, clickable = true, top = 1950)
        val ownHansonEdit = makeEl(4, shortClass = "EditText", packageName = "com.hanson.mobileuse", editable = true, focused = true, top = 50)

        val snapWithFocus = makeSnap("com.kakao.talk", elements = listOf(ownHansonEdit, topPlainEdit, searchEdit, focusedEdit, nonEditableBtn))
        val r1 = ArtemisDeliberativeAligner.alignBeforeExecution(
            toolName = "input_text",
            args = JSONObject().put("text", "안녕하세요"),
            thoughtOrReply = "메시지를 입력할게요",
            snapshot = snapWithFocus,
            iteration = 1
        )
        assertFalse("Advisory aligner must NEVER mutate input_text args", r1.wasCorrected)
        assertFalse("Must not inject element_index when omitted by VLM", r1.alignedArgs.has("element_index"))
        assertEquals("안녕하세요", r1.alignedArgs.getString("text"))
        assertTrue(r1.appTipKo.contains("카카오톡"))

        // Explicit index pointing at own Hanson UI produces advisory warning without mutating args
        val rOwn = ArtemisDeliberativeAligner.alignBeforeExecution(
            toolName = "input_text",
            args = JSONObject().put("element_index", 4).put("text", "족발"),
            thoughtOrReply = "검색합니다",
            snapshot = snapWithFocus,
            iteration = 1
        )
        assertFalse(rOwn.wasCorrected)
        assertEquals(4, rOwn.alignedArgs.getInt("element_index"))
        assertTrue("Expected advisory warning for Hanson own element", rOwn.advisoryWarningKo.isNotBlank())
    }

    @Test
    fun artemisAligner_preservesValidExplicitEditableNode() {
        val edit0 = makeEl(0, shortClass = "EditText", editable = true, focused = true)
        val edit1 = makeEl(1, shortClass = "EditText", editable = true, focused = false)
        val snap = makeSnap("com.kakao.talk", elements = listOf(edit0, edit1))

        val originalArgs = JSONObject().put("element_index", 1).put("text", "테스트")
        val res = ArtemisDeliberativeAligner.alignBeforeExecution(
            toolName = "input_text",
            args = originalArgs,
            thoughtOrReply = "두 번째 입력창에 적을게요",
            snapshot = snap,
            iteration = 2
        )
        assertFalse(res.wasCorrected)
        assertEquals(1, res.alignedArgs.getInt("element_index"))
        assertEquals("", res.advisoryWarningKo)
    }

    @Test
    fun artemisAligner_neverMutatesVlmClickCoordinatesOrElementIndex() {
        val ownHansonBubble = makeEl(0, text = "전송", packageName = "com.hanson.mobileuse", clickable = true)
        val cancelBtn = makeEl(1, text = "취소", clickable = true)
        val sendBtn = makeEl(2, text = "전송", contentDesc = "메시지 전송", clickable = true)
        val snap = makeSnap("com.kakao.talk", elements = listOf(ownHansonBubble, cancelBtn, sendBtn))

        // 1. VLM element_index is preserved verbatim
        val rIndex = ArtemisDeliberativeAligner.alignBeforeExecution(
            toolName = "click",
            args = JSONObject().put("element_index", 1),
            thoughtOrReply = "이제 '전송' 버튼을 눌러 보낼게요.",
            snapshot = snap,
            iteration = 2
        )
        assertFalse(rIndex.wasCorrected)
        assertEquals(1, rIndex.alignedArgs.getInt("element_index"))

        // 2. VLM norm_x / norm_y coordinates are preserved verbatim
        val rCoords = ArtemisDeliberativeAligner.alignBeforeExecution(
            toolName = "click",
            args = JSONObject().put("norm_x", 412).put("norm_y", 778).put("target_description", "전송"),
            thoughtOrReply = "'전송' 버튼을 누릅니다",
            snapshot = snap,
            iteration = 2
        )
        assertFalse(rCoords.wasCorrected)
        assertEquals(412, rCoords.alignedArgs.getInt("norm_x"))
        assertEquals(778, rCoords.alignedArgs.getInt("norm_y"))
        assertFalse(rCoords.alignedArgs.has("element_index"))

        // 3. Hanson own package element emits advisory warning without mutating args
        val rOwnPkg = ArtemisDeliberativeAligner.alignBeforeExecution(
            toolName = "click",
            args = JSONObject().put("element_index", 0),
            thoughtOrReply = "\"전송\" 탭을 누릅니다.",
            snapshot = snap,
            iteration = 2
        )
        assertFalse(rOwnPkg.wasCorrected)
        assertEquals(0, rOwnPkg.alignedArgs.getInt("element_index"))
        assertTrue(rOwnPkg.advisoryWarningKo.contains("Hanson"))
    }

    @Test
    fun artemisAligner_emitsAdvisoryWarningOnDisabledElementWithoutMutatingClick() {
        val disabledTarget = makeEl(0, text = "결제", clickable = true, enabled = false)
        val okBtn = makeEl(1, text = "확인", clickable = true, enabled = true)
        val snap = makeSnap("com.sampleapp", elements = listOf(disabledTarget, okBtn))

        val rDisabled = ArtemisDeliberativeAligner.alignBeforeExecution(
            toolName = "click",
            args = JSONObject().put("element_index", 0),
            thoughtOrReply = "'결제' 버튼을 누릅니다",
            snapshot = snap,
            iteration = 2
        )
        assertFalse(rDisabled.wasCorrected)
        assertEquals(0, rDisabled.alignedArgs.getInt("element_index"))
        assertTrue("Disabled element should emit advisory warning", rDisabled.advisoryWarningKo.contains("비활성화"))

        val rClean = ArtemisDeliberativeAligner.alignBeforeExecution(
            toolName = "click",
            args = JSONObject().put("element_index", 1),
            thoughtOrReply = "'확인' 버튼을 누릅니다",
            snapshot = snap,
            iteration = 2
        )
        assertFalse(rClean.wasCorrected)
        assertEquals(1, rClean.alignedArgs.getInt("element_index"))
        assertEquals("", rClean.advisoryWarningKo)
    }

    @Test
    fun artemisAligner_warnsOnStep1HomePressInTargetAppWithoutMutatingTool() {
        val targetSnap = makeSnap("com.kakao.talk", elements = listOf(makeEl(0, text = "채팅")))
        val step1 = ArtemisDeliberativeAligner.alignBeforeExecution(
            toolName = "press_key",
            args = JSONObject().put("key", "HOME"),
            thoughtOrReply = "홈으로 가자",
            snapshot = targetSnap,
            iteration = 1
        )
        assertFalse("Advisory aligner must never rewrite toolName or args", step1.wasCorrected)
        assertEquals("press_key", step1.toolName)
        assertEquals("HOME", step1.alignedArgs.getString("key"))
        assertTrue(step1.appTipKo.contains("카카오톡"))
        assertTrue("Expected advisory warning for step-1 HOME in external app", step1.advisoryWarningKo.contains("HOME"))

        // Iteration 2 does not trigger step-1 HOME advisory warning
        val step2 = ArtemisDeliberativeAligner.alignBeforeExecution(
            toolName = "press_key",
            args = JSONObject().put("key", "HOME"),
            thoughtOrReply = "이제 홈으로",
            snapshot = targetSnap,
            iteration = 2
        )
        assertFalse(step2.wasCorrected)
        assertEquals("press_key", step2.toolName)
        assertEquals("", step2.advisoryWarningKo)

        // Own Hanson app does not trigger step-1 HOME advisory warning
        val hansonSnap = makeSnap("com.hanson.mobileuse", elements = emptyList())
        val ownStep1 = ArtemisDeliberativeAligner.alignBeforeExecution(
            toolName = "press_key",
            args = JSONObject().put("key", "HOME"),
            thoughtOrReply = "홈으로",
            snapshot = hansonSnap,
            iteration = 1
        )
        assertFalse(ownStep1.wasCorrected)
        assertEquals("press_key", ownStep1.toolName)
        assertEquals("", ownStep1.advisoryWarningKo)
    }

    // =========================================================================
    // 6. HumanStepTranslator
    // =========================================================================

    @Test
    fun humanStepTranslator_producesFriendlyKoreanForToolsUsingSnapshotLabels() {
        val snap = makeSnap(elements = listOf(makeEl(4, text = "장바구니 담기")))
        val clickStep = HumanStepTranslator.toPlainKoreanStep(
            toolName = "click",
            args = JSONObject().put("element_index", 4),
            snapshot = snap
        )
        assertTrue(clickStep.contains("장바구니 담기"))
        assertFalse(clickStep.contains("element_index"))

        val inputStep = HumanStepTranslator.toPlainKoreanStep(
            toolName = "input_text",
            args = JSONObject().put("text", "족발 맛집")
        )
        assertTrue(inputStep.contains("족발 맛집"))

        assertTrue(HumanStepTranslator.toPlainKoreanStep("swipe", JSONObject().put("direction", "up")).contains("아래로"))
        assertTrue(HumanStepTranslator.toPlainKoreanStep("press_key", JSONObject().put("key", "BACK")).contains("이전 화면"))
        assertTrue(HumanStepTranslator.toPlainKoreanStep("manage_app", JSONObject().put("action", "launch").put("app_name", "카카오톡")).contains("카카오톡"))
        assertTrue(HumanStepTranslator.toPlainKoreanStep("control_device_setting", JSONObject().put("setting", "flashlight").put("value", "1")).contains("손전등"))
        assertTrue(HumanStepTranslator.toPlainKoreanStep("set_alarm", JSONObject().put("hour", 7).put("minute", 30)).contains("오전 7시 30분"))
    }

    @Test
    fun humanStepTranslator_sanitizeStripsAllTechnicalArtifactsAndJsonObjects() {
        val dirty = """
            작업 실패: MobileUseAgentEngine.kt:1423 java.lang.IllegalStateException: timeout
            at com.hanson.mobileuse.agent.MobileUseAgentEngine.runLoop(MobileUseAgentEngine.kt:1423)
            normX=512 normY=340 px=(540,1200) bounds=[10,20][300,400] {"element_index":3,"nested":{"norm_x":500}}
        """.trimIndent()

        val clean = HumanStepTranslator.sanitizeForHumanDisplay(dirty)
        assertFalse(clean.contains("normX"))
        assertFalse(clean.contains("normY"))
        assertFalse(clean.contains("px="))
        assertFalse(clean.contains("bounds="))
        assertFalse(clean.contains(".kt:1423"))
        assertFalse(clean.contains("java.lang.IllegalStateException"))
        assertFalse(clean.contains("element_index"))
        assertFalse(clean.contains("{"))
        assertTrue(clean.contains("작업 실패"))
    }

    @Test
    fun humanStepTranslator_handlesBlankAndPureJsonSafely() {
        assertEquals("", HumanStepTranslator.sanitizeForHumanDisplay("   "))
        assertEquals("", HumanStepTranslator.sanitizeForHumanDisplay("{\"key\":\"value\",\"num\":123}"))
        val fallback = HumanStepTranslator.toPlainKoreanStep(
            toolName = "complete_task",
            args = JSONObject().put("summary", "{\"raw\":1}")
        )
        assertTrue(fallback.isNotBlank())
        assertFalse(fallback.contains("{"))
    }

    // =========================================================================
    // 7. ReversibleReceiptPlanner
    // =========================================================================

    @Test
    fun reversibleReceiptPlanner_plansDeviceSettingInverseActions() {
        val flashOffUndo = ReversibleReceiptPlanner.planUndoForAction(
            "control_device_setting",
            JSONObject().put("setting", "flashlight").put("value", "1")
        )
        assertNotNull(flashOffUndo)
        assertEquals("control_device_setting", flashOffUndo!!.undoToolName)
        assertEquals("0", JSONObject(flashOffUndo.undoArgsJson).getString("value"))

        val flashOnUndo = ReversibleReceiptPlanner.planUndoForAction(
            "control_device_setting",
            JSONObject().put("setting", "flashlight").put("value", "0")
        )
        assertEquals("1", JSONObject(flashOnUndo!!.undoArgsJson).getString("value"))

        val dndOffUndo = ReversibleReceiptPlanner.planUndoForAction(
            "control_device_setting",
            JSONObject().put("setting", "dnd").put("value", "on")
        )
        assertEquals("off", JSONObject(dndOffUndo!!.undoArgsJson).getString("value"))

        val dndOnUndo = ReversibleReceiptPlanner.planUndoForAction(
            "control_device_setting",
            JSONObject().put("setting", "dnd").put("value", "off")
        )
        assertEquals("on", JSONObject(dndOnUndo!!.undoArgsJson).getString("value"))

        val ringerUndo = ReversibleReceiptPlanner.planUndoForAction(
            "control_device_setting",
            JSONObject().put("setting", "ringer_mode").put("value", "silent")
        )
        assertEquals("normal", JSONObject(ringerUndo!!.undoArgsJson).getString("value"))

        val brightUndo = ReversibleReceiptPlanner.planUndoForAction(
            "control_device_setting",
            JSONObject().put("setting", "brightness").put("value", "220"),
            previousNoteValue = "95"
        )
        assertEquals("95", JSONObject(brightUndo!!.undoArgsJson).getString("value"))

        val volUndo = ReversibleReceiptPlanner.planUndoForAction(
            "control_device_setting",
            JSONObject().put("setting", "volume_media").put("value", "80"),
            previousNoteValue = "35"
        )
        assertEquals("35", JSONObject(volUndo!!.undoArgsJson).getString("value"))
    }

    @Test
    fun reversibleReceiptPlanner_plansNotesScratchpadWatcherAndBackNavigation() {
        val saveUndoNew = ReversibleReceiptPlanner.planUndoForAction(
            "save_note",
            JSONObject().put("key", "memo1").put("content", "새 메모")
        )
        assertNotNull(saveUndoNew)
        assertEquals("save_note", saveUndoNew!!.undoToolName)
        assertEquals("", JSONObject(saveUndoNew.undoArgsJson).getString("content"))

        val saveUndoPrev = ReversibleReceiptPlanner.planUndoForAction(
            "save_note",
            JSONObject().put("key", "memo1").put("content", "수정됨"),
            previousNoteValue = "원래 메모"
        )
        assertEquals("원래 메모", JSONObject(saveUndoPrev!!.undoArgsJson).getString("content"))

        val padUndo = ReversibleReceiptPlanner.planUndoForAction(
            "scratchpad_note",
            JSONObject().put("action", "put").put("key", "주문번호").put("value", "ORD-123")
        )
        assertEquals("scratchpad_note", padUndo!!.undoToolName)
        assertEquals("remove", JSONObject(padUndo.undoArgsJson).getString("action"))
        assertEquals("주문번호", JSONObject(padUndo.undoArgsJson).getString("key"))

        val watcherUndo = ReversibleReceiptPlanner.planUndoForAction(
            "watcher_manage",
            JSONObject().put("action", "add").put("title", "티켓").put("rule_id", "watch_42")
        )
        assertEquals("watcher_manage", watcherUndo!!.undoToolName)
        assertEquals("remove", JSONObject(watcherUndo.undoArgsJson).getString("action"))
        assertEquals("watch_42", JSONObject(watcherUndo.undoArgsJson).getString("rule_id"))

        for (navTool in listOf("click", "open_link", "manage_app")) {
            val navUndo = ReversibleReceiptPlanner.planUndoForAction(navTool, JSONObject())
            assertNotNull("Expected BACK undo for $navTool", navUndo)
            assertEquals("press_key", navUndo!!.undoToolName)
            assertEquals("BACK", JSONObject(navUndo.undoArgsJson).getString("key"))
        }
    }

    @Test
    fun reversibleReceiptPlanner_returnsNullForReadOnlyAndIrreversibleActions() {
        assertNull(ReversibleReceiptPlanner.planUndoForAction("list_notifications", JSONObject()))
        assertNull(ReversibleReceiptPlanner.planUndoForAction("read_note", JSONObject().put("key", "k")))
        assertNull(ReversibleReceiptPlanner.planUndoForAction("make_call", JSONObject().put("recipient", "010-1234-5678")))
        assertNull(ReversibleReceiptPlanner.planUndoForAction("send_sms", JSONObject().put("recipient", "엄마").put("message", "도착")))
        assertNull(ReversibleReceiptPlanner.planUndoForAction("reply_notification", JSONObject().put("target", "").put("message", "확인")))
        assertNull(ReversibleReceiptPlanner.planUndoForAction("manage_app", JSONObject().put("action", "list_games")))
    }

    // =========================================================================
    // 8. PersonalRoutineStore
    // =========================================================================

    @Test
    fun personalRoutineStore_seedCrudTogglePinnedAndPersistence() {
        val file = File(tempFolder.newFolder("routines_crud"), "hanson_personal_routines.json")
        val store = PersonalRoutineStore(file)

        store.seedDefaultsIfEmpty()
        val initialSize = store.routines.value.size
        assertEquals(3, initialSize)
        store.seedDefaultsIfEmpty()
        assertEquals(initialSize, store.routines.value.size)

        val custom = store.addRoutine(
            titleKo = "점심 메뉴 찾기",
            goalPrompt = "배민에서 주변 샐러드 맛집 검색해줘",
            emoji = "🥗",
            sourceBadge = "직접 추가",
            pinned = true,
            stepCount = 2
        )
        assertEquals("점심 메뉴 찾기", custom.titleKo)
        assertTrue(custom.pinned)

        assertTrue(store.togglePinned(custom.id))
        assertFalse(store.routines.value.first { it.id == custom.id }.pinned)
        assertFalse(store.togglePinned("missing_id"))

        val reloaded = PersonalRoutineStore(file)
        assertEquals(initialSize + 1, reloaded.routines.value.size)
        assertFalse(reloaded.routines.value.first { it.id == custom.id }.pinned)

        assertTrue(reloaded.removeRoutine(custom.id))
        assertFalse(reloaded.removeRoutine(custom.id))
        assertEquals(initialSize, reloaded.routines.value.size)
    }

    @Test
    fun personalRoutineStore_compilesFromDemonstrationWithAppLabelsAndFallbacks() {
        val file = File(tempFolder.newFolder("routines_demo"), "hanson_personal_routines.json")
        val store = PersonalRoutineStore(file)

        val compiled = store.compileFromDemonstration(
            customTitle = "가족방 안부 인사",
            recordedPackage = "com.kakao.talk",
            compiledRecipe = "[사용자 직접 시연 루틴 (3단계)] 1.[터치] '가족방' → 2.[입력] '다녀왔습니다' → 3.[터치] '전송'",
            stepCount = 3
        )
        assertEquals("가족방 안부 인사", compiled.titleKo)
        assertEquals("🎬", compiled.emoji)
        assertEquals(3, compiled.stepCount)
        assertTrue(compiled.sourceBadge.contains("3단계"))
        assertTrue(compiled.goalPrompt.contains("카카오톡"))
        assertTrue(compiled.goalPrompt.contains("가족방"))

        val fallback = store.compileFromDemonstration(
            customTitle = "",
            recordedPackage = "com.sampleapp",
            compiledRecipe = "",
            stepCount = 0
        )
        assertTrue(fallback.titleKo.contains("배달의민족"))
        assertEquals(1, fallback.stepCount)
        assertEquals(2, PersonalRoutineStore(file).routines.value.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun personalRoutineStore_rejectsBlankGoalPrompt() {
        val file = File(tempFolder.newFolder("routines_err"), "hanson_personal_routines.json")
        PersonalRoutineStore(file).addRoutine(titleKo = "빈 루틴", goalPrompt = "   ")
    }

    // =========================================================================
    // 9. CallConciergePlanner
    // =========================================================================

    @Test
    fun callConciergePlanner_buildsApprovedScriptWithCalleePurposeAndPreferredSlot() {
        val plan = CallConciergePlanner.buildPlan(
            calleeLabel = "성수 비스트로",
            phoneNumber = "02-555-1234",
            purposeKo = "2명 저녁 식사 자리 예약",
            preferredSlot = "금요일 오후 7시"
        )
        assertEquals("성수 비스트로", plan.calleeLabel)
        assertEquals("02-555-1234", plan.phoneNumber)
        assertEquals("2명 저녁 식사 자리 예약", plan.purposeKo)
        assertTrue(plan.requiresHumanApproval)
        assertTrue(plan.scriptLinesKo.size >= 3)
        assertTrue(plan.scriptLinesKo.any { it.contains("성수 비스트로") && it.contains("2명 저녁 식사 자리 예약") })
        assertTrue(plan.scriptLinesKo.any { it.contains("금요일 오후 7시") })
    }

    @Test
    fun callConciergePlanner_handlesBlankFieldsAndEmptySlotGracefully() {
        val plan = CallConciergePlanner.buildPlan(
            calleeLabel = " ",
            phoneNumber = "010-9876-5432",
            purposeKo = "",
            preferredSlot = ""
        )
        assertEquals("상대방", plan.calleeLabel)
        assertTrue(plan.purposeKo.isNotBlank())
        assertTrue(plan.requiresHumanApproval)
        assertTrue(plan.scriptLinesKo.all { it.isNotBlank() })
    }

    // =========================================================================
    // 10. HansonFirestoreCloudSyncEngine (Optional BYO Cloud Firestore Sync)
    // =========================================================================

    private fun createDummySyncEnv(folderName: String): Triple<Context, MutableMap<String, Any>, BlindCredentialVault> {
        val root = tempFolder.newFolder(folderName)
        val filesDir = File(root, "files").apply { mkdirs() }
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
        val dummyContext = object : ContextWrapper(null) {
            override fun getPackageName(): String = "com.hanson.mobileuse"
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = filesDir
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = dummyPrefs
        }
        val vault = BlindCredentialVault(File(filesDir, "sync_vault.json"), testMasterKey)
        return Triple(dummyContext, prefsMap, vault)
    }

    @Test
    fun firestoreSync_mergeBubblesLwwSelectsNewerTimestampAndPreservesLocalTies() {
        val local = listOf(
            HumanMemoryBubble(id = "b1", emoji = "☕", category = "FOOD", factKo = "아이스 아메리카노", updatedAtMs = 200L),
            HumanMemoryBubble(id = "b2", emoji = "🚇", category = "PLACE", factKo = "로컬 지하철 우선", updatedAtMs = 300L),
            HumanMemoryBubble(id = "b_local", emoji = "💬", category = "STYLE", factKo = "로컬 전용", updatedAtMs = 150L)
        )
        val remote = listOf(
            HumanMemoryBubble(id = "b1", emoji = "🍵", category = "FOOD", factKo = "디카페인 라떼", updatedAtMs = 500L),
            HumanMemoryBubble(id = "b2", emoji = "🚕", category = "PLACE", factKo = "원격 동일 타임스탬프", updatedAtMs = 300L),
            HumanMemoryBubble(id = "b_remote", emoji = "🏠", category = "PLACE", factKo = "원격 추가", updatedAtMs = 400L)
        )

        val merged = HansonFirestoreCloudSyncEngine.mergeBubblesLww(local, remote)
        assertEquals(4, merged.size)
        assertEquals("디카페인 라떼", merged.first { it.id == "b1" }.factKo)
        assertEquals("로컬 지하철 우선", merged.first { it.id == "b2" }.factKo)
        assertEquals("b1", merged.first().id)
    }

    @Test
    fun firestoreSync_mergeRoutinesLwwSelectsNewerTimestampAndPreservesLocalTies() {
        val local = listOf(
            PersonalRoutine(id = "r1", titleKo = "아침 루틴 구버전", emoji = "☀️", goalPrompt = "구버전", sourceBadge = "로컬", createdAtMs = 100L),
            PersonalRoutine(id = "r2", titleKo = "로컬 동률 우선", emoji = "🎧", goalPrompt = "집중 모드", sourceBadge = "로컬", createdAtMs = 250L)
        )
        val remote = listOf(
            PersonalRoutine(id = "r1", titleKo = "아침 루틴 최신", emoji = "🌅", goalPrompt = "최신 프롬프트", sourceBadge = "클라우드", createdAtMs = 600L),
            PersonalRoutine(id = "r2", titleKo = "원격 동률", emoji = "🎵", goalPrompt = "원격 프롬프트", sourceBadge = "클라우드", createdAtMs = 250L),
            PersonalRoutine(id = "r3", titleKo = "원격 신규", emoji = "🌙", goalPrompt = "취침 준비", sourceBadge = "클라우드", createdAtMs = 450L)
        )

        val merged = HansonFirestoreCloudSyncEngine.mergeRoutinesLww(local, remote)
        assertEquals(3, merged.size)
        assertEquals("아침 루틴 최신", merged.first { it.id == "r1" }.titleKo)
        assertEquals("로컬 동률 우선", merged.first { it.id == "r2" }.titleKo)
        assertEquals("r1", merged.first().id)
    }

    @Test
    fun firestoreSync_sanitizeBubbleAndRoutineMapsBlockForbiddenKeysAndRedactSecrets() {
        val dir = tempFolder.newFolder("sync_sanitize")
        val vault = BlindCredentialVault(File(dir, "vault.json"), testMasterKey)
        val secretToken = "VaultSecretPass!9988"
        vault.putSecret("portal_login", "포털 비번", secretToken, VaultSecretCategory.LOGIN)

        val dirtyBubble = HumanMemoryBubble(
            id = "b_secret",
            emoji = "🫧",
            category = "PREF",
            factKo = "내 키는 AIzaSyDummySecret123456 이고 비번은 $secretToken 카드 4532-1111-2222-3333",
            enabled = true,
            updatedAtMs = 1000L
        )
        val bubbleMap = HansonFirestoreCloudSyncEngine.sanitizeBubbleMap(dirtyBubble, vault)
        assertEquals(setOf("id", "emoji", "category", "factKo", "enabled", "updatedAtMs"), bubbleMap.keys)
        for (forbidden in HansonFirestoreCloudSyncEngine.FORBIDDEN_SECRET_KEYS) {
            assertFalse("Forbidden key '$forbidden' must never be emitted", bubbleMap.containsKey(forbidden))
        }
        val cleanFact = bubbleMap["factKo"] as String
        assertFalse(cleanFact.contains("AIzaSyDummySecret123456"))
        assertFalse(cleanFact.contains(secretToken))
        assertFalse(cleanFact.contains("4532-1111-2222-3333"))
        assertTrue(cleanFact.contains("REDACTED"))

        val dirtyRoutine = PersonalRoutine(
            id = "r_secret",
            titleKo = "보안 로그인",
            emoji = "🎬",
            goalPrompt = "토큰 Bearer super_secret_token_9911 과 sk-proj-abc12345678 사용",
            sourceBadge = "시연",
            pinned = true,
            stepCount = 2,
            createdAtMs = 2000L
        )
        val routineMap = HansonFirestoreCloudSyncEngine.sanitizeRoutineMap(dirtyRoutine, vault)
        assertEquals(
            setOf("id", "titleKo", "emoji", "goalPrompt", "sourceBadge", "pinned", "stepCount", "createdAtMs"),
            routineMap.keys
        )
        for (forbidden in HansonFirestoreCloudSyncEngine.FORBIDDEN_SECRET_KEYS) {
            assertFalse(routineMap.containsKey(forbidden))
        }
        val cleanGoal = routineMap["goalPrompt"] as String
        assertFalse(cleanGoal.contains("super_secret_token_9911"))
        assertFalse(cleanGoal.contains("sk-proj-abc12345678"))
        assertTrue(cleanGoal.contains("REDACTED"))
    }

    @Test
    fun firestoreSync_sanitizeWatcherAndReceiptMapsExcludeForbiddenKeysAndRedactSecrets() {
        val dir = tempFolder.newFolder("sync_watcher_rcpt")
        val vault = BlindCredentialVault(File(dir, "vault.json"), testMasterKey)
        vault.putSecret("card_pin", "PIN", "SecretPin9876", VaultSecretCategory.PAYMENT)

        val watcher = WatcherRule(
            id = "w_safe",
            title = "주문 확인 SecretPin9876",
            targetAppOrUrl = "https://example.com/?key=AIzaSySecret123456",
            matchKeywords = listOf("예약", "SecretPin9876", "900101-1234567"),
            lastHitSummary = "주민번호 900101-1234567 감지"
        )
        val wMap = HansonFirestoreCloudSyncEngine.sanitizeWatcherMap(watcher, vault)
        assertEquals(
            setOf("id", "title", "targetAppOrUrl", "keywords", "mode", "enabled", "hitCount", "updatedAtMs"),
            wMap.keys
        )
        for (forbidden in HansonFirestoreCloudSyncEngine.FORBIDDEN_SECRET_KEYS) {
            assertFalse(wMap.containsKey(forbidden))
        }
        assertFalse(wMap.containsKey("matchKeywords"))
        assertFalse(wMap.containsKey("soldOutKeywords"))
        assertFalse(wMap.containsKey("intervalSec"))
        assertFalse(wMap.containsKey("lastCheckedAtMs"))
        assertFalse(wMap.containsKey("lastHitSummary"))
        assertFalse((wMap["title"] as String).contains("SecretPin9876"))
        assertFalse((wMap["targetAppOrUrl"] as String).contains("AIzaSySecret123456"))
        val wKeywords = (wMap["keywords"] as List<*>).map { it.toString() }
        assertTrue(wKeywords.none { it.contains("SecretPin9876") || it.contains("900101-1234567") })

        val receipt = ActionReceipt(
            id = "rcpt_1",
            toolName = "save_note",
            summaryKo = "메모 저장 SecretPin9876",
            detailKo = "sk-proj-secret12345678",
            undoHintKo = "이전 메모 복원",
            undoToolName = "save_note",
            undoArgsJson = "{\"key\":\"k\",\"content\":\"SecretPin9876\"}"
        )
        val rMap = HansonFirestoreCloudSyncEngine.sanitizeReceiptMap(receipt, vault)
        assertEquals(
            setOf("id", "timestampMs", "toolName", "summaryKo", "detailKo", "undoHintKo", "undoToolName", "undoArgsJson", "undone"),
            rMap.keys
        )
        for (forbidden in HansonFirestoreCloudSyncEngine.FORBIDDEN_SECRET_KEYS) {
            assertFalse(rMap.containsKey(forbidden))
        }
        assertFalse((rMap["summaryKo"] as String).contains("SecretPin9876"))
        assertFalse((rMap["detailKo"] as String).contains("sk-proj-secret12345678"))
        assertFalse((rMap["undoArgsJson"] as String).contains("SecretPin9876"))

        // Verify firestore.rules length and numeric bounds parity across all 4 sanitizers
        val oversizedBubble = HansonFirestoreCloudSyncEngine.sanitizeBubbleMap(
            HumanMemoryBubble(
                id = "b".repeat(200),
                emoji = "🫧".repeat(40),
                category = "C".repeat(80),
                factKo = "가".repeat(700),
                enabled = true,
                updatedAtMs = 999_999_999_999_999L
            ),
            vault
        )
        assertTrue((oversizedBubble["id"] as String).length in 1..128)
        assertTrue((oversizedBubble["emoji"] as String).length in 1..32)
        assertTrue((oversizedBubble["category"] as String).length in 1..32)
        assertTrue((oversizedBubble["factKo"] as String).length in 1..500)
        assertTrue((oversizedBubble["updatedAtMs"] as Long) in 0L..253_402_300_799_999L)

        val oversizedRoutine = HansonFirestoreCloudSyncEngine.sanitizeRoutineMap(
            PersonalRoutine(
                id = "r".repeat(200),
                titleKo = "타".repeat(300),
                emoji = "⚡".repeat(40),
                goalPrompt = "목".repeat(1500),
                sourceBadge = "배".repeat(100),
                pinned = true,
                stepCount = 99,
                createdAtMs = -10L
            ),
            vault
        )
        assertTrue((oversizedRoutine["id"] as String).length in 1..128)
        assertTrue((oversizedRoutine["titleKo"] as String).length in 1..200)
        assertTrue((oversizedRoutine["emoji"] as String).length in 1..32)
        assertTrue((oversizedRoutine["goalPrompt"] as String).length in 1..1000)
        assertTrue((oversizedRoutine["sourceBadge"] as String).length in 1..64)
        assertEquals(50, oversizedRoutine["stepCount"])
        assertEquals(0L, oversizedRoutine["createdAtMs"])

        val oversizedWatcher = HansonFirestoreCloudSyncEngine.sanitizeWatcherMap(
            WatcherRule(
                id = "w".repeat(200),
                title = "감".repeat(300),
                targetAppOrUrl = "u".repeat(700),
                matchKeywords = List(25) { idx -> "k${idx}_" + "키".repeat(120) },
                hitCount = 2_000_000,
                lastCheckedAtMs = 999_999_999_999_999L
            ),
            vault
        )
        assertTrue((oversizedWatcher["id"] as String).length in 1..128)
        assertTrue((oversizedWatcher["title"] as String).length in 1..200)
        assertTrue((oversizedWatcher["targetAppOrUrl"] as String).length <= 500)
        val cappedKeywords = (oversizedWatcher["keywords"] as List<*>).map { it.toString() }
        assertTrue(cappedKeywords.size in 1..20)
        assertTrue(cappedKeywords.all { it.length in 1..100 })
        assertEquals(1_000_000, oversizedWatcher["hitCount"])
        assertEquals(253_402_300_799_999L, oversizedWatcher["updatedAtMs"])
        assertTrue(oversizedWatcher["mode"] in setOf("NOTIFY_ONLY", "HOLD_FOR_APPROVAL"))

        val oversizedReceipt = HansonFirestoreCloudSyncEngine.sanitizeReceiptMap(
            ActionReceipt(
                id = "x".repeat(200),
                timestampMs = -50L,
                toolName = "t".repeat(150),
                summaryKo = "요".repeat(700),
                detailKo = "상".repeat(1500),
                undoHintKo = "되".repeat(700),
                undoToolName = "u".repeat(150),
                undoArgsJson = "j".repeat(1500)
            ),
            vault
        )
        assertTrue((oversizedReceipt["id"] as String).length in 1..128)
        assertEquals(0L, oversizedReceipt["timestampMs"])
        assertTrue((oversizedReceipt["toolName"] as String).length in 1..100)
        assertTrue((oversizedReceipt["summaryKo"] as String).length in 1..500)
        assertTrue((oversizedReceipt["detailKo"] as String).length <= 1000)
        assertTrue((oversizedReceipt["undoHintKo"] as String).length <= 500)
        assertTrue((oversizedReceipt["undoToolName"] as String).length <= 100)
        assertTrue((oversizedReceipt["undoArgsJson"] as String).length <= 1000)
    }

    @Test
    fun firestoreSync_unconfiguredStaysInLocalStandbyWithoutThrowing() {
        val (ctx, _, vault) = createDummySyncEnv("sync_unconfigured")
        val syncEngine = HansonFirestoreCloudSyncEngine(ctx, vault)
        val bubblesStore = HumanMemoryBubblesStore(File(ctx.filesDir, "bubbles.json")).apply { seedDefaultsIfEmpty() }
        val routineStore = PersonalRoutineStore(File(ctx.filesDir, "routines.json")).apply { seedDefaultsIfEmpty() }

        assertFalse(syncEngine.state.value.config.enabled)
        assertFalse(syncEngine.state.value.hasApiKeyInVault)

        val resultState = syncEngine.syncNow(bubblesStore, routineStore)
        assertFalse(resultState.isSyncing)
        assertEquals("BYO Firebase 미설정 — 로컬 전용 저장 중", resultState.lastStatusKo)
    }

    @Test
    fun firestoreSync_saveByoConfigStoresApiKeyStrictlyInVaultNeverInSharedPrefs() {
        val (ctx, prefsMap, vault) = createDummySyncEnv("sync_vault_key")
        val syncEngine = HansonFirestoreCloudSyncEngine(ctx, vault)
        val dummyApiKey = "AIzaSyDemoByoFirestoreKey123456"

        syncEngine.saveByoConfig(
            enabled = true,
            projectId = "demo-byo-project-001",
            applicationId = "1:000000000000:android:demo0001",
            apiKeyPlaintext = dummyApiKey,
            databaseId = "enterprise-db-01",
            editionMode = FirestoreEditionMode.ENTERPRISE,
            userUid = "owner_01"
        )

        val st = syncEngine.state.value
        assertTrue(st.config.enabled)
        assertEquals("demo-byo-project-001", st.config.projectId)
        assertEquals("enterprise-db-01", st.config.databaseId)
        assertEquals(FirestoreEditionMode.ENTERPRISE, st.config.editionMode)
        assertTrue(st.hasApiKeyInVault)
        assertNotNull(vault.getHandleSummary("firestore_sync_api_key"))
        assertEquals(dummyApiKey, vault.resolvePlaintextForBlindInjection("vault://firestore_sync_api_key")?.second)

        // Plain SharedPreferences must NEVER contain the API key anywhere
        val allPrefStrings = prefsMap.entries.joinToString(" ") { "${it.key}=${it.value}" }
        assertFalse("Plain SharedPreferences must NEVER store API key", allPrefStrings.contains(dummyApiKey))
    }

    @Test
    fun firestoreSync_syncNowWhenConfiguredOnJvmDegradesSafelyWithoutPlayServices() {
        val (ctx, _, vault) = createDummySyncEnv("sync_jvm_safe")
        val syncEngine = HansonFirestoreCloudSyncEngine(ctx, vault)
        val bubblesStore = HumanMemoryBubblesStore(File(ctx.filesDir, "bubbles.json")).apply { seedDefaultsIfEmpty() }
        val routineStore = PersonalRoutineStore(File(ctx.filesDir, "routines.json")).apply { seedDefaultsIfEmpty() }

        syncEngine.saveByoConfig(
            enabled = true,
            projectId = "demo-byo-project-002",
            applicationId = "1:000000000000:android:demo0002",
            apiKeyPlaintext = "AIzaSyDemoByoFirestoreKey654321",
            databaseId = "(default)",
            editionMode = FirestoreEditionMode.STANDARD,
            userUid = "jvm_user"
        )

        val outcome = syncEngine.syncNow(bubblesStore, routineStore)
        assertFalse("JVM unit test standby must never leave isSyncing stuck true", outcome.isSyncing)
        assertTrue(outcome.hasApiKeyInVault)
        assertTrue(outcome.lastStatusKo.isNotBlank())
    }

    @Test
    fun firestoreSync_upsertMergedBubblesAndRoutinesPreserveRemoteIdsWithoutDuplicates() {
        val dir = tempFolder.newFolder("sync_upsert_lww_ids")
        val bubblesFile = File(dir, "bubbles.json")
        val routinesFile = File(dir, "routines.json")
        val bubblesStore = HumanMemoryBubblesStore(bubblesFile)
        val routineStore = PersonalRoutineStore(routinesFile)

        bubblesStore.addBubble("카페인은 오전에만 마심", "PREF", "☕")
        routineStore.addRoutine(
            titleKo = "오전 메일 확인",
            goalPrompt = "오늘 수신된 중요 메일을 요약해줘",
            emoji = "📧",
            stepCount = 2
        )

        val remoteBubble1 = HumanMemoryBubble(
            id = "remote_bubble_1",
            emoji = "🥛",
            category = "FOOD",
            factKo = "오트 밀크 라떼를 선호함",
            enabled = false,
            updatedAtMs = 5_000_000_000_000L
        )
        val remoteRoutine1 = PersonalRoutine(
            id = "remote_routine_1",
            titleKo = "퇴근길 음악",
            emoji = "🎧",
            goalPrompt = "유튜브 뮤직에서 재즈 플레이리스트 재생해줘",
            sourceBadge = "클라우드 동기화",
            pinned = true,
            stepCount = 3,
            createdAtMs = 5_000_000_000_000L
        )

        val mergedBubbles = HansonFirestoreCloudSyncEngine.mergeBubblesLww(
            bubblesStore.bubbles.value,
            listOf(remoteBubble1)
        )
        bubblesStore.upsertMergedBubbles(mergedBubbles)

        val mergedRoutines = HansonFirestoreCloudSyncEngine.mergeRoutinesLww(
            routineStore.routines.value,
            listOf(remoteRoutine1)
        )
        routineStore.upsertMergedRoutines(mergedRoutines)

        assertEquals(2, bubblesStore.bubbles.value.size)
        val storedBubble = bubblesStore.bubbles.value.firstOrNull { it.id == "remote_bubble_1" }
        assertNotNull("Remote bubble ID must be preserved verbatim", storedBubble)
        assertEquals("오트 밀크 라떼를 선호함", storedBubble?.factKo)
        assertFalse(storedBubble?.enabled ?: true)
        assertEquals(5_000_000_000_000L, storedBubble?.updatedAtMs)

        assertEquals(2, routineStore.routines.value.size)
        val storedRoutine = routineStore.routines.value.firstOrNull { it.id == "remote_routine_1" }
        assertNotNull("Remote routine ID must be preserved verbatim", storedRoutine)
        assertEquals("퇴근길 음악", storedRoutine?.titleKo)
        assertEquals(3, storedRoutine?.stepCount)
        assertEquals(5_000_000_000_000L, storedRoutine?.createdAtMs)

        // Second sync pass updating same remote IDs must update in-place with zero duplicates
        val updatedRemoteBubble = remoteBubble1.copy(
            factKo = "디카페인 오트 라떼 선호",
            enabled = true,
            updatedAtMs = 5_000_000_005_000L
        )
        val updatedRemoteRoutine = remoteRoutine1.copy(
            titleKo = "퇴근길 재즈",
            stepCount = 4,
            createdAtMs = 5_000_000_005_000L
        )
        bubblesStore.upsertMergedBubbles(
            HansonFirestoreCloudSyncEngine.mergeBubblesLww(
                bubblesStore.bubbles.value,
                listOf(updatedRemoteBubble)
            )
        )
        routineStore.upsertMergedRoutines(
            HansonFirestoreCloudSyncEngine.mergeRoutinesLww(
                routineStore.routines.value,
                listOf(updatedRemoteRoutine)
            )
        )

        val reloadedBubbles = HumanMemoryBubblesStore(bubblesFile).bubbles.value
        val reloadedRoutines = PersonalRoutineStore(routinesFile).routines.value
        assertEquals(2, reloadedBubbles.size)
        assertEquals(1, reloadedBubbles.count { it.id == "remote_bubble_1" })
        assertEquals("디카페인 오트 라떼 선호", reloadedBubbles.first { it.id == "remote_bubble_1" }.factKo)
        assertTrue(reloadedBubbles.first { it.id == "remote_bubble_1" }.enabled)

        assertEquals(2, reloadedRoutines.size)
        assertEquals(1, reloadedRoutines.count { it.id == "remote_routine_1" })
        assertEquals("퇴근길 재즈", reloadedRoutines.first { it.id == "remote_routine_1" }.titleKo)
        assertEquals(4, reloadedRoutines.first { it.id == "remote_routine_1" }.stepCount)
    }
}
