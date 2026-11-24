# Local Harness Override Root (`harness/local/`)

Everything inside `harness/local/` (except this `README.md`) is **git-ignored** so personal preferences, per-device app quirks, and local run notes stay strictly on your machine.

## Directory Convention
- `harness/local/apps/<package_name>/CARD.md` — overrides the shipped `harness/apps/android/<package_name>/CARD.md` when `load_app_card("<package_name>")` is called.
- `harness/local/memory/` — optional local operational notes (never store secrets, tokens, OTPs, or PINs).
- `harness/local/quarantine.jsonl` — optional local flaky-target quarantine overrides for `eval/hanson_eval.py`.
