# Contributing to Hanson Mobile-Use

Thank you for contributing to **Hanson Mobile-Use**! Our goal is to build the most reliable, empirically grounded open-source mobile-use runtime for Android.

---

## Development Setup

### Prerequisites
- **JDK 17+** (Android Gradle Plugin 8.x compatible)
- **Android SDK** (`compileSdk = 35`, `minSdk = 26`) with `ANDROID_HOME` configured or set in an untracked `local.properties`
- **Python 3.9+** (stdlib only — used for `mcp/mobile_use_mcp_server.py` and `laptop-cli-bridge.py`)
- **`adb` (Android Platform Tools)** for device/emulator testing

### Build & Run Tests

```bash
# Run all unit & architecture verification tests and assemble the debug APK
./gradlew testDebugUnitTest assembleDebug
```

### Smoke-Test the MCP Server & CLI Bridge

```bash
# Check CLI syntax & help
python mcp/mobile_use_mcp_server.py --help
python laptop-cli-bridge.py --help
```

---

## Core Engineering Principles

1. **Observe → Act → Verify Over Blind Heuristics**:
   - Never mark a multi-step GUI task complete (`complete_task`) without observing post-action screen evidence that the user's goal postcondition has been reached.
   - Prefer `element_index` grounded on `SetOfMarksAnnotator` + `HierarchyDumper` over hardcoded pixel coordinates.
2. **Portable, Clean Tests**:
   - Unit tests under `app/src/test` must pass on any clean clone (`./gradlew testDebugUnitTest`) without requiring a connected physical phone, hardcoded host paths, or pre-built APK artifacts.
3. **Zero Secrets or Personal Data**:
   - Never commit API keys, `.jks` / `.keystore` files, `local.properties`, device serial numbers, personal names, or private chat transcripts.
4. **Preserve Loopback Security**:
   - Any new endpoint added to `ArtemisCommandServer` (`:18888`) or `HansonOsCliMain` (`:18890`) must pass through the existing constant-time token authentication and caller validation gates.

---

## Pull Request Checklist

- [ ] `./gradlew testDebugUnitTest assembleDebug` passes with 0 failures.
- [ ] New tools or bridge endpoints are documented in `README.md` / `mcp/README.md`.
- [ ] No hardcoded device serials, host paths, API keys, or PII are introduced.
