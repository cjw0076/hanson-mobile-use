package com.hanson.mobileuse

import com.hanson.mobileuse.accessibility.AccessibilityEngineState
import com.hanson.mobileuse.accessibility.ArtemisCommandServer
import com.hanson.mobileuse.agent.BlindCredentialVault
import com.hanson.mobileuse.agent.InteractiveMorphEngine
import com.hanson.mobileuse.oscli.HansonOsCliMain
import com.hanson.mobileuse.ui.components.HansonJsBridge
import com.hanson.mobileuse.ui.components.HansonWebViewClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Security Hardening Verification Suite (v3.1):
 * Empirical tests verifying remediation of:
 * 1. Leaked release keystore (`app/hanson-release.jks`) & hardcoded fallback password (`hanson123`)
 * 2. Unauthenticated `:18890` OS CLI daemon & arbitrary `/exec` shell execution (`sh -c`)
 * 3. Unauthenticated `:18888` A2A/Artemis server (`requireTokenAuth = true` by default + 256-bit token)
 */
class SecurityHardeningVerificationTest {

    private fun resolveRepoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return if (File(cwd, "settings.gradle.kts").exists()) cwd else cwd.parentFile ?: cwd
    }

    // =========================================================================
    // 1. Release Signing Key & Build Script Security
    // =========================================================================

    @Test
    fun testReleaseSigningKey_isRemovedFromRepoAndNoHardcodedPasswordExists() {
        val repoRoot = resolveRepoRoot()
        val leakedJks = File(repoRoot, "app/hanson-release.jks")
        assertFalse(
            "Compromised app/hanson-release.jks must NOT exist in the repository",
            leakedJks.exists()
        )

        val buildGradle = File(repoRoot, "app/build.gradle.kts")
        assertTrue("app/build.gradle.kts must exist", buildGradle.isFile)
        val gradleText = buildGradle.readText()
        assertFalse(
            "app/build.gradle.kts must NOT contain hardcoded password 'hanson123'",
            gradleText.contains("hanson123")
        )
        assertFalse(
            "app/build.gradle.kts must NOT reference committed 'hanson-release.jks'",
            gradleText.contains("hanson-release.jks")
        )

        val gitignore = File(repoRoot, ".gitignore")
        assertTrue(".gitignore must exist", gitignore.isFile)
        val ignoreText = gitignore.readText()
        assertTrue(".gitignore must ignore *.jks", ignoreText.contains("*.jks"))
        assertTrue(".gitignore must ignore keystore.properties", ignoreText.contains("keystore.properties"))
    }

    // =========================================================================
    // 2. :18890 OS CLI Daemon Authentication & Shell Injection Prevention
    // =========================================================================

    @Test
    fun testOsCliDaemon_rejectsUnauthenticatedRequestsWith401() {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val pool = Executors.newSingleThreadExecutor()
        pool.execute {
            try {
                val client = server.accept()
                HansonOsCliMain.handleDaemonClient(client)
            } catch (_: Throwable) {
            } finally {
                server.close()
            }
        }

        try {
            val conn = (URL("http://127.0.0.1:$port/exec").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            val payload = JSONObject().put("action", "status").toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            assertEquals(
                "Unauthenticated /exec request to :18890 daemon must be rejected with 401 Unauthorized",
                401,
                conn.responseCode
            )
        } finally {
            server.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun testOsCliDaemon_rejectsBrowserCrossOriginRequestsWith403EvenWithToken() {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val pool = Executors.newSingleThreadExecutor()
        pool.execute {
            try {
                val client = server.accept()
                HansonOsCliMain.handleDaemonClient(client)
            } catch (_: Throwable) {
            } finally {
                server.close()
            }
        }

        try {
            val validToken = HansonOsCliMain.getOrCreateSharedDaemonToken(null)
            val payload = JSONObject().put("action", "status").toString()
            val payloadBytes = payload.toByteArray(Charsets.UTF_8)
            java.net.Socket("127.0.0.1", port).use { clientSock ->
                val rawRequest = buildString {
                    append("POST /exec HTTP/1.1\r\n")
                    append("Host: 127.0.0.1:$port\r\n")
                    append("Content-Type: application/json\r\n")
                    append("${HansonOsCliMain.TOKEN_HEADER}: $validToken\r\n")
                    append("Origin: https://malicious.example.com\r\n")
                    append("Content-Length: ${payloadBytes.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }
                clientSock.getOutputStream().write(rawRequest.toByteArray(Charsets.UTF_8))
                clientSock.getOutputStream().write(payloadBytes)
                clientSock.getOutputStream().flush()

                val statusLine = clientSock.getInputStream().bufferedReader(Charsets.UTF_8).readLine() ?: ""
                assertTrue(
                    "Browser cross-origin requests must be rejected with 403 Forbidden, got: $statusLine",
                    statusLine.contains("403")
                )
            }
        } finally {
            server.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun testOsCliDaemon_rejectsArbitraryShellExecutionAndMetacharacters() {
        // 1. Direct executeCommandJson must reject unallowlisted 'sh' / 'rm' / arbitrary commands
        val rawShRes = HansonOsCliMain.executeCommandJson("sh", listOf("-c", "id"))
        assertEquals("error", rawShRes.optString("status"))
        assertTrue(rawShRes.optString("error").contains("Security rejection"))

        // 2. Metacharacter injection in arguments must be blocked
        val injectedRes = HansonOsCliMain.executeCommandJson("tap", listOf("500; rm -rf /", "600"))
        assertEquals("error", injectedRes.optString("status"))
        assertTrue(injectedRes.optString("error").contains("shell metacharacters forbidden"))

        // 3. HTTP /exec with shell metacharacters must return 403 Forbidden even when authenticated
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val pool = Executors.newSingleThreadExecutor()
        pool.execute {
            try {
                val client = server.accept()
                HansonOsCliMain.handleDaemonClient(client)
            } catch (_: Throwable) {
            } finally {
                server.close()
            }
        }

        try {
            val validToken = HansonOsCliMain.getOrCreateSharedDaemonToken(null)
            val conn = (URL("http://127.0.0.1:$port/exec").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty(HansonOsCliMain.TOKEN_HEADER, validToken)
                setRequestProperty("Host", "127.0.0.1:${HansonOsCliMain.DAEMON_PORT}")
            }
            val maliciousPayload = JSONObject().put("command", "id && cat /etc/passwd").toString()
            conn.outputStream.use { it.write(maliciousPayload.toByteArray(Charsets.UTF_8)) }

            assertEquals(
                "Shell injection payload on /exec must be rejected with 403 Forbidden",
                403,
                conn.responseCode
            )
        } finally {
            server.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun testOsCliDaemon_acceptsValidAuthenticatedAllowlistedAction() {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val pool = Executors.newSingleThreadExecutor()
        pool.execute {
            try {
                val client = server.accept()
                HansonOsCliMain.handleDaemonClient(client)
            } catch (_: Throwable) {
            } finally {
                server.close()
            }
        }

        try {
            val validToken = HansonOsCliMain.getOrCreateSharedDaemonToken(null)
            val conn = (URL("http://127.0.0.1:$port/exec").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty(HansonOsCliMain.TOKEN_HEADER, validToken)
                setRequestProperty("Host", "127.0.0.1:${HansonOsCliMain.DAEMON_PORT}")
            }
            val validPayload = JSONObject().apply {
                put("action", "tap")
                put("args", JSONArray(listOf("540", "1200")))
            }.toString()
            conn.outputStream.use { it.write(validPayload.toByteArray(Charsets.UTF_8)) }

            assertEquals(200, conn.responseCode)
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            assertTrue(body.contains("input tap 540 1200"))
        } finally {
            server.close()
            pool.shutdownNow()
        }
    }

    // =========================================================================
    // 3. :18888 A2A / Artemis Bridge Default Token Auth Verification
    // =========================================================================

    @Test
    fun testArtemisBridge_enforcesTokenAuthByDefaultAndConstantTimeComparison() {
        val defaultState = AccessibilityEngineState()
        assertTrue(
            ":18888 bridge must require token authentication by default (bridgeRequireToken = true)",
            defaultState.bridgeRequireToken
        )

        val valid256BitToken = "a".repeat(64)
        assertFalse(ArtemisCommandServer.verifyTokenConstantTime(null, valid256BitToken))
        assertFalse(ArtemisCommandServer.verifyTokenConstantTime("", valid256BitToken))
        assertFalse(ArtemisCommandServer.verifyTokenConstantTime("short_token", valid256BitToken))
        assertFalse(ArtemisCommandServer.verifyTokenConstantTime("b".repeat(64), valid256BitToken))
        assertTrue(ArtemisCommandServer.verifyTokenConstantTime(valid256BitToken, valid256BitToken))
    }

    // =========================================================================
    // 4. v6.1 Pre-Release Security Hardening Regression Checks
    // =========================================================================

    @Test
    fun testManifestAndNetworkConfig_enforceNoBackupAndLoopbackOnlyCleartext() {
        val repoRoot = resolveRepoRoot()
        val manifest = File(repoRoot, "app/src/main/AndroidManifest.xml").readText()
        assertTrue(
            "AndroidManifest must set android:allowBackup=\"false\"",
            manifest.contains("""android:allowBackup="false"""")
        )
        assertTrue(
            "AndroidManifest must set android:usesCleartextTraffic=\"false\"",
            manifest.contains("""android:usesCleartextTraffic="false"""")
        )
        assertTrue(
            "AndroidManifest must reference network_security_config",
            manifest.contains("""android:networkSecurityConfig="@xml/network_security_config"""")
        )

        val netConfig = File(repoRoot, "app/src/main/res/xml/network_security_config.xml")
        assertTrue("network_security_config.xml must exist", netConfig.isFile)
        val netXml = netConfig.readText()
        assertTrue(netXml.contains("""cleartextTrafficPermitted="false""""))
        assertTrue(netXml.contains("127.0.0.1"))
        assertTrue(netXml.contains("localhost"))
    }

    @Test
    fun testWebViewBridgeAndMorphHtml_blockUnsafeSchemesNavigationAndAttributeBreakout() {
        assertTrue(HansonJsBridge.isAllowedDeepLinkUri("https://example.com/path"))
        assertTrue(HansonJsBridge.isAllowedDeepLinkUri("kakaotalk://launch"))
        assertFalse(HansonJsBridge.isAllowedDeepLinkUri("file:///data/data/com.hanson.mobileuse/files/hanson_blind_vault.json"))
        assertFalse(HansonJsBridge.isAllowedDeepLinkUri("content://sms/inbox"))
        assertFalse(HansonJsBridge.isAllowedDeepLinkUri("javascript:alert(1)"))
        assertFalse(HansonJsBridge.isAllowedDeepLinkUri("tel:01012345678"))
        assertFalse(HansonJsBridge.isAllowedDeepLinkUri("sms:01012345678"))
        assertFalse(HansonJsBridge.isAllowedDeepLinkUri("intent:#Intent;end"))

        assertTrue(HansonWebViewClient.isAllowedNavigationUrl("https://hanson.local/"))
        assertTrue(HansonWebViewClient.isAllowedNavigationUrl("about:blank"))
        assertFalse(HansonWebViewClient.isAllowedNavigationUrl("https://evil.example.com/phish"))
        assertFalse(HansonWebViewClient.isAllowedNavigationUrl("file:///etc/passwd"))

        val hostilePrompt = "맛집 추천\" onmouseover=\"alert(1)</script><img src=x>"
        val surface = requireNotNull(InteractiveMorphEngine.synthesizeFromPrompt(hostilePrompt)) {
            "Expected InteractiveMorphSurface for food prompt"
        }
        val html = InteractiveMorphEngine.buildAchromaticMicroAppHtml(surface)
        assertFalse(
            "onclick attribute must not allow unescaped double-quote breakout",
            html.contains("\" onmouseover=\"")
        )
        assertFalse(
            "Micro-app HTML must not contain raw </script> breakout from prompt",
            html.contains("</script><img")
        )
    }

    @Test
    fun testExternalShareReceiverAndGeminiTransport_enforceExplicitConfirmationAndHeaderAuth() {
        val repoRoot = resolveRepoRoot()
        val nexusSrc = File(
            repoRoot,
            "app/src/main/java/com/hanson/mobileuse/service/HansonSystemNexusServices.kt"
        ).readText()
        assertTrue(
            "HansonSystemNexusReceiverActivity must forward external intents to MainActivity for user confirmation",
            nexusSrc.contains("EXTRA_PENDING_EXTERNAL_PROMPT")
        )
        val receiverBody = nexusSrc.substringAfter("class HansonSystemNexusReceiverActivity")
        assertFalse(
            "HansonSystemNexusReceiverActivity must NOT auto-execute engine.startRun from untrusted external Intents",
            receiverBody.contains("engine.startRun(")
        )

        val geminiSrc = File(
            repoRoot,
            "app/src/main/java/com/hanson/mobileuse/agent/GeminiMultimodalClient.kt"
        ).readText()
        assertFalse(
            "GeminiMultimodalClient must transmit API keys via x-goog-api-key header, never ?key= query params",
            geminiSrc.contains(":generateContent?key=")
        )
        assertTrue(
            "GeminiMultimodalClient must attach x-goog-api-key header",
            geminiSrc.contains("x-goog-api-key")
        )
    }

    @Test
    fun testReleaseBuildConfigAndThemeBars_blockEmbeddedApiKeyAndFixPaperContrast() {
        val repoRoot = resolveRepoRoot()
        val gradleText = File(repoRoot, "app/build.gradle.kts").readText()
        val releaseSection = gradleText.substringAfter("release {")
        assertTrue(
            "Release buildType must explicitly override BuildConfig.GEMINI_API_KEY to empty string",
            releaseSection.contains("""buildConfigField("String", "GEMINI_API_KEY", "\"\"")""")
        )

        val themeSrc = File(
            repoRoot,
            "app/src/main/java/com/hanson/mobileuse/ui/theme/Theme.kt"
        ).readText()
        assertTrue(
            "Theme.kt must set light status bar icons when Paper Light theme is active",
            themeSrc.contains("isAppearanceLightStatusBars = !isDark")
        )
        assertTrue(
            "Theme.kt must set light navigation bar icons when Paper Light theme is active",
            themeSrc.contains("isAppearanceLightNavigationBars = !isDark")
        )
    }

    // =========================================================================
    // 5. v6.2 Modernization Security Hardening Regression Checks
    // =========================================================================

    @Test
    fun testNativeCodeCanvasViewport_enforcesHansonWebViewClientAndDisablesFileContentAccess() {
        val repoRoot = resolveRepoRoot()
        val rendererSrc = File(
            repoRoot,
            "app/src/main/java/com/hanson/mobileuse/ui/components/GenerativeUiRenderer.kt"
        ).readText()
        val canvasBlock = rendererSrc.substringAfter("private fun NativeCodeCanvasViewport")
        assertTrue(
            "NativeCodeCanvasViewport must attach origin-locked HansonWebViewClient",
            canvasBlock.contains("webViewClient = HansonWebViewClient(bridge)")
        )
        assertTrue(
            "NativeCodeCanvasViewport must attach HansonWebChromeClient",
            canvasBlock.contains("webChromeClient = HansonWebChromeClient(bridge)")
        )
        assertTrue(
            "NativeCodeCanvasViewport must disable settings.allowFileAccess",
            canvasBlock.contains("settings.allowFileAccess = false")
        )
        assertTrue(
            "NativeCodeCanvasViewport must disable settings.allowContentAccess",
            canvasBlock.contains("settings.allowContentAccess = false")
        )
        assertTrue(
            "NativeCodeCanvasViewport must disable allowFileAccessFromFileURLs",
            canvasBlock.contains("settings.allowFileAccessFromFileURLs = false")
        )
        assertTrue(
            "NativeCodeCanvasViewport must disable allowUniversalAccessFromFileURLs",
            canvasBlock.contains("settings.allowUniversalAccessFromFileURLs = false")
        )
    }

    @Test
    fun testUniversalNexusEngine_hasZeroDuckDuckGoScrapingAndZeroXssBrowserBoard() {
        val repoRoot = resolveRepoRoot()
        val nexusSrc = File(
            repoRoot,
            "app/src/main/java/com/hanson/mobileuse/agent/UniversalNexusEngine.kt"
        ).readText()
        assertFalse(
            "UniversalNexusEngine.kt must have ZERO html.duckduckgo.com references",
            nexusSrc.contains("html.duckduckgo.com", ignoreCase = true)
        )
        assertFalse(
            "UniversalNexusEngine.kt must have ZERO buildInteractiveBrowserBoardHtml references",
            nexusSrc.contains("buildInteractiveBrowserBoardHtml")
        )
    }

    @Test
    fun testBlindCredentialVault_handlesAndroidKeyStoreWithoutCallerIv() {
        val repoRoot = resolveRepoRoot()
        val vaultSrc = File(
            repoRoot,
            "app/src/main/java/com/hanson/mobileuse/agent/PickleSotaAbsorptionEngine.kt"
        ).readText()
        val encFn = vaultSrc.substringAfter("internal fun encryptAesGcmWithKey").substringBefore("internal fun computeAuditEntryHash")
        assertTrue(
            "encryptAesGcmWithKey must check `key !is SecretKeySpec` for AndroidKeyStore randomized IV",
            encFn.contains("key !is SecretKeySpec")
        )
        assertTrue(
            "AndroidKeyStore branch must omit GCMParameterSpec and read cipher.iv",
            encFn.contains("cipher.init(Cipher.ENCRYPT_MODE, key)") && encFn.contains("cipher.iv")
        )

        // Live crypto verification of BOTH branches:
        val rawKeyBytes = ByteArray(32) { idx -> (idx * 7 + 3).toByte() }
        val plainBytes = "TopSecretAndroidKeyStoreValue!2026".toByteArray(StandardCharsets.UTF_8)
        val aadBytes = "hanson.blind.vault.v2|vault://ks_test".toByteArray(StandardCharsets.UTF_8)

        // 1. Non-SecretKeySpec wrapper simulates AndroidKeyStore SecretKey (cipher generates IV internally)
        val keyStoreLikeKey = object : SecretKey {
            override fun getAlgorithm(): String = "AES"
            override fun getFormat(): String = "RAW"
            override fun getEncoded(): ByteArray = rawKeyBytes.copyOf()
        }
        val (ksIvB64, ksCtB64) = BlindCredentialVault.encryptAesGcmWithKey(keyStoreLikeKey, plainBytes, aadBytes)
        val ksIv = Base64.getDecoder().decode(ksIvB64)
        assertEquals("Provider-generated AES-GCM IV must be 12 bytes", 12, ksIv.size)

        val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        decryptCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(rawKeyBytes, "AES"), GCMParameterSpec(128, ksIv))
        decryptCipher.updateAAD(aadBytes)
        assertEquals(
            "TopSecretAndroidKeyStoreValue!2026",
            String(decryptCipher.doFinal(Base64.getDecoder().decode(ksCtB64)), StandardCharsets.UTF_8)
        )

        // 2. Standard SecretKeySpec JVM fallback path also round-trips cleanly
        val (specIvB64, specCtB64) = BlindCredentialVault.encryptAesGcmWithKey(
            SecretKeySpec(rawKeyBytes, "AES"),
            plainBytes,
            aadBytes
        )
        val specIv = Base64.getDecoder().decode(specIvB64)
        assertEquals(12, specIv.size)
        val decryptSpec = Cipher.getInstance("AES/GCM/NoPadding")
        decryptSpec.init(Cipher.DECRYPT_MODE, SecretKeySpec(rawKeyBytes, "AES"), GCMParameterSpec(128, specIv))
        decryptSpec.updateAAD(aadBytes)
        assertEquals(
            "TopSecretAndroidKeyStoreValue!2026",
            String(decryptSpec.doFinal(Base64.getDecoder().decode(specCtB64)), StandardCharsets.UTF_8)
        )
    }

    @Test
    fun testOsCliMain_verifyLoopbackPeerUidIsFailClosed() {
        val repoRoot = resolveRepoRoot()
        val osCliSrc = File(
            repoRoot,
            "app/src/main/java/com/hanson/mobileuse/oscli/HansonOsCliMain.kt"
        ).readText()
        val verifyFn = osCliSrc.substringAfter("internal fun verifyLoopbackPeerUid")
            .substringBefore("private fun resolveTrustedAndroidUids")
        assertTrue(
            "verifyLoopbackPeerUid must fail closed when /proc/net/tcp* cannot be read",
            verifyFn.contains("if (tcpFiles.isEmpty())") && verifyFn.contains("return false")
        )
        assertTrue(
            "verifyLoopbackPeerUid must fail closed on catch",
            verifyFn.contains("catch (_: Throwable)") && verifyFn.substringAfter("catch (_: Throwable)").contains("return false")
        )

        // Live unit tests of verifyProcNetTcpLines fail-closed behavior:
        val trustedUids = setOf(0, 2000, 10123)
        val header = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode"

        // 1. Missing entry -> fail closed (false)
        assertFalse(
            "Empty /proc/net/tcp must fail closed",
            HansonOsCliMain.verifyProcNetTcpLines(sequenceOf(header), "C350", "49CA", trustedUids)
        )

        // 2. Malformed/non-numeric UID -> fail closed (false)
        val malformedRow = "   0: 0100007F:C350 0100007F:49CA 01 00000000:00000000 00:00000000 00000000   BADUID   0 12345"
        assertFalse(
            "Malformed UID must fail closed",
            HansonOsCliMain.verifyProcNetTcpLines(sequenceOf(header, malformedRow), "C350", "49CA", trustedUids)
        )

        // 3. Untrusted third-party app UID -> fail closed (false)
        val untrustedRow = "   0: 0100007F:C350 0100007F:49CA 01 00000000:00000000 00:00000000 00000000   10999   0 12345"
        assertFalse(
            "Untrusted UID 10999 must be rejected",
            HansonOsCliMain.verifyProcNetTcpLines(sequenceOf(header, untrustedRow), "C350", "49CA", trustedUids)
        )

        // 4. Trusted Hanson/shell UID matching exact client & server hex ports -> allowed (true)
        val trustedRow = "   0: 0100007F:C350 0100007F:49CA 01 00000000:00000000 00:00000000 00000000   10123   0 12345"
        assertTrue(
            "Trusted UID 10123 matching client/server ports must be accepted",
            HansonOsCliMain.verifyProcNetTcpLines(sequenceOf(header, trustedRow), "C350", "49CA", trustedUids)
        )
    }
}
