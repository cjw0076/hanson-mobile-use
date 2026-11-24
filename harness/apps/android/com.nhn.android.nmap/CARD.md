# App Card: 네이버 지도 (`com.nhn.android.nmap`)
- **Domain**: `MOBILITY` | **Read-Only Advisory**: `false`
- **Deep Links**: `nmap://search?query=<place>&appname=com.hanson.mobileuse`, `nmap://route/public?...`, `nmap://place?...` (always prefer `open_deep_link` over typing in the map search box!).
- **Navigation & Grounding**:
  - Map canvas overlays floating bottom sheets; check `hidden` vs `offscreen` before tapping collapsed place cards.
  - Use `wait_stable()` after map camera pan/zoom so ETA labels (`도보`, `대중교통`, `자동차`) finish updating.
- **Proactive Secretary**:
  - Primary destination for 1-tap `open_map` actions on `CALENDAR_EVENT` and `RESERVATION_COMMUTE` proposals.
