package com.hanson.mobileuse.paradigm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.hanson.mobileuse.notification.RemoteInputSessionCache
import com.hanson.mobileuse.oscli.HansonOsCliMain
import org.json.JSONObject

/**
 * Candidate representations for Layer 1 Semantic Teleportation.
 */
sealed class SemanticTeleportCandidate {
    data class DeepLinkCandidate(val uri: String) : SemanticTeleportCandidate()
    data class RemoteInputCandidate(val sessionId: String, val replyText: String) : SemanticTeleportCandidate()
    data class OsCliCandidate(val cliCommand: String) : SemanticTeleportCandidate()
    data class IntentCandidate(
        val packageName: String,
        val action: String = Intent.ACTION_VIEW,
        val dataUri: String? = null
    ) : SemanticTeleportCandidate()
}

/**
 * Outcome of a Layer 1 Semantic Teleport attempt.
 */
sealed class TeleportResult {
    data class Success(val stateHash: String, val messageKo: String = "") : TeleportResult()
    data class FallbackToKinetic(val reasonKo: String) : TeleportResult()
}

/**
 * Layer 1: Hybrid Semantic-Kinetic OS Router (`SemanticTeleportRouter`).
 *
 * Evaluates incoming user goals for zero-touch semantic execution:
 * 1. Screen-Off RemoteInput (<100ms background replies without waking screen or launching GUI).
 * 2. DeepLink URI schemes (`kakaotalk://`, `nmap://`, `youtube://`).
 * 3. Privileged OS CLI commands via UID-2000 shell daemon or rootless system properties.
 * 4. Explicit/Implicit Intent launches (`android.intent.action.VIEW`).
 *
 * When no direct semantic shortcut exists, emits `TeleportResult.FallbackToKinetic`
 * to hand off control to Layer 2 Continuous Physical Kinetic Touch.
 */
class SemanticTeleportRouter(private val context: Context) {

    /**
     * Determines whether a goal contains compound instructions beyond just opening an app.
     */
    fun isCompoundGoal(goal: String): Boolean {
        val trimmed = goal.trim()
        return trimmed.contains("해서") || trimmed.contains("하고") ||
            trimmed.contains("검색") || trimmed.contains("클릭") ||
            trimmed.contains("열어서") || trimmed.contains("실행하고") ||
            trimmed.contains("찾아") || trimmed.contains("보내") ||
            trimmed.contains("틀어") || trimmed.contains("재생") ||
            trimmed.split(" ").size > 3
    }

    /**
     * Inspects a high-level goal prompt to identify if a Layer 1 fast-lane shortcut exists.
     */
    fun findFastLaneCandidate(goal: String): SemanticTeleportCandidate? {
        val trimmed = goal.trim()
        if (trimmed.isEmpty()) return null

        // 1. Notification reply pattern: "카톡 ... '...' 답장해줘" or "알림에 '...' 보내줘"
        val notifMatch = Regex("(?:카톡|메시지|알림).*?\"([^\"]+)\".*?답장").find(trimmed)
            ?: Regex("(?:답장|보내).*?\"([^\"]+)\"").find(trimmed)
            ?: Regex("(?:카톡|메시지|알림).*?'([^']+)'.*?답장").find(trimmed)
        if (notifMatch != null) {
            val replyText = notifMatch.groupValues[1]
            val cached = RemoteInputSessionCache.findBestMatch(trimmed)
            if (cached != null && cached.canQuickReply) {
                return SemanticTeleportCandidate.RemoteInputCandidate(cached.sessionId, replyText)
            }
        }

        // 2. Direct URI scheme pattern: "kakaotalk://...", "https://...", "geo:..."
        val uriMatch = Regex("([a-zA-Z0-9_-]+://[^\\s]+)").find(trimmed)
        if (uriMatch != null) {
            return SemanticTeleportCandidate.DeepLinkCandidate(uriMatch.groupValues[1])
        }

        // 3. System setting / OS CLI shortcut
        if (trimmed.contains("와이파이") || trimmed.contains("Wi-Fi") ||
            trimmed.contains("블루투스") || trimmed.contains("비행기 모드")
        ) {
            val cmd = when {
                trimmed.contains("와이파이 켜") -> "setting put global wifi_on 1"
                trimmed.contains("와이파이 끄") -> "setting put global wifi_on 0"
                trimmed.contains("블루투스 켜") -> "setting put global bluetooth_on 1"
                trimmed.contains("블루투스 끄") -> "setting put global bluetooth_on 0"
                trimmed.contains("비행기 모드 켜") -> "setting put global airplane_mode_on 1"
                trimmed.contains("비행기 모드 끄") -> "setting put global airplane_mode_on 0"
                else -> null
            }
            if (cmd != null) return SemanticTeleportCandidate.OsCliCandidate(cmd)
        }

        // 4. Direct App Intent launch
        if (trimmed.startsWith("유튜브 열") || trimmed.startsWith("유튜브 실행")) {
            return SemanticTeleportCandidate.IntentCandidate("com.google.android.youtube")
        }
        if (trimmed.startsWith("카카오톡 열") || trimmed.startsWith("카톡 실행")) {
            return SemanticTeleportCandidate.IntentCandidate("com.kakao.talk")
        }
        if (trimmed.startsWith("설정 열") || trimmed.startsWith("환경설정 실행")) {
            return SemanticTeleportCandidate.IntentCandidate("com.android.settings")
        }

        return null
    }

