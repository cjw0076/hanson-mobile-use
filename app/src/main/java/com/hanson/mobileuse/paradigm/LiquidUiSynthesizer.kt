package com.hanson.mobileuse.paradigm

import com.hanson.mobileuse.agent.AgentStepLog
import com.hanson.mobileuse.ui.components.GenerativeUiSpec
import com.hanson.mobileuse.ui.components.GenerativeUiStateHolder

/**
 * New Paradigm Pillar 2: Post-App "Liquid UI" & Kinetic Trajectory Synthesizer (`LiquidUiSynthesizer`).
 *
 * Dynamically renders an interactive HTML5/Canvas/JS surface inside the Spatial Glass Command Center
 * that visualizes the live Markov State Trajectory (S_0 -> S_1 -> S_2 -> Goal), ground-truth ΔUI
 * verification telemetry, Self-Healing logs (`인지 -> 복구 -> 재실행`), and 1-tap multi-step trajectory triggers.
 */
object LiquidUiSynthesizer {

    fun updateLiveTrajectoryHud(
        goal: String,
        steps: List<AgentStepLog>,
        worldGraph: WorldModelGraph
    ) {
        val nodeCount = worldGraph.getAllNodes().size
        val edgeCount = worldGraph.getAllEdges().size

        val stepsHtml = if (steps.isEmpty()) {
            """<div class="empty">아직 실행된 행동 궤적이 없습니다. 아래 궤적 프리셋을 탭하거나 목표를 입력하세요.</div>"""
        } else {
            steps.takeLast(5).joinToString("") { step ->
                val isHealed = step.resultSummary.contains("자기수복")
                val badgeClass = when {
                    isHealed -> "badge-healed"
                    step.succeeded -> "badge-ok"
                    else -> "badge-fail"
                }
                val badgeText = when {
                    isHealed -> "SELF-HEALED"
                    step.succeeded -> "VERIFIED OK"
                    else -> "RECOVERING"
                }
                val cleanSummary = step.resultSummary
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .lines()
                    .joinToString("<br/>")
                """
                <div class="step-row">
                  <div class="step-head">
                    <span class="step-num">S${step.stepNumber} · ${step.toolName}</span>
                    <span class="badge $badgeClass">$badgeText</span>
                  </div>
                  <div class="step-body">$cleanSummary</div>
                </div>
                """.trimIndent()
            }
        }

        val safeGoal = goal.replace("<", "&lt;").replace(">", "&gt;").take(42)

        val html = """
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
<style>
  :root {
    --cyan: #38bdf8;
    --emerald: #34d399;
    --violet: #a855f7;
    --amber: #fbbf24;
    --rose: #f87171;
    --text: #f8fafc;
    --muted: #94a3b8;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
  body { background: transparent; color: var(--text); padding: 6px; user-select: none; }
  .top-telemetry {
    display: flex;
    justify-content: space-between;
    align-items: center;
    background: rgba(15, 23, 42, 0.9);
    border: 1px solid rgba(56, 189, 248, 0.35);
    border-radius: 12px;
    padding: 7px 10px;
    margin-bottom: 7px;
    font-size: 11px;
  }
  .macro-row {
    display: grid;
    grid-template-columns: 1fr 1fr 1fr;
    gap: 6px;
    margin-bottom: 8px;
  }
  .macro-btn {
    background: linear-gradient(145deg, rgba(24,34,56,0.92), rgba(13,19,33,0.95));
    border: 1px solid rgba(148, 163, 184, 0.28);
    border-radius: 11px;
    padding: 7px 8px;
    text-align: left;
  }
  .macro-btn:active { border-color: var(--cyan); transform: scale(0.97); }
  .macro-tag { font-size: 9px; font-family: monospace; color: var(--cyan); }
  .macro-title { font-size: 11.5px; font-weight: 700; margin-top: 2px; }
  .stream {
    display: flex;
    flex-direction: column;
    gap: 5px;
    max-height: 138px;
    overflow-y: auto;
  }
  .step-row {
    background: rgba(17, 24, 39, 0.88);
    border-left: 3px solid var(--cyan);
    border-radius: 8px;
    padding: 6px 9px;
  }
  .step-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 2px; }
  .step-num { font-size: 11px; font-family: monospace; font-weight: 700; color: var(--cyan); }
  .badge { font-size: 9px; font-family: monospace; padding: 1px 6px; border-radius: 99px; font-weight: 700; }
  .badge-ok { background: rgba(52, 211, 153, 0.16); color: var(--emerald); }
  .badge-healed { background: rgba(251, 191, 36, 0.18); color: var(--amber); }
  .badge-fail { background: rgba(248, 113, 113, 0.18); color: var(--rose); }
  .step-body { font-size: 10.5px; color: var(--muted); line-height: 1.35; }
  .empty { font-size: 11px; color: var(--muted); padding: 10px; text-align: center; }
</style>
</head>
<body>
  <div class="top-telemetry">
    <span>🧠 World-Model Graph: <b>${nodeCount} States · ${edgeCount} Edges</b></span>
    <span style="color:#34d399;font-family:monospace;">CLOSED-LOOP VERIFIER ON</span>
  </div>

  <div class="macro-row">
    <div class="macro-btn" onclick="runGoal('유튜브 열어서 아이유 검색하고 영상 클릭해줘')">
      <div class="macro-tag">4-STEP 궤적</div>
      <div class="macro-title">🎬 유튜브 검색·재생</div>
    </div>
    <div class="macro-btn" onclick="runGoal('설정 열어서 디스플레이 검색하고 밝기 슬라이더 드래그해줘')">
      <div class="macro-tag">DRAG &amp; DROP</div>
      <div class="macro-title">🎛️ 설정 탐색·드래그</div>
    </div>
    <div class="macro-btn" onclick="runGoal('카카오톡 열어서 나와의 채팅 검색하고 클릭해줘')">
      <div class="macro-tag">FOCUS + TYPE</div>
      <div class="macro-title">💬 카톡 검색·진입</div>
    </div>
  </div>

  <div class="stream">
    $stepsHtml
  </div>

  <script>
    function runGoal(g) {
      if (window.HansonOS) {
        window.HansonOS.runCommand(g);
      }
    }
  </script>
</body>
</html>
        """.trimIndent()

        GenerativeUiStateHolder.publishSpec(
            GenerativeUiSpec(
                id = "kinetic_hud_${steps.size}_${System.currentTimeMillis() / 500}",
                title = "Kinetic Trajectory & World-Model HUD",
                subtitle = if (safeGoal.isNotBlank()) "목표 궤적: $safeGoal" else "실시간 상태 전이 검증 · 3단계 자기수복(인지→복구→재실행)",
                htmlContent = html,
                heightDp = 260
            )
        )
    }
}
