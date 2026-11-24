# Shipped Android App Cards (`harness/apps/index.md`)

Load any card programmatically via `load_app_card("<package>")`. If `harness/local/apps/<package>/CARD.md` exists, it overrides the shipped card automatically.

| Package | App | Domain | Read-Only Hardline | Card |
| :--- | :--- | :--- | :-: | :--- |
| `com.kakao.talk` | 카카오톡 (KakaoTalk) | `MESSENGER` | No (HITL send gate) | [`CARD.md`](android/com.kakao.talk/CARD.md) |
| `com.Slack` | Slack | `WORK_CHAT` | No (HITL send gate) | [`CARD.md`](android/com.Slack/CARD.md) |
| `com.google.android.gm` | Gmail | `EMAIL` | No (HITL send gate) | [`CARD.md`](android/com.google.android.gm/CARD.md) |
| `com.nhn.android.nmap` | 네이버 지도 (Naver Map) | `MOBILITY` | No | [`CARD.md`](android/com.nhn.android.nmap/CARD.md) |
| `com.kakao.taxi` | 카카오 T (Kakao T) | `MOBILITY` | Checkout HITL | [`CARD.md`](android/com.kakao.taxi/CARD.md) |
| `com.coupang.mobile` | 쿠팡 (Coupang) | `DELIVERY` | Checkout HITL | [`CARD.md`](android/com.coupang.mobile/CARD.md) |
| `com.sampleapp` | 배달의민족 (Baemin) | `DELIVERY` | Checkout HITL | [`CARD.md`](android/com.sampleapp/CARD.md) |
| `kr.co.wadcorp.catchtable` | 캐치테이블 (CatchTable) | `RESERVATION` | Booking HITL | [`CARD.md`](android/kr.co.wadcorp.catchtable/CARD.md) |
| `com.korail.talk` | 코레일톡 (KorailTalk) | `RESERVATION` | Payment HITL | [`CARD.md`](android/com.korail.talk/CARD.md) |
| `viva.republica.toss` | 토스 (Toss) | `FINANCE_ADVISORY` | **YES (`readOnlyAdvisoryOnly`)** | [`CARD.md`](android/viva.republica.toss/CARD.md) |
| `com.kakaobank.channel` | 카카오뱅크 (KakaoBank) | `FINANCE_ADVISORY` | **YES (`readOnlyAdvisoryOnly`)** | [`CARD.md`](android/com.kakaobank.channel/CARD.md) |
| `com.samsung.android.messaging` | 삼성 메시지 (Samsung SMS) | `MESSENGER` | OTP Reject | [`CARD.md`](android/com.samsung.android.messaging/CARD.md) |
| `com.github.android` | GitHub | `PRODUCTIVITY` | No | [`CARD.md`](android/com.github.android/CARD.md) |
| `notion.id` | Notion | `PRODUCTIVITY` | No | [`CARD.md`](android/notion.id/CARD.md) |