    /**
     * Executes a detected SemanticTeleportCandidate.
     */
    fun executeTeleport(candidate: SemanticTeleportCandidate): TeleportResult {
        return try {
            when (candidate) {
                is SemanticTeleportCandidate.RemoteInputCandidate -> {
                    executeScreenOffRemoteInput(candidate.sessionId, candidate.replyText)
                }
                is SemanticTeleportCandidate.DeepLinkCandidate -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(candidate.uri)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    TeleportResult.Success("DEEPLINK_LAUNCHED", "[Layer 1 Teleport] 딥링크 실행 성공: ${candidate.uri}")
                }
                is SemanticTeleportCandidate.OsCliCandidate -> {
                    executePrivilegedOsCli(candidate.cliCommand)
                }
                is SemanticTeleportCandidate.IntentCandidate -> {
                    attemptTeleport(
                        targetPackage = candidate.packageName,
                        intentAction = candidate.action,
                        uri = candidate.dataUri?.let { Uri.parse(it) }
                    )
                }
            }
        } catch (e: Exception) {
            TeleportResult.FallbackToKinetic("텔레포트 예외 발생: ${e.message}")
        }
    }

    /**
     * Contract matching PROJECT.md:
     * `attemptTeleport(targetPackage: String, intentAction: String, uri: Uri?, extras: Bundle): TeleportResult`
     */
    fun attemptTeleport(
        targetPackage: String,
        intentAction: String = Intent.ACTION_VIEW,
        uri: Uri? = null,
        extras: Bundle = Bundle()
    ): TeleportResult {
        return try {
            val pm = context.packageManager
            val launchIntent = if (uri != null || intentAction != Intent.ACTION_VIEW) {
                Intent(intentAction).apply {
                    setPackage(targetPackage)
                    data = uri
                    putExtras(extras)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                pm.getLaunchIntentForPackage(targetPackage)?.apply {
                    putExtras(extras)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            if (launchIntent != null) {
                context.startActivity(launchIntent)
                TeleportResult.Success(
                    stateHash = "INTENT_${targetPackage.substringAfterLast('.')}",
                    messageKo = "[Layer 1 Intent Teleport] 앱 인텐트 실행 완료: $targetPackage"
                )
            } else {
                TeleportResult.FallbackToKinetic("패키지 인텐트 생성 불가: $targetPackage")
            }
        } catch (e: Exception) {
            TeleportResult.FallbackToKinetic("인텐트 실행 실패: ${e.message}")
        }
    }

    /**
     * Executes screen-off background remote reply via RemoteInputSessionCache.
     */
    fun executeScreenOffRemoteInput(sessionId: String, replyText: String): TeleportResult {
        val (ok, message) = RemoteInputSessionCache.sendQuickReply(context, sessionId, replyText)
        return if (ok) {
            TeleportResult.Success("REMOTE_INPUT_OK", message)
        } else {
            TeleportResult.FallbackToKinetic(message)
        }
    }

    /**
     * Executes privileged OS CLI command via HansonOsCliMain.
     */
    fun executePrivilegedOsCli(command: String): TeleportResult {
        return try {
            val parts = command.split(" ").filter { it.isNotBlank() }
            val action = parts.firstOrNull() ?: "status"
            val args = parts.drop(1)
            val json = HansonOsCliMain.executeCommandJson(action, args)
            val status = json.optString("status")
            if (status == "ok" || json.optInt("exitCode", 0) == 0) {
                TeleportResult.Success("OS_CLI_OK", "[Layer 1 Teleport] OS CLI 실행 성공: $command")
            } else {
                TeleportResult.FallbackToKinetic("OS CLI 반환 오류: ${json.optString("error", json.toString())}")
            }
        } catch (e: Exception) {
            TeleportResult.FallbackToKinetic("OS CLI 실행 예외: ${e.message}")
        }
    }
}
