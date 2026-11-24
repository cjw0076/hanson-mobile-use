# App Card: GitHub (`com.github.android`)
- **Domain**: `PRODUCTIVITY` | **Read-Only Advisory**: `false`
- **Deep Links**: `https://github.com/<owner>/<repo>/pull/<num>` (`resolve_deep_link(...)`).
- **Navigation & Grounding**:
  - Prefer `hanson_secretary_sync.py --github` (`gh api notifications`) over scraping mobile notification feeds when on desktop/bridge.
  - PR review requests, mentions, and CI failure notifications normalize into `DEADLINE_TASK` proposals.
- **Security**:
  - Always redact `ghp_`, `gho_`, `github_pat_`, and pasted API tokens in PR/issue snippets before storing.
