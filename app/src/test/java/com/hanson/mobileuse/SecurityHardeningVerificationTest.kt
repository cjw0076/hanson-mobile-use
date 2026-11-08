package com.hanson.mobileuse

import com.hanson.mobileuse.accessibility.AccessibilityEngineState
import com.hanson.mobileuse.accessibility.ArtemisCommandServer
import com.hanson.mobileuse.oscli.HansonOsCliMain
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
import java.util.concurrent.Executors

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
}
