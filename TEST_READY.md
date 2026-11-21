# TEST SUITE OVERVIEW: 4-Tier Unit & Integration Verification

**Project**: `hanson-mobile-use`  
**Test Suite Paths**: `app/src/test/java/com/hanson/mobileuse/` & `app/src/test/java/com/hanson/mobileuse/lifestyle/`  
**Status**: **VERIFIED & PORTABLE (Zero Host-Path or Device-Serial Coupling)**  

---

## 1. Suite Architecture

The automated test suite validates the Android Mobile-Use Engine, Set-of-Marks (`[index]`) visual grounding, multi-provider VLM routing, session memory persistence, and pure achromatic UI ergonomics across four tiers:

- **Tier 1 (Feature Coverage)**: 100 tests across 20 core mobile-use & companion features
- **Tier 2 (Boundary & Corner Cases)**: 100 boundary, malformed input, and concurrency stress tests
- **Tier 3 (Cross-Feature Combinations)**: 20 pairwise inter-module interaction tests
- **Tier 4 (End-to-End Scenarios)**: 10 multi-turn user journey tests
- **Core Engine & Security Suites**: Set-of-Marks annotator tests, OpenAI-compatible URL normalization tests, intent decomposition & anti-self-click tests, FG/BG parallel lane isolation tests, `:18888` constant-time token verification tests, and UID-2000 OS CLI fallback tests

---

## 2. Running the Tests

### 2.1 Full JVM Unit Test Suite (Windows / macOS / Linux)
```bash
# Windows PowerShell
.\gradlew.bat testDebugUnitTest

# macOS / Linux
./gradlew testDebugUnitTest
```

### 2.2 Targeted Suite Execution
```bash
./gradlew testDebugUnitTest --tests "com.hanson.mobileuse.MobileUseHarnessTest"
./gradlew testDebugUnitTest --tests "com.hanson.mobileuse.lifestyle.*"
```

### 2.3 Physical Android Device Verification (Optional)
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\s25-lifestyle-e2e-runner.ps1 -DeviceSerial <YOUR_ADB_SERIAL>
```

---

## 3. Test Suite Matrix

| Test Suite Class | File Path | Scope |
|:---|:---|:---|
| `MobileUseHarnessTest` | `app/src/test/java/com/hanson/mobileuse/MobileUseHarnessTest.kt` | Coordinate normalization, Set-of-Marks collision nudging, OpenAI-compatible endpoint normalization, world graph transitions |
| `IntentDecompositionAndFastRoutingTest` | `app/src/test/java/com/hanson/mobileuse/lifestyle/IntentDecompositionAndFastRoutingTest.kt` | 7-category intent decomposition, Korean jamo fuzzy matching, anti-self-click isolation |
| `FgBgParallelOrchestrationTest` | `app/src/test/java/com/hanson/mobileuse/FgBgParallelOrchestrationTest.kt` | Foreground GUI mutex vs. parallel Background headless execution |
| `EmpiricalChallengerParadigmStressTest` | `app/src/test/java/com/hanson/mobileuse/EmpiricalChallengerParadigmStressTest.kt` | Transition stability tracking, verified action shortcuts, loop-erased credit assignment |
| `EditorialLifestyleUiErgonomicsTest` | `app/src/test/java/com/hanson/mobileuse/lifestyle/EditorialLifestyleUiErgonomicsTest.kt` | Clean badge sanitation, IME keyboard ergonomics, squircle card system |
| `MultiModelIntelligenceAndSessionMemoryTest` | `app/src/test/java/com/hanson/mobileuse/lifestyle/MultiModelIntelligenceAndSessionMemoryTest.kt` | Multi-model routing, atomic memory vault persistence, credential masking |
| `BuildIntegrityAndDeploymentContractTest` | `app/src/test/java/com/hanson/mobileuse/lifestyle/BuildIntegrityAndDeploymentContractTest.kt` | Reflection & compile contracts, APK ZIP/DEX integrity |
