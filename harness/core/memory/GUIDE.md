# Operational Memory Guide (`harness/core/memory/GUIDE.md`)

Keep persistent task memory transparent, editable, and strictly free of secrets.

## Storage Locations
- **On-Device**: `HumanMemoryBubblesStore` (user-inspectable/deletable bubbles in the Hanson Android app).
- **Local Harness Overrides** (git-ignored):
  - `harness/local/apps/<package>/CARD.md` — per-device UI quirks, custom deep links, or account-specific navigation tips (automatically overrides shipped `harness/apps/android/<package>/CARD.md` via `load_app_card(pkg)`).
  - `harness/local/memory/notes.md` — concise operational notes (preferred transit hub, frequent delivery address label, quiet hours).

## Rules
1. Store **durable preferences and verified UI patterns**, never transient session noise.
2. Never record passwords, OTPs, API keys, card numbers, RRNs, or door/locker PINs in any memory file.
3. Prefer small surgical edits over full rewrites so the user can inspect and delete individual entries anytime.
