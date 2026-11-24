package com.hanson.mobileuse.agent

import com.hanson.mobileuse.notification.InterceptedNotification
import com.hanson.mobileuse.notification.NotificationStormShieldEngine
import com.hanson.mobileuse.notification.NotificationThreadBuffer
import com.hanson.mobileuse.notification.RemoteInputSessionCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Comprehensive JVM JUnit4 test suite for Track E White-Space Life-Care Engines:
 * #1 CommitmentDebtTrackerEngine ("내가 한 약속 추적기")
 * #2 SubscriptionAndExpiryDefenseEngine ("무료체험·자동결제 해지 방어막 + 7일 반품기한 가드")
 * #3 NotificationStormShieldEngine ("알림 폭풍 집중 방패 + 복귀 3줄 브리프")
 * #4 SmishingAndPlainKoreanEngine ("스미싱·사칭 사전 방어 + 어려운 안내문 1줄 통역")
 * #5 RelationshipCareCadenceEngine ("인간관계·안부 텀 케어")
 * #6 AtomicRollbackAndExplainEngine + ActionTransactionLedger ("원자적 일괄 되돌리기 + 증거 카드")
 * Bonus: CrossLabPortabilityPack (AGENTS.md-compatible open Markdown export/import)
 */
class WhiteSpaceLifeCareEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val seoulZone: ZoneId = ZoneId.of("Asia/Seoul")
    private val refMs: Long = ZonedDateTime.of(2026, 11, 24, 14, 0, 0, 0, seoulZone).toInstant().toEpochMilli()
    private lateinit var storeDir: File

    @Before
    fun setUp() {
        storeDir = tempFolder.newFolder("whitespace_store")
        ProactiveSecretaryEngine.resetForTesting()
        ProactiveSecretaryEngine.configureForTesting(storeDir, clearExisting = true)
        CommitmentDebtTrackerEngine.resetForTesting()
        CommitmentDebtTrackerEngine.configureForTesting(storeDir, clearExisting = true)
        RelationshipCareCadenceEngine.resetForTesting()
        RelationshipCareCadenceEngine.configureForTesting(storeDir, clearExisting = true)
        ActionTransactionLedger.resetForTesting()
        ActionTransactionLedger.configureForTesting(storeDir, clearExisting = true)
        PrivacyAndQuotaProofLedger.resetForTesting()
        PrivacyAndQuotaProofLedger.configureForTesting(storeDir, clearExisting = true)
        NotificationStormShieldEngine.resetForTesting()
        NotificationThreadBuffer.clearAll()
        RemoteInputSessionCache.clearAll()
        A2uiSurfaceRegistry.reset()
        DynamicUiEventBus.reset()
    }

    @After
    fun tearDown() {
        ProactiveSecretaryEngine.resetForTesting()
        CommitmentDebtTrackerEngine.resetForTesting()
        RelationshipCareCadenceEngine.resetForTesting()
        ActionTransactionLedger.resetForTesting()
        PrivacyAndQuotaProofLedger.resetForTesting()
        NotificationStormShieldEngine.resetForTesting()
        NotificationThreadBuffer.clearAll()
        RemoteInputSessionCache.clearAll()
        A2uiSurfaceRegistry.reset()
        DynamicUiEventBus.reset()
    }

    private fun makeNotif(
        pkg: String,
        appLabel: String,
        sender: String,
        room: String,
        text: String,
        ts: Long = refMs,
        canQuickReply: Boolean = false
    ): InterceptedNotification = InterceptedNotification(
        sessionId = "ws_${pkg.substringAfterLast('.')}_$ts",
        notificationKey = "ws::$pkg::$room::$ts",
        packageName = pkg,
        appLabel = appLabel,
        sender = sender,
        roomOrTitle = room,
        messageText = text,
        timestampMs = ts,
        canQuickReply = canQuickReply
    )

    // =========================================================================
    // #1. CommitmentDebtTrackerEngine
    // =========================================================================

    @Test
    fun commitmentDebtTracker_capturesPromisesUpdatesPrecedenceRejectsHedgesAndEnforcesFinanceReadOnly() {
        // 1. Soft hedges MUST be rejected
        assertNull(
            CommitmentDebtTrackerEngine.onSelfMessageObserved(
                context = null,
                packageName = "com.kakao.talk",
                roomOrTitle = "김민수",
                counterparty = "김민수",
                selfText = "언젠가 나중에 밥 한번 먹자!",
                timestampMs = refMs,
                zoneId = seoulZone
            )
        )
        assertNull(
            CommitmentDebtTrackerEngine.onSelfMessageObserved(
                context = null,
                packageName = "com.Slack",
                roomOrTitle = "#general",
                counterparty = "Alex",
                selfText = "maybe someday we can look at it",
                timestampMs = refMs,
                zoneId = seoulZone
            )
        )

        // 2. Real 1st-person commitment captured + deadline precedence update in place
        val p1 = CommitmentDebtTrackerEngine.onSelfMessageObserved(
            context = null,
            packageName = "com.kakao.talk",
            roomOrTitle = "박지현",
            counterparty = "박지현",
            selfText = "내일까지 디자인 시안 정리해서 보내드릴게요!",
            timestampMs = refMs,
            zoneId = seoulZone
        )
        assertNotNull(p1)
        assertEquals(ProactiveProposalType.SELF_COMMITMENT_DEBT, p1!!.type)
        assertFalse(p1.readOnlyAdvisory)
        assertEquals(1, CommitmentDebtTrackerEngine.listOpenCommitments().size)

        val p2 = CommitmentDebtTrackerEngine.onSelfMessageObserved(
            context = null,
            packageName = "com.kakao.talk",
            roomOrTitle = "박지현",
            counterparty = "박지현",
            selfText = "금요일 오후 5시까지 수정해서 다시 보내드릴게요",
            timestampMs = refMs + 60_000L,
            zoneId = seoulZone
        )
        assertNotNull(p2)
        assertEquals("Updated commitment deadline must update existing open commitment in place", 1, CommitmentDebtTrackerEngine.listOpenCommitments().size)
        assertEquals(p1.id, p2!!.id)

        // 3. A2UI v1.0 surface round-trip
        val surfId = CommitmentDebtTrackerEngine.publishToA2uiRegistry(p2)
        val surface = A2uiSurfaceRegistry.getSurface(surfId)
        assertNotNull(surface)
        assertTrue(surface!!.hasRootComponent)
        assertNotNull(surface.components["cd_done_btn"])
        assertNotNull(surface.components["cd_draft_btn"])
        assertNotNull(surface.components["cd_resched_btn"])

        // 4. Financial promise ("입금할게") MUST force readOnlyAdvisory = true
        val finProp = CommitmentDebtTrackerEngine.onSelfMessageObserved(
            context = null,
            packageName = "com.kakao.talk",
            roomOrTitle = "회식 정산방",
            counterparty = "최총무",
            selfText = "오늘 밤에 정산금 입금할게요!",
            timestampMs = refMs,
            zoneId = seoulZone
        )
        assertNotNull(finProp)
        assertEquals(ProactiveProposalType.SELF_COMMITMENT_DEBT, finProp!!.type)
        assertTrue("Financial self-promise MUST force readOnlyAdvisory = true", finProp.readOnlyAdvisory)

        // 5. RemoteInputSessionCache.markReplied automatically feeds CommitmentDebtTrackerEngine
        val sess = makeNotif("com.kakao.talk", "카카오톡", "이서연", "이서연", "자료 언제 줄 수 있어?", canQuickReply = true)
        RemoteInputSessionCache.put(sess)
        RemoteInputSessionCache.markReplied(sess.sessionId, "오늘 밤에 확인해볼게!")
        assertTrue(CommitmentDebtTrackerEngine.listOpenCommitments().any { it.counterparty == "이서연" })

        // 6. Mark done closes commitment
        val done = CommitmentDebtTrackerEngine.markDone(p2.id, refMs + 120_000L)
        assertNotNull(done)
        assertTrue(done!!.completed)
    }

    // =========================================================================
    // #2. SubscriptionAndExpiryDefenseEngine
    // =========================================================================

    @Test
    fun subscriptionDefenseEngine_detectsFreeTrialHiddenAddOnD2AlarmAndReturnWindowGuard() {
        // 1. Free-trial + hidden add-on trap -> SUBSCRIPTION_TRIAL_DEFENSE, readOnlyAdvisory = true
        val trialNotif = makeNotif(
            pkg = "com.google.android.gm",
            appLabel = "Gmail",
            sender = "CloudPDF AI",
            room = "7일 무료체험 시작 안내",
            text = "7일 무료 체험이 시작되었습니다. 종료 후 AI Assistant add-on 포함 자동 갱신됩니다."
        )
        val emitted = ProactiveSecretaryEngine.onNotificationPosted(
            context = null,
            notification = trialNotif,
            referenceTimeMs = refMs,
            zoneId = seoulZone
        )
        assertNotNull(emitted)
        assertEquals(ProactiveProposalType.SUBSCRIPTION_TRIAL_DEFENSE, emitted!!.type)
        assertTrue("Subscription defense MUST strictly be readOnlyAdvisory = true", emitted.readOnlyAdvisory)

        val bundle = SubscriptionAndExpiryDefenseEngine.detectTrialOrRenewal(
            notification = trialNotif,
            sanitizedText = trialNotif.messageText,
            sourceApp = "Gmail",
            referenceTimeMs = refMs,
            zoneId = seoulZone
        )!!
        assertTrue(bundle.cancelChecklistKo.any { it.contains("숨은 애드온 구독 확인") })
        assertEquals(SubscriptionAndExpiryDefenseEngine.PLAY_STORE_SUBSCRIPTIONS_URL, bundle.subscriptionsUrl)
        assertTrue(bundle.refundDraftKo.contains("청약철회"))

        // Expected D-2 alarm = 2026-11-24 + 7d - 2d = 2026-11-29 10:00 KST
        val expectedD2 = ZonedDateTime.of(2026, 11, 29, 10, 0, 0, 0, seoulZone).toInstant().toEpochMilli()
        assertEquals(expectedD2, bundle.d2AlarmTimeMs)

        // A2UI surface round-trip
        val surfId = SubscriptionAndExpiryDefenseEngine.publishToA2uiRegistry(bundle)
        val surface = A2uiSurfaceRegistry.getSurface(surfId)
        assertNotNull(surface)
        assertNotNull(surface!!.components["td_alarm_btn"])
        assertNotNull(surface.components["td_subs_btn"])
        assertNotNull(surface.components["td_refund_btn"])

        // 2. E-commerce "배송 완료" -> D-5 return window guard (7-day statutory window)
        val delivNotif = makeNotif(
            pkg = "com.coupang.mobile",
            appLabel = "쿠팡",
            sender = "쿠팡",
            room = "쿠팡",
            text = "[쿠팡] 로켓배송 배송 완료되었습니다. (공동현관 비번 #7788*)"
        )
        val retBundle = SubscriptionAndExpiryDefenseEngine.detectReturnWindowGuard(
            notification = delivNotif,
            sanitizedText = delivNotif.messageText,
            sourceApp = "쿠팡",
            referenceTimeMs = refMs,
            zoneId = seoulZone
        )
        assertNotNull(retBundle)
        assertTrue(retBundle!!.isReturnWindowGuard)
        assertTrue(retBundle.proposal.readOnlyAdvisory)
        assertFalse("Return guard must NEVER store door PIN", retBundle.proposal.toJson().toString().contains("7788"))
    }

    // =========================================================================
    // #3. NotificationStormShieldEngine
    // =========================================================================

    @Test
    fun notificationStormShield_holdsBurstAlertsAllowsVipAndEmergencyAndBuilds3LineDigest() {
        NotificationStormShieldEngine.startFocusBlock(
            vipNames = setOf("어머니", "김팀장"),
            burstThreshold = 4,
            burstWindowMs = 180_000L
        )

        // 1. First 3 messages below threshold -> not snoozed
        for (i in 1..3) {
            val d = NotificationStormShieldEngine.evaluateIncoming(
                packageName = "com.kakao.talk",
                roomKey = "사내 수다방",
                sender = "동료$i",
                text = "점심 뭐 먹지 $i",
                nowMs = refMs + i * 1000L
            )
            assertFalse(d.shouldSnooze)
        }

        // 2. Messages 4..7 exceed burst threshold -> held locally & snoozed!
        val d4 = NotificationStormShieldEngine.evaluateIncoming("com.kakao.talk", "사내 수다방", "박대리", "금요일 회식 강남역으로 확정되었습니다!", refMs + 4000L)
        val d5 = NotificationStormShieldEngine.evaluateIncoming("com.kakao.talk", "사내 수다방", "이과장", "참석 가능 여부 확인 부탁드립니다?", refMs + 5000L)
        val d6 = NotificationStormShieldEngine.evaluateIncoming("com.kakao.talk", "사내 수다방", "최사원", "오 좋네요 기대돼요", refMs + 6000L)
        val d7 = NotificationStormShieldEngine.evaluateIncoming("com.kakao.talk", "사내 수다방", "정사원", "인증번호 482910", refMs + 7000L) // secret stripped!
        assertTrue(d4.shouldSnooze)
        assertTrue(d5.shouldSnooze)
        assertTrue(d6.shouldSnooze)
        assertTrue(d7.shouldSnooze)

        // 3. VIP & Emergency MUST ALWAYS bypass shield even during active storm!
        val vipDecision = NotificationStormShieldEngine.evaluateIncoming("com.kakao.talk", "사내 수다방", "어머니", "집에 도착했니?", refMs + 8000L)
        assertFalse("VIP must NEVER be snoozed", vipDecision.shouldSnooze)
        assertTrue(vipDecision.bypassedByVipOrEmergency)

        val emergencyDecision = NotificationStormShieldEngine.evaluateIncoming("com.kakao.talk", "사내 수다방", "낯선사람", "긴급 병원 연락 필요합니다", refMs + 9000L)
        assertFalse("Emergency keyword must NEVER be snoozed", emergencyDecision.shouldSnooze)
        assertTrue(emergencyDecision.bypassedByVipOrEmergency)

        val mentionDecision = NotificationStormShieldEngine.evaluateIncoming("com.kakao.talk", "사내 수다방", "동료9", "@한손 지금 확인 가능해?", refMs + 9500L)
        assertFalse("@mention must bypass shield", mentionDecision.shouldSnooze)
        assertTrue(mentionDecision.bypassedByVipOrEmergency)

        // 4. Flush into deterministic 3-Line Re-Entry Digest + A2UI v1.0 surface
        val digestProp = ProactiveSecretaryEngine.flushFocusShieldDigest(sessionId = "sess_focus_1", nowMs = refMs + 10_000L, publishToA2ui = true)
        assertNotNull(digestProp)
        assertEquals(ProactiveProposalType.FOCUS_REENTRY_DIGEST, digestProp!!.type)
        assertTrue(digestProp.detailKo.contains("1. 확정·결정 사항"))
        assertTrue(digestProp.detailKo.contains("2. 내 확인·답장 필요"))
        assertTrue(digestProp.detailKo.contains("3. 일반 잡담"))
        assertFalse("Secret OTP must NEVER be stored inside focus digest", digestProp.toJson().toString().contains("482910"))

        val surf = A2uiSurfaceRegistry.getSurface("focus_digest_sess_focus_1")
        assertNotNull(surf)
        assertNotNull(surf!!.components["fd_ack_btn"])
        assertNotNull(surf.components["fd_ext_btn"])
        assertNotNull(surf.components["fd_vip_btn"])
    }

    // =========================================================================
    // #4. SmishingAndPlainKoreanEngine
    // =========================================================================

    @Test
    fun smishingAndPlainKoreanEngine_flagsScamsAllowsOfficialGoKrAndTranslatesDenseNotices() {
        // 1. High-risk obituary / international shortlink smishing -> SCAM_PRE_SHIELD (priority 100)
        val scamNotif = makeNotif(
            pkg = "com.samsung.android.messaging",
            appLabel = "메시지(SMS)",
            sender = "미등록번호",
            room = "미등록번호",
            text = "[국외발신] 모바일 부고장 안내 확인 http://bit.ly/fake-obituary-apk"
        )
        val report = SmishingAndPlainKoreanEngine.scoreSmishingRisk(scamNotif.sender, scamNotif.messageText)
        assertTrue("Smishing score must be high risk (>=55)", report.isHighRisk && report.score >= 80)

        val scamProp = ProactiveSecretaryEngine.onNotificationPosted(
            context = null,
            notification = scamNotif,
            referenceTimeMs = refMs,
            zoneId = seoulZone
        )
        assertNotNull(scamProp)
        assertEquals(ProactiveProposalType.SCAM_PRE_SHIELD, scamProp!!.type)
        assertEquals(100, scamProp.type.priorityWeight)
        assertTrue(scamProp.readOnlyAdvisory)
        assertFalse("Suspicious raw URL must be masked in quotedExcerpt", scamProp.quotedExcerpt.contains("bit.ly"))

        // High-contrast monochrome A2UI warning card round-trip
        val scamA2ui = SmishingAndPlainKoreanEngine.toScamShieldA2uiJsonl(scamProp, report)
        val ingestRes = A2uiSurfaceRegistry.ingestJsonl(scamA2ui)
        assertEquals(3, ingestRes.size)
        assertTrue(ingestRes.all { it is A2uiIngestResult.Applied })

        // 2. Family impersonation + remote-control lure -> high risk even without URL
        val familyLure = SmishingAndPlainKoreanEngine.scoreSmishingRisk("엄마", "폰 액정이 깨져서 그러는데 AnyDesk 원격 설치하고 대신 송금 좀 해줘")
        assertTrue(familyLure.isHighRisk)

        // 3. Official .go.kr URL allowlist -> NOT flagged as smishing; translated via Plain-Korean Notice Translator!
        val govNotif = makeNotif(
            pkg = "com.samsung.android.messaging",
            appLabel = "메시지(SMS)",
            sender = "국세청",
            room = "국세청",
            text = "[국세청] 종합소득세 기한 후 신고 및 본인부담 세액 안내 11월 30일 마감 https://www.hometax.go.kr/main"
        )
        val govRisk = SmishingAndPlainKoreanEngine.scoreSmishingRisk(govNotif.sender, govNotif.messageText)
        assertFalse(".go.kr official notice must NOT be flagged as smishing", govRisk.isHighRisk)
        assertTrue(govRisk.officialGoKrAllowed)

        val plainProp = ProactiveSecretaryEngine.onNotificationPosted(
            context = null,
            notification = govNotif,
            referenceTimeMs = refMs,
            zoneId = seoulZone
        )
        assertNotNull(plainProp)
        assertEquals(ProactiveProposalType.PLAIN_NOTICE_TRANSLATION, plainProp!!.type)
        assertTrue(plainProp.summaryKo.contains("쉬운 정리"))
    }

    // =========================================================================
    // #5. RelationshipCareCadenceEngine
    // =========================================================================

    @Test
    fun relationshipCareCadence_tracksLocalVipsLifeEventsAndNeverAutoSends() {
        val tenDaysAgo = refMs - 10L * 86_400_000L
        val vip = RelationshipCareCadenceEngine.upsertVip(
            VipContactCadence(
                id = "vip_mom",
                name = "어머니",
                relationLabelKo = "가족",
                cadenceDays = 7,
                preferredPackage = "com.kakao.talk",
                phoneNumber = "010-0000-0001",
                lastContactMs = tenDaysAgo
            )
        )
        assertNotNull(vip)

        // Record a life-event cue on contact touch (keeps older timestamp if older, updates cues)
        val touched = RelationshipCareCadenceEngine.recordContactTouch(
            contactName = "어머니",
            messageText = "다음 주 화요일 병원 진료 예약 잡았어",
            timestampMs = tenDaysAgo
        )
        assertNotNull(touched)
        assertTrue(touched!!.lifeEventCues.any { it.contains("병원") })

        // Scan overdue VIPs -> calm nudge with readOnlyAdvisory=true & ZERO SEND_QUICK_REPLY auto-send!
        val nudges = RelationshipCareCadenceEngine.scanOverdueVips(refMs)
        assertEquals(1, nudges.size)
        val nudge = nudges.first()
        assertEquals(ProactiveProposalType.RELATIONSHIP_CARE_NUDGE, nudge.type)
        assertTrue(nudge.readOnlyAdvisory)
        assertFalse(
            "Relationship care MUST NEVER auto-send messages",
            nudge.primaryAction.kind == ProposalActionKind.SEND_QUICK_REPLY
        )

        val a2uiRes = A2uiSurfaceRegistry.ingestJsonl(RelationshipCareCadenceEngine.toCareA2uiJsonl(nudge, touched))
        assertEquals(3, a2uiRes.size)

        // Reload persistence check
        RelationshipCareCadenceEngine.configureForTesting(storeDir, clearExisting = false)
        assertEquals(1, RelationshipCareCadenceEngine.listVips().size)
    }

    // =========================================================================
    // #6. AtomicRollbackAndExplainEngine + ActionTransactionLedger + Evidence Card
    // =========================================================================

    @Test
    fun atomicRollbackLedger_verifiesHashChainReverseOrderRollbackHonestIrreversibleAndEvidenceCard() {
        val steps = listOf(
            TransactionStepRecord(
                stepId = "st_1_cal",
                toolName = "manage_calendar",
                targetResourceId = "event_901",
                reversibility = ReversibilityClass.REVERSIBLE,
                preStateJson = """{"exists":false}""",
                postStateJson = """{"eventId":901}""",
                undoCommand = "delete_calendar_event:901",
                readbackVerified = true,
                readbackDetailKo = "CalendarContract eventId=901 확인"
            ),
            TransactionStepRecord(
                stepId = "st_2_note",
                toolName = "scratchpad_note",
                targetResourceId = "memo_meeting",
                reversibility = ReversibilityClass.REVERSIBLE,
                preStateJson = """{"previousValue":null}""",
                postStateJson = """{"value":"강남역 11번 출구"}""",
                undoCommand = "remove_note:memo_meeting",
                readbackVerified = true
            ),
            TransactionStepRecord(
                stepId = "st_3_reply",
                toolName = "reply_notification",
                targetResourceId = "kakao_room_1",
                reversibility = ReversibilityClass.IRREVERSIBLE_EXTERNAL,
                postStateJson = """{"sent":"좋아요 그때 봬요"}""",
                readbackVerified = true
            )
        )

        val bundle = ActionTransactionLedger.recordVerifiedTransaction(
            txId = "tx_bundle_1",
            proposalId = "prop_1",
            summaryKo = "캘린더 등록 + 메모 + 빠른 답장",
            steps = steps,
            nowMs = refMs
        )
        assertEquals("VERIFIED_DONE", bundle.status)
        assertTrue("Hash chain must verify cleanly", ActionTransactionLedger.verifyHashChain())

        // Execute reverse-order rollback
        val executedUndoOrder = mutableListOf<String>()
        val report = ActionTransactionLedger.rollbackTransaction("tx_bundle_1", executor = { step ->
            executedUndoOrder.add(step.stepId)
            true
        }, nowMs = refMs + 5000L)
        assertNotNull(report)
        assertEquals("Reversible steps must execute in exact reverse order", listOf("st_2_note", "st_1_cal"), executedUndoOrder)
        assertEquals(listOf("st_3_reply"), report!!.irreversibleStepIds)
        assertTrue(report.summaryKo.contains("자동 회수 불가"))
        assertTrue("Hash chain must remain valid after rollback append", ActionTransactionLedger.verifyHashChain())

        // Reload JSONL ledger from disk and re-verify SHA-256 chain
        ActionTransactionLedger.configureForTesting(storeDir, clearExisting = false)
        assertEquals(2, ActionTransactionLedger.listTransactions().size)
        assertTrue(ActionTransactionLedger.verifyHashChain())

        // Verify inline "왜 제안했나요?" Evidence Card in ProactiveProposal.toJson() & A2UI bridge
        val calProp = ProactiveSecretaryEngine.onNotificationPosted(
            context = null,
            notification = makeNotif("com.kakao.talk", "카카오톡", "김민수", "대학동기방", "금요일 오후 7시 강남역 11번 출구 스타벅스로 확정!"),
            referenceTimeMs = refMs,
            zoneId = seoulZone
        )!!
        val wire = calProp.toJson()
        assertTrue("Wire JSON must embed 'evidence' card", wire.has("evidence"))
        assertEquals(0, wire.getJSONObject("evidence").getInt("cloudUploadCount"))
        assertEquals(ReversibilityClass.REVERSIBLE.name, wire.getJSONObject("evidence").getString("reversibility"))

        val surfId = ProactiveProposalToA2uiBridge.publishToA2uiRegistry(calProp)
        val surf = A2uiSurfaceRegistry.getSurface(surfId)!!
        assertNotNull("A2UI surface must render inline Evidence Card", surf.components["sec_evidence_card"])
        val evText = A2uiDataBindingResolver.resolveString(surf.components["sec_evidence_txt"]!!.properties["text"], surf.dataModel)
        assertTrue(evText.contains("왜 제안했나요?"))
        assertTrue(evText.contains("클라우드 전송 0건"))
    }

    // =========================================================================
    // Bonus: CrossLabPortabilityPack (AGENTS.md-Compatible Open Markdown)
    // =========================================================================

    @Test
    fun crossLabPortabilityPack_exportsAndImportsOpenMarkdownWhileStrippingSecrets() {
        val bubbles = listOf(
            HumanMemoryBubble("b1", "☕", "FOOD", "아이스 아메리카노는 연하게 마셔요", enabled = true),
            HumanMemoryBubble("b2", "🔐", "PREF", "AIzaSyLeakMustBeBlocked1234567", enabled = true)
        )
        val routines = listOf(
            PersonalRoutine("r1", "퇴근 브리핑", "🗞️", "오늘 일정과 알림 요약해줘", "내 루틴", true)
        )
        val vips = listOf(
            VipContactCadence("vip_1", "어머니", "가족", cadenceDays = 7)
        )
        val beats = listOf(
            RoutineScheduleSpec("beat_1", "평일 아침 브리핑", "오늘 일정 요약", BeatFrequency.WEEKDAYS_AT, 8, 0)
        )

        val exported = CrossLabPortabilityPack.exportMarkdown(bubbles, routines, vips, beats)
        assertTrue(exported.contains("아이스 아메리카노는 연하게 마셔요"))
        assertTrue(exported.contains("퇴근 브리핑 :: 오늘 일정과 알림 요약해줘"))
        assertTrue(exported.contains("어머니 | 7d | 가족"))
        assertTrue(exported.contains("평일 아침 브리핑"))
        assertFalse("Secrets MUST NEVER appear in exported Markdown", exported.contains("AIzaSy"))

        val injectedMd = exported + "\n## Memory Bubbles\n- [PREF] sk-proj-secretKey1234567890\n"
        val imported = CrossLabPortabilityPack.parseImportedMarkdown(injectedMd)
        assertEquals(listOf("아이스 아메리카노는 연하게 마셔요"), imported.bubbles)
        assertEquals(listOf("퇴근 브리핑" to "오늘 일정과 알림 요약해줘"), imported.routines)
        assertEquals(listOf("어머니" to 7), imported.vipNamesAndDays)
        assertEquals(listOf("평일 아침 브리핑"), imported.beatTitles)
        assertEquals(1, imported.skippedSecretLines)
    }
}
