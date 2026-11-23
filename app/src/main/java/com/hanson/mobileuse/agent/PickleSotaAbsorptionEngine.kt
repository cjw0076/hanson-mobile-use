package com.hanson.mobileuse.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.accessibility.IndexedUiElement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Arrays
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// =============================================================================
// 1. Pickle Blind Credential Vault (`vault://<key>`)
//    Zero LLM plaintext exposure, AES-256-GCM + AAD handle-bound encryption,
//    OWASP PBKDF2-HMAC-SHA256 (>= 210k), zero-decryption keyed redaction index,
//    immediate buffer zeroing, and SHA-256 hash-chained append-only audit log.
// =============================================================================

enum class VaultSecretCategory(
    val labelKo: String,
    val alwaysRequireApproval: Boolean
) {
    LOGIN("로그인 계정", false),
    PAYMENT("결제·카드", true),
    IDENTITY("신원·인증", true),
    ADDRESS("배송지·주소", false),
    API_TOKEN("외부 토큰", false)
}

data class VaultHandleSummary(
    val key: String,
    val handle: String,
    val label: String,
    val category: VaultSecretCategory,
    val maskedPreview: String,
    val updatedAtMs: Long
)

/**
 * Append-only SHA-256 hash-chained audit record for BlindCredentialVault events.
 * Raw secret values are NEVER stored in any field.
 */
data class VaultAuditEntry(
    val sequence: Long,
    val timestampIso: String,
    val action: String,
    val handle: String,
    val approvedReceiptId: String? = null,
    val controlEpoch: Long? = null,
    val outcome: String = "OK",
    val prevHash: String,
    val entryHash: String
)

data class AuditChainVerificationResult(
    val valid: Boolean,
    val entryCount: Int,
    val brokenAtSequence: Long? = null,
    val headHash: String = BlindCredentialVault.AUDIT_GENESIS_HASH,
    val reason: String = "OK"
)

class BlindCredentialVault private constructor(
    private val storageFile: File,
    private val customMasterKey: ByteArray?,
    private val envMasterKeyProvider: () -> String?
) {
    @JvmOverloads
    constructor(
        storageFile: File,
        customMasterKey: ByteArray? = null
    ) : this(
        storageFile = storageFile,
        customMasterKey = customMasterKey,
        envMasterKeyProvider = { System.getenv(ENV_MASTER_KEY) }
    )

    internal constructor(
        storageFile: File,
        envMasterKeyProvider: () -> String?
    ) : this(
        storageFile = storageFile,
        customMasterKey = null,
        envMasterKeyProvider = envMasterKeyProvider
    )

    constructor(context: Context) : this(
        storageFile = File(context.applicationContext.filesDir, VAULT_FILE_NAME),
        customMasterKey = null,
        envMasterKeyProvider = { System.getenv(ENV_MASTER_KEY) }
    )

    companion object {
        private const val VAULT_FILE_NAME = "hanson_blind_vault.json"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "hanson_blind_vault_aes_v1"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_BYTES = 12
        const val VAULT_SCHEMA_VERSION = 2
        const val AAD_VERSION_PREFIX = "hanson-blind-vault:v2:"
        private const val REDACTION_INDEX_KEY_AAD = "hanson-blind-vault:v2:__redaction_index_key__"
        const val PBKDF2_ITERATIONS = 210_000
        const val ENV_MASTER_KEY = "HANSON_VAULT_MASTER_KEY"
        private const val SALT_BYTES = 32
        private const val MIN_LEGACY_SALT_BYTES = 16
        private const val REDACTION_INDEX_KEY_BYTES = 48
        const val AUDIT_GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"

        private val CARD_16_REGEX = Regex("\\b(?:\\d{4}[-\\s]){3}(\\d{4})\\b")
        private val RRN_REGEX = Regex("\\b\\d{6}[-\\s]?[1-4]\\d{6}\\b")

        internal fun buildAadBytes(normalizedHandle: String): ByteArray =
            "$AAD_VERSION_PREFIX$normalizedHandle".toByteArray(StandardCharsets.UTF_8)

        /**
         * Encrypts [plainBytes] under AES-256-GCM with [aadBytes].
         *
         * On real Android devices, hardware-backed AndroidKeyStore keys enforce randomized
         * encryption (`setRandomizedEncryptionRequired(true)`) and throw
         * `InvalidAlgorithmParameterException: Caller-provided IV not permitted` if a
         * caller-generated `GCMParameterSpec` is passed to `cipher.init(ENCRYPT_MODE, ...)`.
         * Therefore non-`SecretKeySpec` keys (AndroidKeyStore) MUST omit `GCMParameterSpec`
         * and read `cipher.iv` generated by the keystore provider after `init` and before
         * `updateAAD`/`doFinal`, while JVM/PBKDF2 `SecretKeySpec` keys supply a fresh 12-byte
         * random IV via `GCMParameterSpec`.
         */
        internal fun encryptAesGcmWithKey(
            key: SecretKey,
            plainBytes: ByteArray,
            aadBytes: ByteArray,
            secureRandom: SecureRandom = SecureRandom()
        ): Pair<String, String> {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            val iv = if (key !is SecretKeySpec) {
                cipher.init(Cipher.ENCRYPT_MODE, key)
                cipher.iv?.takeIf { it.isNotEmpty() }
                    ?: throw GeneralSecurityException("KeyStore AES-GCM cipher did not generate an IV.")
            } else {
                ByteArray(GCM_IV_BYTES).also { generatedIv ->
                    secureRandom.nextBytes(generatedIv)
                    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, generatedIv))
                }
            }
            cipher.updateAAD(aadBytes)
            val encrypted = cipher.doFinal(plainBytes)
            val encoder = Base64.getEncoder()
            return encoder.encodeToString(iv) to encoder.encodeToString(encrypted)
        }

        internal fun computeAuditEntryHash(
            sequence: Long,
            timestampIso: String,
            action: String,
            handle: String,
            approvedReceiptId: String?,
            controlEpoch: Long?,
            outcome: String,
            prevHash: String
        ): String {
            fun enc(v: String): String = "${v.length}:$v"
            val canonical = buildString {
                append("v1|")
                append(sequence)
                append('|')
                append(enc(timestampIso))
                append('|')
                append(enc(action))
                append('|')
                append(enc(handle))
                append('|')
                append(enc(approvedReceiptId.orEmpty()))
                append('|')
                append(enc(controlEpoch?.toString().orEmpty()))
                append('|')
                append(enc(outcome))
                append('|')
                append(enc(prevHash))
            }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        internal fun hardenOwnerOnlyFilePermissions(file: File) {
            runCatching {
                val perms = PosixFilePermissions.fromString("rw-------")
                Files.setPosixFilePermissions(file.toPath(), perms)
            }
            runCatching {
                file.setReadable(false, false)
                file.setReadable(true, true)
                file.setWritable(false, false)
                file.setWritable(true, true)
                file.setExecutable(false, false)
            }
        }

        internal fun writeOwnerOnlyBytes(file: File, bytes: ByteArray) {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            if (!file.exists()) {
                val createdPosix = runCatching {
                    val attr = PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")
                    )
                    Files.createFile(file.toPath(), attr)
                    true
                }.getOrDefault(false)
                if (!createdPosix) {
                    runCatching { file.createNewFile() }
                }
            }
            hardenOwnerOnlyFilePermissions(file)
            file.writeBytes(bytes)
            hardenOwnerOnlyFilePermissions(file)
        }

        internal fun writeOwnerOnlyText(file: File, content: String) {
            val bytes = content.toByteArray(StandardCharsets.UTF_8)
            try {
                writeOwnerOnlyBytes(file, bytes)
            } finally {
                Arrays.fill(bytes, 0.toByte())
            }
        }

        private fun appendOwnerOnlyLine(file: File, line: String) {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            if (!file.exists()) {
                val createdPosix = runCatching {
                    val attr = PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")
                    )
                    Files.createFile(file.toPath(), attr)
                    true
                }.getOrDefault(false)
                if (!createdPosix) {
                    runCatching { file.createNewFile() }
                }
            }
            hardenOwnerOnlyFilePermissions(file)
            file.appendText("$line\n", StandardCharsets.UTF_8)
            hardenOwnerOnlyFilePermissions(file)
        }

        /**
         * Zero-allocation keyed SipHash-2-4 over a single 64-bit word (4 UTF-16 chars).
         */
        private fun sipHash24Word(k0: Long, k1: Long, word: Long): Long {
            var v0 = 0x736f6d6570736575L xor k0
            var v1 = 0x646f72616e646f6dL xor k1
            var v2 = 0x6c7967656e657261L xor k0
            var v3 = 0x7465646279746573L xor k1

            fun sipRound() {
                v0 += v1
                v1 = java.lang.Long.rotateLeft(v1, 13)
                v1 = v1 xor v0
                v0 = java.lang.Long.rotateLeft(v0, 32)
                v2 += v3
                v3 = java.lang.Long.rotateLeft(v3, 16)
                v3 = v3 xor v2
                v0 += v3
                v3 = java.lang.Long.rotateLeft(v3, 21)
                v3 = v3 xor v0
                v2 += v1
                v1 = java.lang.Long.rotateLeft(v1, 17)
                v1 = v1 xor v2
                v2 = java.lang.Long.rotateLeft(v2, 32)
            }

            // Full 8-byte block
            v3 = v3 xor word
            sipRound(); sipRound()
            v0 = v0 xor word

            // Final block with byte length = 8
            val finalBlock = 8L shl 56
            v3 = v3 xor finalBlock
            sipRound(); sipRound()
            v0 = v0 xor finalBlock

            // Finalization (4 rounds)
            v2 = v2 xor 0xffL
            sipRound(); sipRound(); sipRound(); sipRound()
            return v0 xor v1 xor v2 xor v3
        }

        private fun packFourChars(c0: Char, c1: Char, c2: Char, c3: Char): Long {
            return ((c0.code.toLong() and 0xFFFFL) shl 48) or
                ((c1.code.toLong() and 0xFFFFL) shl 32) or
                ((c2.code.toLong() and 0xFFFFL) shl 16) or
                (c3.code.toLong() and 0xFFFFL)
        }

        private fun bytesToLongLE(bytes: ByteArray, offset: Int): Long {
            var r = 0L
            for (i in 0 until 8) {
                r = r or ((bytes[offset + i].toLong() and 0xFFL) shl (i * 8))
            }
            return r
        }

        private fun utf8EncodeChars(chars: CharArray): ByteArray {
            val encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(chars))
            val out = ByteArray(encoded.remaining())
            encoded.get(out)
            if (encoded.hasArray()) {
                Arrays.fill(encoded.array(), 0.toByte())
            }
            return out
        }

        private fun utf8DecodeToChars(bytes: ByteArray): CharArray {
            val decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes))
            val out = CharArray(decoded.remaining())
            decoded.get(out)
            if (decoded.hasArray()) {
                Arrays.fill(decoded.array(), '\u0000')
            }
            return out
        }
    }

    private data class StoredVaultEntry(
        val summary: VaultHandleSummary,
        val ivBase64: String,
        val ciphertextBase64: String,
        val aadVersion: Int = VAULT_SCHEMA_VERSION,
        val secretLength: Int = 0,
        val prefixTag: Long? = null,
        val fullDigestBase64: String = ""
    )

    private data class RedactionIndexKey(
        val sipK0: Long,
        val sipK1: Long,
        val hmacKey: SecretKey
    )

    private data class RedactionCandidate(
        val handle: String,
        val secretLength: Int,
        val fullDigest: ByteArray
    )

    private val lock = Any()
    private val entries = LinkedHashMap<String, StoredVaultEntry>()
    private val prefixIndex = HashMap<Long, List<RedactionCandidate>>()
    private var minIndexedSecretLength: Int = Int.MAX_VALUE
    private val secureRandom = SecureRandom()

    val auditLogFile: File = File(
        storageFile.parentFile ?: File("."),
        "${storageFile.nameWithoutExtension}.audit.jsonl"
    )
    private val auditEntries = mutableListOf<VaultAuditEntry>()
    private var auditDiskParseErrorAtSequence: Long? = null
    private var auditDiskParseErrorReason: String? = null

    private var wrappedRedactionKeyIvB64: String = ""
    private var wrappedRedactionKeyCtB64: String = ""

    @Volatile
    private var cachedSecretKey: SecretKey? = null

    @Volatile
    private var cachedRedactionIndexKey: RedactionIndexKey? = null

    @Volatile
    internal var activeKeySourceForTesting: String = "UNINITIALIZED"
        private set

    private val credentialDecryptCount = AtomicLong(0L)

    private val _handles = MutableStateFlow<List<VaultHandleSummary>>(emptyList())
    val handles: StateFlow<List<VaultHandleSummary>> = _handles.asStateFlow()

    init {
        loadFromDisk()
        loadAuditLogFromDisk()
    }

    override fun toString(): String {
        val count = synchronized(lock) { entries.size }
        return "BlindCredentialVault(entries=$count, schemaVersion=$VAULT_SCHEMA_VERSION)"
    }

    internal fun credentialDecryptCountForTesting(): Long = credentialDecryptCount.get()

    @JvmOverloads
    fun putSecret(
        rawKey: String,
        label: String,
        plaintext: String,
        category: VaultSecretCategory = VaultSecretCategory.LOGIN,
        approvedReceiptId: String? = null,
        controlEpoch: Long? = null
    ): VaultHandleSummary {
        val cleanKey = sanitizeKey(rawKey)
        require(cleanKey.isNotEmpty()) { "Vault key must contain alphanumeric characters." }
        require(plaintext.isNotEmpty()) { "Vault secret value cannot be empty." }

        val normalizedHandle = "vault://$cleanKey"
        val now = System.currentTimeMillis()
        val masked = buildMaskedPreview(plaintext, category)
        val plainChars = plaintext.toCharArray()
        var plainBytes: ByteArray? = null

        val summary = VaultHandleSummary(
            key = cleanKey,
            handle = normalizedHandle,
            label = label.ifBlank { cleanKey },
            category = category,
            maskedPreview = masked,
            updatedAtMs = now
        )

        try {
            plainBytes = utf8EncodeChars(plainChars)
            synchronized(lock) {
                val indexKey = resolveOrCreateRedactionIndexKeyLocked()
                val secretLen = plainChars.size
                val prefixTag = if (secretLen >= 4) {
                    sipHash24Word(
                        indexKey.sipK0,
                        indexKey.sipK1,
                        packFourChars(plainChars[0], plainChars[1], plainChars[2], plainChars[3])
                    )
                } else {
                    null
                }
                val fullDigestB64 = if (secretLen >= 4) {
                    val digest = computeHmacSha256(indexKey.hmacKey, plainBytes)
                    try {
                        Base64.getEncoder().encodeToString(digest)
                    } finally {
                        Arrays.fill(digest, 0.toByte())
                    }
                } else {
                    ""
                }

                val (ivB64, ctB64) = encryptBytesLocked(plainBytes, buildAadBytes(normalizedHandle))
                entries[cleanKey] = StoredVaultEntry(
                    summary = summary,
                    ivBase64 = ivB64,
                    ciphertextBase64 = ctB64,
                    aadVersion = VAULT_SCHEMA_VERSION,
                    secretLength = secretLen,
                    prefixTag = prefixTag,
                    fullDigestBase64 = fullDigestB64
                )
                rebuildRedactionIndexLocked()
                saveToDiskLocked()
                recordAuditEventLocked(
                    action = "PUT_SECRET",
                    handle = normalizedHandle,
                    approvedReceiptId = approvedReceiptId,
                    controlEpoch = controlEpoch,
                    outcome = "OK"
                )
                _handles.value = entries.values.map { it.summary }.sortedByDescending { it.updatedAtMs }
            }
        } finally {
            Arrays.fill(plainChars, '\u0000')
            plainBytes?.let { Arrays.fill(it, 0.toByte()) }
        }
        return summary
    }

    @JvmOverloads
    fun deleteSecret(
        rawKey: String,
        approvedReceiptId: String? = null,
        controlEpoch: Long? = null
    ): Boolean {
        val cleanKey = sanitizeKey(rawKey.removePrefix("vault://"))
        synchronized(lock) {
            val removed = entries.remove(cleanKey) ?: return false
            rebuildRedactionIndexLocked()
            saveToDiskLocked()
            recordAuditEventLocked(
                action = "DELETE_SECRET",
                handle = removed.summary.handle,
                approvedReceiptId = approvedReceiptId,
                controlEpoch = controlEpoch,
                outcome = "OK"
            )
            _handles.value = entries.values.map { it.summary }.sortedByDescending { it.updatedAtMs }
            return removed.summary.key == cleanKey
        }
    }

    fun listHandles(categoryFilter: VaultSecretCategory? = null): List<VaultHandleSummary> {
        synchronized(lock) {
            return entries.values
                .map { it.summary }
                .filter { categoryFilter == null || it.category == categoryFilter }
                .sortedByDescending { it.updatedAtMs }
        }
    }

    fun getHandleSummary(keyOrHandle: String): VaultHandleSummary? {
        val cleanKey = sanitizeKey(keyOrHandle.removePrefix("vault://"))
        synchronized(lock) {
            return entries[cleanKey]?.summary
        }
    }

    /**
     * Resolves decrypted secret into a temporary CharArray strictly inside the on-device
     * blind injection boundary and zeroes the CharArray immediately after [block] finishes.
     */
    internal fun <T> withDecryptedSecretChars(
        keyOrHandle: String,
        approvedReceiptId: String? = null,
        controlEpoch: Long? = null,
        block: (VaultHandleSummary, CharArray) -> T
    ): T? {
        val cleanKey = sanitizeKey(keyOrHandle.removePrefix("vault://"))
        val entry = synchronized(lock) { entries[cleanKey] } ?: return null
        val plainChars = try {
            decryptEntryToChars(entry)
        } catch (sec: GeneralSecurityException) {
            recordAuditEvent(
                action = "DECRYPT_INTEGRITY_FAILED",
                handle = entry.summary.handle,
                approvedReceiptId = approvedReceiptId,
                controlEpoch = controlEpoch,
                outcome = "AEAD_AUTH_FAILED"
            )
            throw sec
        }
        try {
            recordAuditEvent(
                action = "RESOLVE_BLIND_INJECT",
                handle = entry.summary.handle,
                approvedReceiptId = approvedReceiptId,
                controlEpoch = controlEpoch,
                outcome = "OK"
            )
            return block(entry.summary, plainChars)
        } finally {
            Arrays.fill(plainChars, '\u0000')
        }
    }

    /**
     * Resolves decrypted secret strictly inside the on-device blind injection boundary.
     * NEVER echo the returned plaintext back into LLM context, chat logs, or receipts.
     */
    @JvmOverloads
    internal fun resolvePlaintextForBlindInjection(
        keyOrHandle: String,
        approvedReceiptId: String? = null,
        controlEpoch: Long? = null
    ): Pair<VaultHandleSummary, String>? {
        return withDecryptedSecretChars(keyOrHandle, approvedReceiptId, controlEpoch) { summary, chars ->
            summary to String(chars)
        }
    }

    /**
     * Heap-safe redaction using keyed SipHash-2-4 prefix fingerprints + HMAC-SHA256 verification.
     * Does NOT decrypt stored v2 vault credential entries on call, and zeroes temporary char/byte
     * slices immediately after matching.
     */
    fun redactSensitiveText(input: String): String {
        if (input.isEmpty()) return input

        val scanState = synchronized(lock) {
            if (entries.isEmpty()) {
                null
            } else {
                val idxKey = runCatching { resolveOrCreateRedactionIndexKeyLocked() }.getOrNull()
                if (idxKey != null) {
                    ensureLegacyEntriesIndexedLocked(idxKey)
                    if (prefixIndex.isEmpty()) {
                        null
                    } else {
                        Triple(idxKey, HashMap(prefixIndex), minIndexedSecretLength)
                    }
                } else {
                    null
                }
            }
        }

        var out = if (scanState != null && input.length >= scanState.third) {
            val (idxKey, indexMap, minLen) = scanState
            redactWithKeyedIndex(input, idxKey, indexMap, minLen)
        } else {
            input
        }

        out = CARD_16_REGEX.replace(out) { m ->
            "••••-••••-••••-${m.groupValues[1]}"
        }
        out = RRN_REGEX.replace(out, "••••••-•••••••")
        return out
    }

    fun recordAuditEvent(
        action: String,
        handle: String,
        approvedReceiptId: String? = null,
        controlEpoch: Long? = null,
        outcome: String = "OK"
    ): VaultAuditEntry {
        synchronized(lock) {
            return recordAuditEventLocked(action, handle, approvedReceiptId, controlEpoch, outcome)
        }
    }

    fun getAuditEntries(): List<VaultAuditEntry> = synchronized(lock) { auditEntries.toList() }

    fun verifyAuditChain(): AuditChainVerificationResult {
        synchronized(lock) {
            if (auditDiskParseErrorAtSequence != null) {
                return AuditChainVerificationResult(
                    valid = false,
                    entryCount = auditEntries.size,
                    brokenAtSequence = auditDiskParseErrorAtSequence,
                    headHash = auditEntries.lastOrNull()?.entryHash ?: AUDIT_GENESIS_HASH,
                    reason = auditDiskParseErrorReason ?: "Corrupted audit log entry on disk"
                )
            }
            return verifyEntryList(auditEntries)
        }
    }

    fun verifyAuditLogOnDisk(): AuditChainVerificationResult {
        synchronized(lock) {
            if (!auditLogFile.isFile) {
                return AuditChainVerificationResult(
                    valid = true,
                    entryCount = 0,
                    headHash = AUDIT_GENESIS_HASH,
                    reason = "OK (empty)"
                )
            }
            val parsed = mutableListOf<VaultAuditEntry>()
            val lines = runCatching { auditLogFile.readLines(StandardCharsets.UTF_8) }
                .getOrElse { err ->
                    return AuditChainVerificationResult(
                        valid = false,
                        entryCount = 0,
                        brokenAtSequence = 0L,
                        reason = "Failed to read audit log: ${err.javaClass.simpleName}"
                    )
                }
            var seqIdx = 0L
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                val entry = parseAuditEntryJson(line)
                    ?: return AuditChainVerificationResult(
                        valid = false,
                        entryCount = parsed.size,
                        brokenAtSequence = seqIdx,
                        headHash = parsed.lastOrNull()?.entryHash ?: AUDIT_GENESIS_HASH,
                        reason = "Malformed audit JSON line at sequence $seqIdx"
                    )
                parsed.add(entry)
                seqIdx += 1L
            }
            return verifyEntryList(parsed)
        }
    }

    private fun redactWithKeyedIndex(
        input: String,
        indexKey: RedactionIndexKey,
        indexMap: Map<Long, List<RedactionCandidate>>,
        minLen: Int
    ): String {
        val n = input.length
        var sb: StringBuilder? = null
        var lastCopied = 0
        var i = 0
        while (i <= n - minLen && i <= n - 4) {
            val tag = sipHash24Word(
                indexKey.sipK0,
                indexKey.sipK1,
                packFourChars(input[i], input[i + 1], input[i + 2], input[i + 3])
            )
            val candidates = indexMap[tag]
            if (candidates != null) {
                var matched: RedactionCandidate? = null
                for (cand in candidates) {
                    val len = cand.secretLength
                    if (i + len > n) continue
                    if (verifySliceMatchesDigest(input, i, len, indexKey.hmacKey, cand.fullDigest)) {
                        matched = cand
                        break
                    }
                }
                if (matched != null) {
                    val builder = sb ?: StringBuilder(n).also { sb = it }
                    if (i > lastCopied) {
                        builder.append(input, lastCopied, i)
                    }
                    builder.append("[REDACTED:").append(matched.handle).append(']')
                    i += matched.secretLength
                    lastCopied = i
                    continue
                }
            }
            i += 1
        }
        val builder = sb ?: return input
        if (lastCopied < n) {
            builder.append(input, lastCopied, n)
        }
        return builder.toString()
    }

    private fun verifySliceMatchesDigest(
        input: String,
        startIndex: Int,
        length: Int,
        hmacKey: SecretKey,
        expectedDigest: ByteArray
    ): Boolean {
        val sliceChars = CharArray(length)
        var sliceBytes: ByteArray? = null
        var actualDigest: ByteArray? = null
        return try {
            input.toCharArray(sliceChars, 0, startIndex, startIndex + length)
            sliceBytes = utf8EncodeChars(sliceChars)
            actualDigest = computeHmacSha256(hmacKey, sliceBytes)
            MessageDigest.isEqual(actualDigest, expectedDigest)
        } finally {
            Arrays.fill(sliceChars, '\u0000')
            sliceBytes?.let { Arrays.fill(it, 0.toByte()) }
            actualDigest?.let { Arrays.fill(it, 0.toByte()) }
        }
    }

    private fun computeHmacSha256(key: SecretKey, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return mac.doFinal(data)
    }

    private fun ensureLegacyEntriesIndexedLocked(indexKey: RedactionIndexKey) {
        var updatedAny = false
        val keys = entries.keys.toList()
        for (k in keys) {
            val e = entries[k] ?: continue
            if (e.prefixTag != null && e.fullDigestBase64.isNotBlank() && e.secretLength >= 4) {
                continue
            }
            if (e.secretLength in 1..3 && e.aadVersion >= VAULT_SCHEMA_VERSION) {
                continue
            }
            val decryptedChars = runCatching { decryptEntryToCharsLocked(e) }.getOrNull() ?: continue
            var plainBytes: ByteArray? = null
            try {
                val len = decryptedChars.size
                val tag = if (len >= 4) {
                    sipHash24Word(
                        indexKey.sipK0,
                        indexKey.sipK1,
                        packFourChars(decryptedChars[0], decryptedChars[1], decryptedChars[2], decryptedChars[3])
                    )
                } else {
                    null
                }
                val digestB64 = if (len >= 4) {
                    plainBytes = utf8EncodeChars(decryptedChars)
                    val digest = computeHmacSha256(indexKey.hmacKey, plainBytes)
                    try {
                        Base64.getEncoder().encodeToString(digest)
                    } finally {
                        Arrays.fill(digest, 0.toByte())
                    }
                } else {
                    ""
                }
                entries[k] = e.copy(
                    secretLength = len,
                    prefixTag = tag,
                    fullDigestBase64 = digestB64
                )
                updatedAny = true
            } finally {
                Arrays.fill(decryptedChars, '\u0000')
                plainBytes?.let { Arrays.fill(it, 0.toByte()) }
            }
        }
        if (updatedAny) {
            rebuildRedactionIndexLocked()
        }
    }

    private fun rebuildRedactionIndexLocked() {
        prefixIndex.clear()
        minIndexedSecretLength = Int.MAX_VALUE
        val grouped = HashMap<Long, MutableList<RedactionCandidate>>()
        val decoder = Base64.getDecoder()
        for (entry in entries.values) {
            val tag = entry.prefixTag ?: continue
            if (entry.secretLength < 4 || entry.fullDigestBase64.isBlank()) continue
            val digest = runCatching { decoder.decode(entry.fullDigestBase64) }.getOrNull() ?: continue
            if (digest.isEmpty()) continue
            if (entry.secretLength < minIndexedSecretLength) {
                minIndexedSecretLength = entry.secretLength
            }
            grouped.getOrPut(tag) { mutableListOf() }.add(
                RedactionCandidate(
                    handle = entry.summary.handle,
                    secretLength = entry.secretLength,
                    fullDigest = digest
                )
            )
        }
        for ((tag, list) in grouped) {
            prefixIndex[tag] = list.sortedByDescending { it.secretLength }
        }
    }

    private fun resolveOrCreateRedactionIndexKeyLocked(): RedactionIndexKey {
        cachedRedactionIndexKey?.let { return it }

        val rawKeyBytes: ByteArray = if (wrappedRedactionKeyIvB64.isNotBlank() && wrappedRedactionKeyCtB64.isNotBlank()) {
            decryptBytesLocked(
                wrappedRedactionKeyIvB64,
                wrappedRedactionKeyCtB64,
                REDACTION_INDEX_KEY_AAD.toByteArray(StandardCharsets.UTF_8)
            )
        } else {
            ByteArray(REDACTION_INDEX_KEY_BYTES).also { bytes ->
                secureRandom.nextBytes(bytes)
                val (ivB64, ctB64) = encryptBytesLocked(
                    bytes,
                    REDACTION_INDEX_KEY_AAD.toByteArray(StandardCharsets.UTF_8)
                )
                wrappedRedactionKeyIvB64 = ivB64
                wrappedRedactionKeyCtB64 = ctB64
            }
        }

        val hmacBytes = ByteArray(32)
        return try {
            val sipK0 = bytesToLongLE(rawKeyBytes, 0)
            val sipK1 = bytesToLongLE(rawKeyBytes, 8)
            System.arraycopy(rawKeyBytes, 16, hmacBytes, 0, 32)
            RedactionIndexKey(
                sipK0 = sipK0,
                sipK1 = sipK1,
                hmacKey = SecretKeySpec(hmacBytes, "HmacSHA256")
            ).also { cachedRedactionIndexKey = it }
        } finally {
            Arrays.fill(rawKeyBytes, 0.toByte())
            Arrays.fill(hmacBytes, 0.toByte())
        }
    }

    private fun sanitizeKey(raw: String): String {
        return raw.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_.-]+"), "_")
            .trim('_')
    }

    private fun sanitizeAuditField(raw: String, maxLen: Int = 128): String {
        return raw.replace(Regex("[\\r\\n\\t]+"), " ").trim().take(maxLen)
    }

    private fun buildMaskedPreview(plaintext: String, category: VaultSecretCategory): String {
        val trimmed = plaintext.trim()
        return when (category) {
            VaultSecretCategory.PAYMENT -> {
                val digits = trimmed.filter { it.isDigit() }
                if (digits.length >= 4) "••••-${digits.takeLast(4)}" else "••••••••"
            }
            VaultSecretCategory.IDENTITY -> {
                if (trimmed.length >= 4) "•••${trimmed.takeLast(2)}" else "••••"
            }
            VaultSecretCategory.ADDRESS -> {
                if (trimmed.length <= 6) "••••" else "${trimmed.take(4)}•••"
            }
            VaultSecretCategory.LOGIN, VaultSecretCategory.API_TOKEN -> {
                if (trimmed.contains("@")) {
                    val user = trimmed.substringBefore("@")
                    val domain = trimmed.substringAfter("@", "")
                    "${user.take(2)}••@$domain"
                } else {
                    "••••••••"
                }
            }
        }
    }

    private fun encryptBytesLocked(plainBytes: ByteArray, aadBytes: ByteArray): Pair<String, String> {
        val key = resolveOrCreateMasterKeyLocked()
        return encryptAesGcmWithKey(key, plainBytes, aadBytes, secureRandom)
    }

    private fun decryptBytesLocked(
        ivBase64: String,
        ciphertextBase64: String,
        aadBytes: ByteArray?
    ): ByteArray {
        val key = resolveOrCreateMasterKeyLocked()
        val decoder = Base64.getDecoder()
        val iv = decoder.decode(ivBase64)
        val encrypted = decoder.decode(ciphertextBase64)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aadBytes != null) {
            cipher.updateAAD(aadBytes)
        }
        return cipher.doFinal(encrypted)
    }

    private fun decryptEntryToChars(entry: StoredVaultEntry): CharArray {
        synchronized(lock) {
            return decryptEntryToCharsLocked(entry)
        }
    }

    private fun decryptEntryToCharsLocked(entry: StoredVaultEntry): CharArray {
        credentialDecryptCount.incrementAndGet()
        val aadBytes = if (entry.aadVersion >= VAULT_SCHEMA_VERSION) {
            buildAadBytes(entry.summary.handle)
        } else {
            null
        }
        val plainBytes = decryptBytesLocked(entry.ivBase64, entry.ciphertextBase64, aadBytes)
        return try {
            utf8DecodeToChars(plainBytes)
        } finally {
            Arrays.fill(plainBytes, 0.toByte())
        }
    }

    private fun resolveOrCreateMasterKeyLocked(): SecretKey {
        cachedSecretKey?.let { return it }

        // Prefer hardware-backed AndroidKeyStore on live Android devices unless a custom test/caller master key was supplied
        val ksKey = if (customMasterKey == null || customMasterKey.isEmpty()) {
            runCatching {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                val existing = keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey
                if (existing != null) {
                    existing
                } else {
                    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                    val spec = KeyGenParameterSpec.Builder(
                        KEYSTORE_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                    generator.init(spec)
                    generator.generateKey()
                }
            }.getOrNull()
        } else {
            null
        }

        if (ksKey != null) {
            cachedSecretKey = ksKey
            activeKeySourceForTesting = "ANDROID_KEYSTORE"
            return ksKey
        }

        // Portable OWASP PBKDF2WithHmacSHA256 (>= 210,000 iterations) + AES-256 fallback
        val parent = storageFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val saltFile = File(parent ?: File("."), ".vault_salt")
        val salt = if (saltFile.isFile && saltFile.length() >= MIN_LEGACY_SALT_BYTES.toLong()) {
            hardenOwnerOnlyFilePermissions(saltFile)
            val existingSalt = saltFile.readBytes()
            if (existingSalt.size >= SALT_BYTES) existingSalt.copyOf(SALT_BYTES) else existingSalt.copyOf(MIN_LEGACY_SALT_BYTES)
        } else {
            ByteArray(SALT_BYTES).also { bytes ->
                secureRandom.nextBytes(bytes)
                runCatching { writeOwnerOnlyBytes(saltFile, bytes) }
            }
        }

        var seedBytesCopy: ByteArray? = null
        var seedChars: CharArray? = null
        var pbeSpec: PBEKeySpec? = null
        var derivedBytes: ByteArray? = null

        return try {
            val envOverride = envMasterKeyProvider()?.trim()?.takeIf { it.isNotEmpty() }
            seedChars = when {
                customMasterKey != null && customMasterKey.isNotEmpty() -> {
                    activeKeySourceForTesting = "CUSTOM_MASTER_KEY"
                    seedBytesCopy = customMasterKey.copyOf()
                    Base64.getEncoder().encodeToString(seedBytesCopy).toCharArray()
                }
                envOverride != null -> {
                    activeKeySourceForTesting = "ENV_MASTER_KEY"
                    envOverride.toCharArray()
                }
                else -> {
                    activeKeySourceForTesting = "LOCAL_KEY_FILE"
                    val localKeyFile = File(parent ?: File("."), ".vault_local_key")
                    val localSeed = if (localKeyFile.isFile && localKeyFile.length() >= 24L) {
                        hardenOwnerOnlyFilePermissions(localKeyFile)
                        localKeyFile.readBytes()
                    } else {
                        ByteArray(32).also { bytes ->
                            secureRandom.nextBytes(bytes)
                            runCatching { writeOwnerOnlyBytes(localKeyFile, bytes) }
                        }
                    }
                    seedBytesCopy = localSeed
                    Base64.getEncoder().encodeToString(localSeed).toCharArray()
                }
            }

            pbeSpec = PBEKeySpec(seedChars, salt, PBKDF2_ITERATIONS, 256)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            derivedBytes = factory.generateSecret(pbeSpec).encoded
            SecretKeySpec(derivedBytes, "AES").also {
                cachedSecretKey = it
            }
        } finally {
            pbeSpec?.clearPassword()
            seedChars?.let { Arrays.fill(it, '\u0000') }
            seedBytesCopy?.let { Arrays.fill(it, 0.toByte()) }
            derivedBytes?.let { Arrays.fill(it, 0.toByte()) }
            Arrays.fill(salt, 0.toByte())
        }
    }

    private fun recordAuditEventLocked(
        action: String,
        handle: String,
        approvedReceiptId: String? = null,
        controlEpoch: Long? = null,
        outcome: String = "OK"
    ): VaultAuditEntry {
        val cleanAction = sanitizeAuditField(action.ifBlank { "UNKNOWN" }, 64)
        val cleanHandle = sanitizeAuditField(handle.ifBlank { "vault://unknown" }, 128)
        val cleanReceiptId = approvedReceiptId?.let { sanitizeAuditField(it, 128).ifBlank { null } }
        val cleanOutcome = sanitizeAuditField(outcome.ifBlank { "OK" }, 64)
        val seq = auditEntries.size.toLong()
        val ts = Instant.now().toString()
        val prevHash = auditEntries.lastOrNull()?.entryHash ?: AUDIT_GENESIS_HASH
        val entryHash = computeAuditEntryHash(
            sequence = seq,
            timestampIso = ts,
            action = cleanAction,
            handle = cleanHandle,
            approvedReceiptId = cleanReceiptId,
            controlEpoch = controlEpoch,
            outcome = cleanOutcome,
            prevHash = prevHash
        )
        val entry = VaultAuditEntry(
            sequence = seq,
            timestampIso = ts,
            action = cleanAction,
            handle = cleanHandle,
            approvedReceiptId = cleanReceiptId,
            controlEpoch = controlEpoch,
            outcome = cleanOutcome,
            prevHash = prevHash,
            entryHash = entryHash
        )
        auditEntries.add(entry)
        runCatching {
            appendOwnerOnlyLine(auditLogFile, serializeAuditEntryJson(entry).toString())
        }
        return entry
    }

    private fun serializeAuditEntryJson(entry: VaultAuditEntry): JSONObject = JSONObject().apply {
        put("sequence", entry.sequence)
        put("timestampIso", entry.timestampIso)
        put("action", entry.action)
        put("handle", entry.handle)
        if (entry.approvedReceiptId != null) put("approvedReceiptId", entry.approvedReceiptId)
        if (entry.controlEpoch != null) put("controlEpoch", entry.controlEpoch)
        put("outcome", entry.outcome)
        put("prevHash", entry.prevHash)
        put("entryHash", entry.entryHash)
    }

    private fun parseAuditEntryJson(line: String): VaultAuditEntry? {
        return runCatching {
            val obj = JSONObject(line)
            if (!obj.has("sequence") || !obj.has("timestampIso") || !obj.has("action") ||
                !obj.has("handle") || !obj.has("prevHash") || !obj.has("entryHash")
            ) {
                return null
            }
            VaultAuditEntry(
                sequence = obj.getLong("sequence"),
                timestampIso = obj.getString("timestampIso"),
                action = obj.getString("action"),
                handle = obj.getString("handle"),
                approvedReceiptId = if (obj.has("approvedReceiptId") && !obj.isNull("approvedReceiptId")) {
                    obj.optString("approvedReceiptId").takeIf { it.isNotBlank() }
                } else {
                    null
                },
                controlEpoch = if (obj.has("controlEpoch") && !obj.isNull("controlEpoch")) {
                    obj.getLong("controlEpoch")
                } else {
                    null
                },
                outcome = obj.optString("outcome", "OK"),
                prevHash = obj.getString("prevHash"),
                entryHash = obj.getString("entryHash")
            )
        }.getOrNull()
    }

    private fun verifyEntryList(list: List<VaultAuditEntry>): AuditChainVerificationResult {
        var expectedPrev = AUDIT_GENESIS_HASH
        for ((idx, entry) in list.withIndex()) {
            val expectedSeq = idx.toLong()
            if (entry.sequence != expectedSeq) {
                return AuditChainVerificationResult(
                    valid = false,
                    entryCount = list.size,
                    brokenAtSequence = expectedSeq,
                    headHash = expectedPrev,
                    reason = "Sequence mismatch at index $expectedSeq (found ${entry.sequence})"
                )
            }
            if (entry.prevHash != expectedPrev) {
                return AuditChainVerificationResult(
                    valid = false,
                    entryCount = list.size,
                    brokenAtSequence = expectedSeq,
                    headHash = expectedPrev,
                    reason = "Broken prevHash chain link at sequence $expectedSeq"
                )
            }
            val expectedHash = computeAuditEntryHash(
                sequence = entry.sequence,
                timestampIso = entry.timestampIso,
                action = entry.action,
                handle = entry.handle,
                approvedReceiptId = entry.approvedReceiptId,
                controlEpoch = entry.controlEpoch,
                outcome = entry.outcome,
                prevHash = entry.prevHash
            )
            if (entry.entryHash != expectedHash) {
                return AuditChainVerificationResult(
                    valid = false,
                    entryCount = list.size,
                    brokenAtSequence = expectedSeq,
                    headHash = expectedPrev,
                    reason = "SHA-256 entryHash mismatch (tampering detected) at sequence $expectedSeq"
                )
            }
            expectedPrev = entry.entryHash
        }
        return AuditChainVerificationResult(
            valid = true,
            entryCount = list.size,
            headHash = expectedPrev,
            reason = "OK"
        )
    }

    private fun loadAuditLogFromDisk() {
        synchronized(lock) {
            auditEntries.clear()
            auditDiskParseErrorAtSequence = null
            auditDiskParseErrorReason = null
            if (!auditLogFile.isFile) return
            val lines = runCatching { auditLogFile.readLines(StandardCharsets.UTF_8) }.getOrNull() ?: return
            var seqIdx = 0L
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                val parsed = parseAuditEntryJson(line)
                if (parsed == null) {
                    auditDiskParseErrorAtSequence = seqIdx
                    auditDiskParseErrorReason = "Malformed audit JSON line at sequence $seqIdx"
                    break
                }
                auditEntries.add(parsed)
                seqIdx += 1L
            }
        }
    }

    private fun loadFromDisk() {
        synchronized(lock) {
            entries.clear()
            prefixIndex.clear()
            minIndexedSecretLength = Int.MAX_VALUE
            wrappedRedactionKeyIvB64 = ""
            wrappedRedactionKeyCtB64 = ""
            cachedRedactionIndexKey = null
            if (!storageFile.isFile) {
                _handles.value = emptyList()
                return
            }
            runCatching {
                val root = JSONObject(storageFile.readText(StandardCharsets.UTF_8))
                val fileSchemaVersion = root.optInt("schemaVersion", 1)
                wrappedRedactionKeyIvB64 = root.optString("redactionKeyIvB64", "")
                wrappedRedactionKeyCtB64 = root.optString("redactionKeyCtB64", "")
                val arr = root.optJSONArray("entries") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val key = sanitizeKey(obj.optString("key"))
                    if (key.isEmpty()) continue
                    val cat = runCatching {
                        VaultSecretCategory.valueOf(obj.optString("category", VaultSecretCategory.LOGIN.name))
                    }.getOrDefault(VaultSecretCategory.LOGIN)
                    val summary = VaultHandleSummary(
                        key = key,
                        handle = "vault://$key",
                        label = obj.optString("label", key),
                        category = cat,
                        maskedPreview = obj.optString("maskedPreview", "••••••••"),
                        updatedAtMs = obj.optLong("updatedAtMs", System.currentTimeMillis())
                    )
                    val ivB64 = obj.optString("ivB64", "")
                    val ctB64 = obj.optString("ctB64", "")
                    val entryAadVersion = obj.optInt(
                        "aadVersion",
                        if (fileSchemaVersion >= VAULT_SCHEMA_VERSION) VAULT_SCHEMA_VERSION else 1
                    )
                    val secretLen = obj.optInt("secretLen", 0)
                    val prefixTag = if (obj.has("prefixTag") && !obj.isNull("prefixTag")) {
                        obj.optLong("prefixTag")
                    } else {
                        null
                    }
                    val fullDigestB64 = obj.optString("fullDigestB64", "")
                    if (ivB64.isNotBlank() && ctB64.isNotBlank()) {
                        entries[key] = StoredVaultEntry(
                            summary = summary,
                            ivBase64 = ivB64,
                            ciphertextBase64 = ctB64,
                            aadVersion = entryAadVersion,
                            secretLength = secretLen,
                            prefixTag = prefixTag,
                            fullDigestBase64 = fullDigestB64
                        )
                    }
                }
            }
            rebuildRedactionIndexLocked()
            _handles.value = entries.values.map { it.summary }.sortedByDescending { it.updatedAtMs }
        }
    }

    private fun saveToDiskLocked() {
        val arr = JSONArray()
        entries.values.forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("key", e.summary.key)
                    put("label", e.summary.label)
                    put("category", e.summary.category.name)
                    put("maskedPreview", e.summary.maskedPreview)
                    put("updatedAtMs", e.summary.updatedAtMs)
                    put("ivB64", e.ivBase64)
                    put("ctB64", e.ciphertextBase64)
                    put("aadVersion", e.aadVersion)
                    if (e.secretLength > 0) put("secretLen", e.secretLength)
                    if (e.prefixTag != null) put("prefixTag", e.prefixTag)
                    if (e.fullDigestBase64.isNotBlank()) put("fullDigestB64", e.fullDigestBase64)
                }
            )
        }
        val root = JSONObject().apply {
            put("schemaVersion", VAULT_SCHEMA_VERSION)
            put("kdfIterations", PBKDF2_ITERATIONS)
            if (wrappedRedactionKeyIvB64.isNotBlank() && wrappedRedactionKeyCtB64.isNotBlank()) {
                put("redactionKeyIvB64", wrappedRedactionKeyIvB64)
                put("redactionKeyCtB64", wrappedRedactionKeyCtB64)
            }
            put("entries", arr)
        }
        writeOwnerOnlyText(storageFile, root.toString(2))
    }
}

// =============================================================================
// 2. Pickle Irreversible Financial / Price-Drift Gate & CAPTCHA Takeover Guard
//    Non-bypassable checkout guard, strict non-bypass CAPTCHA/2FA handoff,
//    and explicit external approval receipt / control_epoch binding.
// =============================================================================

enum class HighStakesKind {
    NONE,
    FINANCIAL_COMMIT,
    PRICE_DRIFT_ALERT,
    CAPTCHA_BOT_GATE,
    TWO_FACTOR_OR_BIOMETRIC,
    SECURE_SURFACE_BLOCKED
}

/**
 * External approval receipt binding single-use `external_approvals` and `control_epoch`
 * verification for high-stakes actions.
 */
data class ExternalApprovalReceipt(
    val receiptId: String,
    val controlEpoch: Long,
    val approvedToolName: String = "",
    val approvedAmountText: String = "",
    val approvedTargetHint: String = "",
    val issuedAtMs: Long = System.currentTimeMillis(),
    val expiresAtMs: Long = Long.MAX_VALUE
)

data class ExternalApprovalValidation(
    val valid: Boolean,
    val receiptId: String = "",
    val controlEpoch: Long? = null,
    val rejectionCode: String = "",
    val reasonKo: String = ""
)

data class HighStakesAssessment(
    val kind: HighStakesKind,
    val merchantOrApp: String = "",
    val detectedAmountText: String = "",
    val previousApprovedAmount: String = "",
    val targetLabel: String = "",
    val reasonKo: String = "",
    val externalApprovalReceiptId: String? = null,
    val controlEpoch: Long? = null,
    val externalApprovalValidated: Boolean = false,
    val approvalRejectionCode: String = ""
)

object FinancialAndTakeoverGuard {

    private val KRW_REGEX = Regex("([0-9]{1,3}(?:,[0-9]{3})+)\\s*원")
    private val USD_REGEX = Regex("\\$\\s*([0-9]+(?:\\.[0-9]{2})?)")
    private val RECEIPT_ID_REGEX = Regex("^[A-Za-z0-9._:-]{6,128}$")

    private val CAPTCHA_KEYWORDS = listOf(
        "recaptcha",
        "hcaptcha",
        "cloudflare turnstile",
        "turnstile",
        "자동입력 방지",
        "보안문자",
        "사람인지 확인",
        "로봇이 아닙니다",
        "i'm not a robot",
        "verify you are human"
    )

    private val TWO_FA_BIOMETRIC_KEYWORDS = listOf(
        "보안키패드",
        "지문 인증",
        "생체 인증",
        "간편비밀번호 6자리",
        "ars 인증",
        "otp 입력",
        "보안카드 입력"
    )

    private val FINANCIAL_COMMIT_KEYWORDS = listOf(
        "결제하기",
        "결제 진행",
        "결제진행",
        "지금 결제",
        "주문하기",
        "송금하기",
        "이체하기",
        "예매하기",
        "예약 확정",
        "구독 결제",
        "pay now",
        "place order",
        "complete purchase",
        "checkout",
        "send money"
    )

    private val GUI_MUTATION_TOOLS = setOf(
        "click",
        "long_press",
        "click_sequence",
        "input_text",
        "swipe",
        "drag_and_drop",
        "haptic_trajectory",
        "vault_blind_inject"
    )

    fun validateExternalApprovalReceipt(
        receiptId: String?,
        controlEpoch: Long?,
        expectedControlEpoch: Long? = null,
        toolName: String = "",
        detectedAmountText: String = "",
        receipt: ExternalApprovalReceipt? = null,
        consumedReceiptIds: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis()
    ): ExternalApprovalValidation {
        val effectiveReceiptId = (receipt?.receiptId ?: receiptId).orEmpty().trim()
        val effectiveEpoch = receipt?.controlEpoch ?: controlEpoch

        if (effectiveReceiptId.isEmpty()) {
            return ExternalApprovalValidation(
                valid = false,
                controlEpoch = effectiveEpoch,
                rejectionCode = "MISSING_RECEIPT_ID",
                reasonKo = "외부 승인 영수증 ID(externalApprovalReceiptId)가 비어 있습니다."
            )
        }
        if (!RECEIPT_ID_REGEX.matches(effectiveReceiptId)) {
            return ExternalApprovalValidation(
                valid = false,
                receiptId = effectiveReceiptId,
                controlEpoch = effectiveEpoch,
                rejectionCode = "INVALID_RECEIPT_ID",
                reasonKo = "외부 승인 영수증 ID 형식이 유효하지 않습니다: $effectiveReceiptId"
            )
        }
        if (effectiveEpoch == null || effectiveEpoch < 0L) {
            return ExternalApprovalValidation(
                valid = false,
                receiptId = effectiveReceiptId,
                controlEpoch = effectiveEpoch,
                rejectionCode = "MISSING_CONTROL_EPOCH",
                reasonKo = "제어 에포크(controlEpoch)가 누락되었거나 유효하지 않습니다."
            )
        }
        if (expectedControlEpoch != null && effectiveEpoch != expectedControlEpoch) {
            return ExternalApprovalValidation(
                valid = false,
                receiptId = effectiveReceiptId,
                controlEpoch = effectiveEpoch,
                rejectionCode = "STALE_CONTROL_EPOCH",
                reasonKo = "승인 영수증의 controlEpoch($effectiveEpoch)가 현재 에포크($expectedControlEpoch)와 일치하지 않습니다."
            )
        }
        if (effectiveReceiptId in consumedReceiptIds) {
            return ExternalApprovalValidation(
                valid = false,
                receiptId = effectiveReceiptId,
                controlEpoch = effectiveEpoch,
                rejectionCode = "RECEIPT_REPLAYED",
                reasonKo = "이미 사용된 단일 승인 영수증($effectiveReceiptId)은 재사용할 수 없습니다."
            )
        }
        if (receipt != null && nowMs > receipt.expiresAtMs) {
            return ExternalApprovalValidation(
                valid = false,
                receiptId = effectiveReceiptId,
                controlEpoch = effectiveEpoch,
                rejectionCode = "RECEIPT_EXPIRED",
                reasonKo = "외부 승인 영수증($effectiveReceiptId)의 유효기간이 만료되었습니다."
            )
        }
        if (receipt != null &&
            receipt.approvedToolName.isNotBlank() &&
            toolName.isNotBlank() &&
            receipt.approvedToolName != toolName
        ) {
            return ExternalApprovalValidation(
                valid = false,
                receiptId = effectiveReceiptId,
                controlEpoch = effectiveEpoch,
                rejectionCode = "TOOL_MISMATCH",
                reasonKo = "승인된 도구(${receipt.approvedToolName})와 실행 도구($toolName)가 일치하지 않습니다."
            )
        }
        if (receipt != null &&
            receipt.approvedAmountText.isNotBlank() &&
            detectedAmountText.isNotBlank() &&
            receipt.approvedAmountText.trim() != detectedAmountText.trim()
        ) {
            return ExternalApprovalValidation(
                valid = false,
                receiptId = effectiveReceiptId,
                controlEpoch = effectiveEpoch,
                rejectionCode = "PRICE_DRIFT_RECEIPT_MISMATCH",
                reasonKo = "승인 영수증 금액(${receipt.approvedAmountText})과 현재 화면 금액($detectedAmountText)이 달라 재승인이 필요합니다."
            )
        }
        return ExternalApprovalValidation(
            valid = true,
            receiptId = effectiveReceiptId,
            controlEpoch = effectiveEpoch,
            reasonKo = "외부 승인 영수증 검증 완료 ($effectiveReceiptId · epoch=$effectiveEpoch)"
        )
    }

    fun extractPrimaryCurrencyAmount(snapshot: AtomicSnapshot?, fallbackText: String = ""): String {
        val lines = mutableListOf<String>()
        snapshot?.indexedElements?.forEach { el ->
            val combined = listOf(el.text, el.contentDesc).filter { it.isNotBlank() }.joinToString(" ")
            if (combined.isNotBlank()) lines.add(combined)
        }
        if (fallbackText.isNotBlank()) lines.add(fallbackText)

        // Prioritize lines explicitly naming total / pay / amount
        val prioritized = lines.sortedByDescending { line ->
            val lower = line.lowercase(Locale.ROOT)
            when {
                lower.contains("총") || lower.contains("합계") || lower.contains("결제") || lower.contains("total") || lower.contains("pay") -> 2
                else -> 1
            }
        }
        for (line in prioritized) {
            KRW_REGEX.find(line)?.let { m ->
                return "${m.groupValues[1]}원"
            }
            USD_REGEX.find(line)?.let { m ->
                return "$${m.groupValues[1]}"
            }
        }
        return ""
    }

    fun assessBeforeAction(
        toolName: String,
        args: JSONObject,
        snapshot: AtomicSnapshot?,
        lastApprovedAmount: String? = null,
        externalApprovalReceiptId: String? = null,
        controlEpoch: Long? = null,
        expectedControlEpoch: Long? = null,
        externalApprovalReceipt: ExternalApprovalReceipt? = null,
        consumedReceiptIds: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis()
    ): HighStakesAssessment {
        val argReceiptId = args.optString("external_approval_receipt_id", "")
            .ifBlank { args.optString("approval_receipt_id", "") }
            .trim()
            .takeIf { it.isNotEmpty() }
        val argEpoch = if (args.has("control_epoch") && !args.isNull("control_epoch")) {
            args.optLong("control_epoch", -1L).takeIf { it >= 0L }
        } else {
            null
        }
        val resolvedReceiptId = externalApprovalReceipt?.receiptId ?: externalApprovalReceiptId ?: argReceiptId
        val resolvedEpoch = externalApprovalReceipt?.controlEpoch ?: controlEpoch ?: argEpoch
        val hasExternalReceipt = externalApprovalReceipt != null || resolvedReceiptId != null || resolvedEpoch != null

        val surfaceTexts = buildList {
            if (!snapshot?.activeWindowTitle.isNullOrBlank()) add(snapshot!!.activeWindowTitle)
            snapshot?.indexedElements?.forEach { el ->
                if (el.text.isNotBlank()) add(el.text)
                if (el.contentDesc.isNotBlank()) add(el.contentDesc)
            }
        }
        val surfaceCombinedLower = surfaceTexts.joinToString("\n").lowercase(Locale.ROOT)
        val merchantOrApp = snapshot?.activePackage?.substringAfterLast('.')?.ifBlank { "현재 화면" } ?: "현재 화면"

        // 1. Strict CAPTCHA / Anti-Bot non-bypass check (NEVER touch CAPTCHAs, even with external receipt!)
        if (toolName in GUI_MUTATION_TOOLS && CAPTCHA_KEYWORDS.any { surfaceCombinedLower.contains(it) }) {
            return HighStakesAssessment(
                kind = HighStakesKind.CAPTCHA_BOT_GATE,
                merchantOrApp = merchantOrApp,
                targetLabel = "CAPTCHA / 봇 방지 검증",
                reasonKo = "CAPTCHA 또는 자동입력 방지(사람 확인) 화면이 감지되어 에이전트 조작을 즉시 일시정지했습니다. 화면에서 직접 인증을 완료한 뒤 '직접 완료 · 재개'를 눌러 주세요.",
                externalApprovalReceiptId = resolvedReceiptId,
                controlEpoch = resolvedEpoch,
                externalApprovalValidated = false,
                approvalRejectionCode = if (hasExternalReceipt) "NON_BYPASSABLE_HUMAN_TAKEOVER" else ""
            )
        }

        // 2. 2FA / Biometric / Secure Keypad Human Takeover check (NEVER bypassed by external receipt!)
        if (toolName in GUI_MUTATION_TOOLS && TWO_FA_BIOMETRIC_KEYWORDS.any { surfaceCombinedLower.contains(it) }) {
            return HighStakesAssessment(
                kind = HighStakesKind.TWO_FACTOR_OR_BIOMETRIC,
                merchantOrApp = merchantOrApp,
                targetLabel = "생체·OTP·보안키패드 인증",
                reasonKo = "보안키패드·생체인증·2차 인증 화면이 감지되었습니다. 보안을 위해 사용자가 직접 인증을 마친 뒤 '직접 완료 · 재개'를 눌러 주세요.",
                externalApprovalReceiptId = resolvedReceiptId,
                controlEpoch = resolvedEpoch,
                externalApprovalValidated = false,
                approvalRejectionCode = if (hasExternalReceipt) "NON_BYPASSABLE_HUMAN_TAKEOVER" else ""
            )
        }

        // 2B. Sensitive vault blind inject with explicit external approval receipt validation
        if (toolName == "vault_blind_inject" && hasExternalReceipt) {
            val validation = validateExternalApprovalReceipt(
                receiptId = resolvedReceiptId,
                controlEpoch = resolvedEpoch,
                expectedControlEpoch = expectedControlEpoch,
                toolName = toolName,
                detectedAmountText = "",
                receipt = externalApprovalReceipt,
                consumedReceiptIds = consumedReceiptIds,
                nowMs = nowMs
            )
            return HighStakesAssessment(
                kind = HighStakesKind.NONE,
                merchantOrApp = merchantOrApp,
                targetLabel = args.optString("handle", "vault_blind_inject"),
                reasonKo = validation.reasonKo,
                externalApprovalReceiptId = validation.receiptId.ifBlank { resolvedReceiptId },
                controlEpoch = validation.controlEpoch ?: resolvedEpoch,
                externalApprovalValidated = validation.valid,
                approvalRejectionCode = validation.rejectionCode
            )
        }

        // 3. Irreversible Checkout / Booking / Transfer & Price-Drift Guard
        val inputPressesEnter = when {
            args.has("press_enter") && !args.isNull("press_enter") -> args.optBoolean("press_enter", true)
            args.has("submit_enter") && !args.isNull("submit_enter") -> args.optBoolean("submit_enter", true)
            else -> true
        }
        val isPotentialCommitAction = toolName in setOf("click", "click_sequence", "long_press") ||
            (toolName == "input_text" && inputPressesEnter)
        if (!isPotentialCommitAction) {
            return HighStakesAssessment(kind = HighStakesKind.NONE)
        }

        val targetElement = resolveTargetElement(args, snapshot)
        val explicitDesc = args.optString("target_description", args.optString("description", "")).trim()
        val elementText = listOfNotNull(
            targetElement?.text?.takeIf { it.isNotBlank() },
            targetElement?.contentDesc?.takeIf { it.isNotBlank() }
        ).joinToString(" ").trim()
        val combinedTarget = listOf(elementText, explicitDesc).filter { it.isNotBlank() }.joinToString(" · ")
        val targetLower = combinedTarget.lowercase(Locale.ROOT)

        val matchedCommit = FINANCIAL_COMMIT_KEYWORDS.any { targetLower.contains(it) }
        if (!matchedCommit) {
            return HighStakesAssessment(kind = HighStakesKind.NONE)
        }

        val amount = extractPrimaryCurrencyAmount(snapshot, combinedTarget)
        val prevAmount = lastApprovedAmount?.trim().orEmpty()
        if (prevAmount.isNotEmpty() && amount.isNotEmpty() && prevAmount != amount) {
            return HighStakesAssessment(
                kind = HighStakesKind.PRICE_DRIFT_ALERT,
                merchantOrApp = merchantOrApp,
                detectedAmountText = amount,
                previousApprovedAmount = prevAmount,
                targetLabel = combinedTarget,
                reasonKo = "[금액 변동 재확인] 결제 직전 금액이 변경되었습니다 ($prevAmount → $amount). 대상: $merchantOrApp · 작업: $combinedTarget",
                externalApprovalReceiptId = resolvedReceiptId,
                controlEpoch = resolvedEpoch,
                externalApprovalValidated = false,
                approvalRejectionCode = if (hasExternalReceipt) "PRICE_DRIFT_REQUIRES_FRESH_APPROVAL" else ""
            )
        }

        val amountClause = if (amount.isNotEmpty()) " · 감지 금액: $amount" else ""

        if (hasExternalReceipt) {
            val validation = validateExternalApprovalReceipt(
                receiptId = resolvedReceiptId,
                controlEpoch = resolvedEpoch,
                expectedControlEpoch = expectedControlEpoch,
                toolName = toolName,
                detectedAmountText = amount,
                receipt = externalApprovalReceipt,
                consumedReceiptIds = consumedReceiptIds,
                nowMs = nowMs
            )
            if (validation.valid) {
                return HighStakesAssessment(
                    kind = HighStakesKind.NONE,
                    merchantOrApp = merchantOrApp,
                    detectedAmountText = amount,
                    previousApprovedAmount = prevAmount,
                    targetLabel = combinedTarget,
                    reasonKo = "[외부 승인 영수증 바인딩 완료] receipt=${validation.receiptId} · epoch=${validation.controlEpoch} · 대상: $merchantOrApp · 작업: $combinedTarget$amountClause",
                    externalApprovalReceiptId = validation.receiptId,
                    controlEpoch = validation.controlEpoch,
                    externalApprovalValidated = true
                )
            }
            if (validation.rejectionCode == "PRICE_DRIFT_RECEIPT_MISMATCH") {
                val receiptAmt = externalApprovalReceipt?.approvedAmountText.orEmpty()
                return HighStakesAssessment(
                    kind = HighStakesKind.PRICE_DRIFT_ALERT,
                    merchantOrApp = merchantOrApp,
                    detectedAmountText = amount,
                    previousApprovedAmount = receiptAmt.ifBlank { prevAmount },
                    targetLabel = combinedTarget,
                    reasonKo = "[금액 변동 재확인] 승인 영수증 금액($receiptAmt)과 현재 결제 금액($amount)이 다릅니다. 대상: $merchantOrApp · 작업: $combinedTarget",
                    externalApprovalReceiptId = validation.receiptId.ifBlank { resolvedReceiptId },
                    controlEpoch = validation.controlEpoch ?: resolvedEpoch,
                    externalApprovalValidated = false,
                    approvalRejectionCode = validation.rejectionCode
                )
            }
            return HighStakesAssessment(
                kind = HighStakesKind.FINANCIAL_COMMIT,
                merchantOrApp = merchantOrApp,
                detectedAmountText = amount,
                previousApprovedAmount = prevAmount,
                targetLabel = combinedTarget,
                reasonKo = "[결제·예약 최종 승인 · 외부 영수증 거부(${validation.rejectionCode})] ${validation.reasonKo} · 대상: $merchantOrApp · 작업: $combinedTarget$amountClause",
                externalApprovalReceiptId = validation.receiptId.ifBlank { resolvedReceiptId },
                controlEpoch = validation.controlEpoch ?: resolvedEpoch,
                externalApprovalValidated = false,
                approvalRejectionCode = validation.rejectionCode
            )
        }

        return HighStakesAssessment(
            kind = HighStakesKind.FINANCIAL_COMMIT,
            merchantOrApp = merchantOrApp,
            detectedAmountText = amount,
            previousApprovedAmount = prevAmount,
            targetLabel = combinedTarget,
            reasonKo = "[결제·예약 최종 승인] 대상: $merchantOrApp · 작업: $combinedTarget$amountClause"
        )
    }

    private fun resolveTargetElement(args: JSONObject, snapshot: AtomicSnapshot?): IndexedUiElement? {
        if (snapshot == null) return null
        if (args.has("element_index")) {
            val idx = args.optInt("element_index", -1)
            if (idx >= 0) {
                return snapshot.indexedElements.firstOrNull { it.index == idx }
            }
        }
        return null
    }
}

// =============================================================================
// 3. Pickle + Poke Condition Watcher & Cancellation-Ticket Sniper
// =============================================================================

enum class WatcherActionMode(val labelKo: String) {
    NOTIFY_ONLY("감지 시 즉시 알림"),
    HOLD_FOR_APPROVAL("승인 대기 예매 홀드")
}

data class WatcherRule(
    val id: String,
    val title: String,
    val targetAppOrUrl: String,
    val matchKeywords: List<String>,
    val soldOutKeywords: List<String> = listOf("매진", "품절", "예약마감", "0석", "sold out"),
    val intervalSec: Int = 60,
    val mode: WatcherActionMode = WatcherActionMode.HOLD_FOR_APPROVAL,
    val enabled: Boolean = true,
    val hitCount: Int = 0,
    val lastCheckedAtMs: Long = 0L,
    val lastHitSummary: String = ""
)

data class WatcherEvaluationHit(
    val rule: WatcherRule,
    val matchedLines: List<String>,
    val availableDetected: Boolean,
    val summaryKo: String
)

class ProactiveWatcherEngine @JvmOverloads constructor(
    private val storageFile: File
) {
    constructor(context: Context) : this(
        storageFile = File(context.applicationContext.filesDir, WATCHERS_FILE_NAME)
    )

    companion object {
        private const val WATCHERS_FILE_NAME = "hanson_pickle_watchers.json"
        private val AVAILABILITY_POSITIVE_HINTS = listOf(
            "예매", "예약가능", "잔여", "빈자리", "선택가능", "구매하기", "석 남", "book now", "available"
        )

        /**
         * Blanket wildcard keywords that MUST NEVER be used as a fallback trigger for
         * proactive watchers or notification auto-reply rules (would fire on every notification).
         */
        val FORBIDDEN_WILDCARD_KEYWORDS: Set<String> = setOf("전체", "모두", "all", "any", "*")

        fun isForbiddenWildcardKeyword(rawKeyword: String?): Boolean {
            val normalized = rawKeyword?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return normalized.isEmpty() || normalized in FORBIDDEN_WILDCARD_KEYWORDS
        }

        /**
         * Sanitizes a watcher or notification auto-reply trigger keyword.
         * Returns `null` when missing or a blanket wildcard (`"전체"`, `"모두"`, `"all"`, `"any"`, `"*"`),
         * NEVER defaulting an unmatched trigger keyword to `"전체"`.
         */
        fun sanitizeTriggerKeyword(rawKeyword: String?): String? {
            val trimmed = rawKeyword?.trim().orEmpty()
            return if (isForbiddenWildcardKeyword(trimmed)) null else trimmed
        }
    }

    private val lock = Any()
    private val rulesList = mutableListOf<WatcherRule>()
    private val _rules = MutableStateFlow<List<WatcherRule>>(emptyList())
    val rules: StateFlow<List<WatcherRule>> = _rules.asStateFlow()

    init {
        loadFromDisk()
    }

    fun addRule(
        title: String,
        targetAppOrUrl: String,
        matchKeywords: List<String>,
        soldOutKeywords: List<String> = listOf("매진", "품절", "예약마감", "0석", "sold out"),
        intervalSec: Int = 60,
        mode: WatcherActionMode = WatcherActionMode.HOLD_FOR_APPROVAL
    ): WatcherRule {
        val cleanKeywords = matchKeywords.mapNotNull { sanitizeTriggerKeyword(it) }.distinct()
        require(cleanKeywords.isNotEmpty()) {
            "At least one specific non-wildcard matchKeyword is required (blanket keywords like '전체' are not permitted)."
        }

        val rule = WatcherRule(
            id = "watch_${System.currentTimeMillis()}_${rulesList.size + 1}",
            title = title.trim().ifBlank { cleanKeywords.joinToString(" · ") },
            targetAppOrUrl = targetAppOrUrl.trim(),
            matchKeywords = cleanKeywords,
            soldOutKeywords = soldOutKeywords.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty {
                listOf("매진", "품절", "예약마감", "0석", "sold out")
            },
            intervalSec = intervalSec.coerceIn(10, 86_400),
            mode = mode,
            enabled = true
        )
        synchronized(lock) {
            rulesList.add(0, rule)
            saveToDiskLocked()
            _rules.value = rulesList.toList()
        }
        return rule
    }

    fun removeRule(id: String): Boolean {
        synchronized(lock) {
            val changed = rulesList.removeAll { it.id == id }
            if (changed) {
                saveToDiskLocked()
                _rules.value = rulesList.toList()
            }
            return changed
        }
    }

    fun toggleRule(id: String, enabled: Boolean): Boolean {
        synchronized(lock) {
            val idx = rulesList.indexOfFirst { it.id == id }
            if (idx < 0) return false
            rulesList[idx] = rulesList[idx].copy(enabled = enabled)
            saveToDiskLocked()
            _rules.value = rulesList.toList()
            return true
        }
    }

    fun listRules(): List<WatcherRule> = synchronized(lock) { rulesList.toList() }

    fun evaluateSnapshot(snapshot: AtomicSnapshot?, extraText: String = ""): List<WatcherEvaluationHit> {
        val candidateLines = buildList {
            snapshot?.indexedElements?.forEach { el ->
                val text = listOf(el.text, el.contentDesc).filter { it.isNotBlank() }.joinToString(" ").trim()
                if (text.isNotBlank()) add(text)
            }
            extraText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
        }
        if (candidateLines.isEmpty()) return emptyList()

        val combinedLower = candidateLines.joinToString("\n").lowercase(Locale.ROOT)
        val now = System.currentTimeMillis()
        val hits = mutableListOf<WatcherEvaluationHit>()

        synchronized(lock) {
            var modified = false
            for (i in rulesList.indices) {
                val rule = rulesList[i]
                if (!rule.enabled) continue

                val effectiveKeywords = rule.matchKeywords.mapNotNull { sanitizeTriggerKeyword(it) }
                if (effectiveKeywords.isEmpty()) continue

                val allKeywordsPresent = effectiveKeywords.all { kw ->
                    combinedLower.contains(kw.lowercase(Locale.ROOT))
                }
                if (!allKeywordsPresent) {
                    rulesList[i] = rule.copy(lastCheckedAtMs = now)
                    modified = true
                    continue
                }

                val soldOutLower = rule.soldOutKeywords.map { it.lowercase(Locale.ROOT) }
                val matchedLines = candidateLines.filter { line ->
                    val l = line.lowercase(Locale.ROOT)
                    effectiveKeywords.any { kw -> l.contains(kw.lowercase(Locale.ROOT)) }
                }.ifEmpty { candidateLines.take(3) }

                val availableLines = matchedLines.filter { line ->
                    val l = line.lowercase(Locale.ROOT)
                    soldOutLower.none { sold -> l.contains(sold) }
                }

                val globalPositive = candidateLines.any { line ->
                    val l = line.lowercase(Locale.ROOT)
                    AVAILABILITY_POSITIVE_HINTS.any { hint -> l.contains(hint) } &&
                        soldOutLower.none { sold -> l.contains(sold) }
                }

                val isAvailable = availableLines.isNotEmpty() || (globalPositive && soldOutLower.none { combinedLower.contains(it) })
                if (isAvailable) {
                    val snippet = availableLines.firstOrNull() ?: matchedLines.firstOrNull().orEmpty()
                    val summary = "[감시 포착 · ${rule.title}] 가용 상태 감지: ${snippet.take(80)} (${rule.mode.labelKo})"
                    val updated = rule.copy(
                        hitCount = rule.hitCount + 1,
                        lastCheckedAtMs = now,
                        lastHitSummary = summary
                    )
                    rulesList[i] = updated
                    modified = true
                    hits.add(
                        WatcherEvaluationHit(
                            rule = updated,
                            matchedLines = availableLines.ifEmpty { matchedLines }.take(5),
                            availableDetected = true,
                            summaryKo = summary
                        )
                    )
                } else {
                    rulesList[i] = rule.copy(lastCheckedAtMs = now)
                    modified = true
                }
            }
            if (modified) {
                saveToDiskLocked()
                _rules.value = rulesList.toList()
            }
        }
        return hits
    }

    private fun loadFromDisk() {
        synchronized(lock) {
            rulesList.clear()
            if (!storageFile.isFile) {
                _rules.value = emptyList()
                return
            }
            runCatching {
                val arr = JSONObject(storageFile.readText(StandardCharsets.UTF_8))
                    .optJSONArray("rules") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val matchArr = o.optJSONArray("matchKeywords") ?: JSONArray()
                    val soldArr = o.optJSONArray("soldOutKeywords") ?: JSONArray()
                    val matchList = (0 until matchArr.length())
                        .mapNotNull { sanitizeTriggerKeyword(matchArr.optString(it)) }
                        .distinct()
                    if (matchList.isEmpty()) continue
                    val soldList = (0 until soldArr.length()).mapNotNull { soldArr.optString(it).takeIf { s -> s.isNotBlank() } }
                    val mode = runCatching {
                        WatcherActionMode.valueOf(o.optString("mode", WatcherActionMode.HOLD_FOR_APPROVAL.name))
                    }.getOrDefault(WatcherActionMode.HOLD_FOR_APPROVAL)
                    rulesList.add(
                        WatcherRule(
                            id = o.optString("id", "watch_$i"),
                            title = o.optString("title", matchList.joinToString(" ")),
                            targetAppOrUrl = o.optString("targetAppOrUrl", ""),
                            matchKeywords = matchList,
                            soldOutKeywords = soldList.ifEmpty { listOf("매진", "품절", "예약마감", "0석", "sold out") },
                            intervalSec = o.optInt("intervalSec", 60),
                            mode = mode,
                            enabled = o.optBoolean("enabled", true),
                            hitCount = o.optInt("hitCount", 0),
                            lastCheckedAtMs = o.optLong("lastCheckedAtMs", 0L),
                            lastHitSummary = o.optString("lastHitSummary", "")
                        )
                    )
                }
            }
            _rules.value = rulesList.toList()
        }
    }

    private fun saveToDiskLocked() {
        val parent = storageFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val arr = JSONArray()
        rulesList.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("title", r.title)
                    put("targetAppOrUrl", r.targetAppOrUrl)
                    put("matchKeywords", JSONArray(r.matchKeywords))
                    put("soldOutKeywords", JSONArray(r.soldOutKeywords))
                    put("intervalSec", r.intervalSec)
                    put("mode", r.mode.name)
                    put("enabled", r.enabled)
                    put("hitCount", r.hitCount)
                    put("lastCheckedAtMs", r.lastCheckedAtMs)
                    put("lastHitSummary", r.lastHitSummary)
                }
            )
        }
        storageFile.writeText(
            JSONObject().put("rules", arr).toString(2),
            StandardCharsets.UTF_8
        )
    }
}

// =============================================================================
// 4. Pickle-to-Pickle Agent-to-Agent (`A2A`) Schedule & Venue Negotiator
// =============================================================================

data class TimeRangeSlot(
    val dayLabel: String,
    val startMinOfDay: Int,
    val endMinOfDay: Int
)

data class MeetingCandidate(
    val dayLabel: String,
    val startHHmm: String,
    val endHHmm: String,
    val durationMin: Int,
    val recommendedArea: String,
    val score: Int,
    val rationaleKo: String
)

data class A2ANegotiationOutcome(
    val peerName: String,
    val candidates: List<MeetingCandidate>,
    val summaryKo: String,
    val a2aJsonRpcPayload: JSONObject,
    val isLocalDraft: Boolean = true,
    val remoteEndpointUrl: String = ""
)

object PickleA2AScheduleNegotiator {

    private val TIME_RANGE_REGEX = Regex("(?:([가-힣a-zA-Z0-9./-]+)\\s+)?(\\d{1,2}):(\\d{2})\\s*[-~]\\s*(\\d{1,2}):(\\d{2})")
    private const val SAMPLE_PEER_FREE_CSV = "12:00-14:00, 18:30-21:00"

    fun parseSlotsCsv(rawCsv: String, defaultDay: String = "내일"): List<TimeRangeSlot> {
        if (rawCsv.isBlank()) return emptyList()
        return rawCsv.split(',', ';')
            .mapNotNull { chunk ->
                val match = TIME_RANGE_REGEX.find(chunk.trim()) ?: return@mapNotNull null
                val day = match.groupValues[1].trim().ifBlank { defaultDay }
                val sH = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                val sM = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null
                val eH = match.groupValues[4].toIntOrNull() ?: return@mapNotNull null
                val eM = match.groupValues[5].toIntOrNull() ?: return@mapNotNull null
                val startMin = (sH * 60 + sM).coerceIn(0, 24 * 60)
                val endMin = (eH * 60 + eM).coerceIn(0, 24 * 60)
                if (endMin > startMin) TimeRangeSlot(day, startMin, endMin) else null
            }
    }

    /**
     * Computes local slot-intersection candidates and prepares a JSON-RPC 2.0 proposal payload.
     * This method performs local offline scheduling math only and does NOT execute remote network
     * negotiation; returned summaries explicitly state offline/local draft status unless a real
     * remote A2A endpoint is configured and dispatched separately.
     */
    @JvmOverloads
    fun negotiate(
        peerName: String,
        durationMinutes: Int = 60,
        preferredArea: String = "성수·강남 중간지점",
        myBusyCsv: String = "",
        peerFreeCsv: String = SAMPLE_PEER_FREE_CSV,
        remoteEndpointUrl: String = ""
    ): A2ANegotiationOutcome {
        val cleanPeer = peerName.trim().ifBlank { "상대 에이전트" }
        val duration = durationMinutes.coerceIn(15, 360)
        val area = preferredArea.trim().ifBlank { "중간 지점 카페·레스토랑" }
        val cleanEndpoint = remoteEndpointUrl.trim()
        val hasRemoteEndpoint = cleanEndpoint.startsWith("http://", ignoreCase = true) ||
            cleanEndpoint.startsWith("https://", ignoreCase = true)
        val isLocalDraft = !hasRemoteEndpoint

        val myBusy = parseSlotsCsv(myBusyCsv)
        val parsedPeerFree = parseSlotsCsv(peerFreeCsv)
        val usedSamplePeerSlots = parsedPeerFree.isEmpty()
        val peerFree = parsedPeerFree.ifEmpty { parseSlotsCsv(SAMPLE_PEER_FREE_CSV) }

        val openWindows = mutableListOf<TimeRangeSlot>()
        for (free in peerFree) {
            var fragments = listOf(free)
            for (busy in myBusy) {
                if (busy.dayLabel != free.dayLabel) continue
                val nextFragments = mutableListOf<TimeRangeSlot>()
                for (frag in fragments) {
                    if (busy.endMinOfDay <= frag.startMinOfDay || busy.startMinOfDay >= frag.endMinOfDay) {
                        nextFragments.add(frag)
                    } else {
                        if (busy.startMinOfDay > frag.startMinOfDay) {
                            nextFragments.add(frag.copy(endMinOfDay = busy.startMinOfDay))
                        }
                        if (busy.endMinOfDay < frag.endMinOfDay) {
                            nextFragments.add(frag.copy(startMinOfDay = busy.endMinOfDay))
                        }
                    }
                }
                fragments = nextFragments
            }
            openWindows.addAll(fragments.filter { it.endMinOfDay - it.startMinOfDay >= duration })
        }

        val candidates = openWindows
            .map { slot ->
                val startMin = slot.startMinOfDay
                val endMin = minOf(slot.endMinOfDay, startMin + duration)
                val (score, rationale) = scoreWindow(startMin, endMin)
                MeetingCandidate(
                    dayLabel = slot.dayLabel,
                    startHHmm = formatMinutes(startMin),
                    endHHmm = formatMinutes(endMin),
                    durationMin = endMin - startMin,
                    recommendedArea = area,
                    score = score,
                    rationaleKo = rationale
                )
            }
            .sortedByDescending { it.score }
            .take(3)

        val candidatesArray = JSONArray()
        candidates.forEach { c ->
            candidatesArray.put(
                JSONObject().apply {
                    put("dayLabel", c.dayLabel)
                    put("start", c.startHHmm)
                    put("end", c.endHHmm)
                    put("durationMin", c.durationMin)
                    put("recommendedArea", c.recommendedArea)
                    put("score", c.score)
                    put("rationaleKo", c.rationaleKo)
                }
            )
        }

        val rpcPayload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", "a2a_sched_${System.currentTimeMillis()}")
            put("method", "pickle/propose_meeting_slots")
            put(
                "params",
                JSONObject().apply {
                    put("peerName", cleanPeer)
                    put("durationMinutes", duration)
                    put("preferredArea", area)
                    put("mode", if (hasRemoteEndpoint) "remote_proposal_ready" else "offline_local_draft")
                    put("dispatchedRemotely", false)
                    if (hasRemoteEndpoint) {
                        put("remoteEndpointUrl", cleanEndpoint)
                    }
                    put("candidates", candidatesArray)
                }
            )
        }

        val sampleHint = if (usedSamplePeerSlots) " (상대 가용 시간 미입력으로 기본 예시 시간대 적용)" else ""
        val summaryKo = if (candidates.isEmpty()) {
            "[오프라인 로컬 일정 초안] ${cleanPeer}님 기준 입력 시간대 분석 결과 ${duration}분 이상 겹치는 빈 시간대가 없어요. (원격 A2A 네트워크 통신이 아닌 로컬 계산 결과입니다.) 가능한 다른 시간 범위를 알려주세요."
        } else {
            val top = candidates.first()
            if (isLocalDraft) {
                "[오프라인 로컬 일정 제안 초안 · 원격 미전송] 원격 A2A 엔드포인트가 설정되지 않아 로컬 시간표 교집합으로 후보 ${candidates.size}개를 계산했어요$sampleHint. 추천 제안 초안: [${top.dayLabel} ${top.startHHmm}-${top.endHHmm} · $area] (${top.rationaleKo}). ${cleanPeer}님에게 직접 공유하거나 원격 A2A 엔드포인트를 연결해 전송해 주세요."
            } else {
                "[A2A 일정 제안 페이로드 초안 · 대상: $cleanEndpoint · 전송 대기] ${cleanPeer}님 대상 로컬 시간표 교집합 후보 ${candidates.size}개를 준비했어요$sampleHint. 최적 제안: [${top.dayLabel} ${top.startHHmm}-${top.endHHmm} · $area] (${top.rationaleKo})."
            }
        }

        return A2ANegotiationOutcome(
            peerName = cleanPeer,
            candidates = candidates,
            summaryKo = summaryKo,
            a2aJsonRpcPayload = rpcPayload,
            isLocalDraft = isLocalDraft,
            remoteEndpointUrl = if (hasRemoteEndpoint) cleanEndpoint else ""
        )
    }

    private fun scoreWindow(startMin: Int, endMin: Int): Pair<Int, String> {
        val mid = (startMin + endMin) / 2
        return when {
            mid in (18 * 60 + 30)..(20 * 60 + 30) -> 98 to "저녁 프라임 모임 시간대 (여유로운 식사·미팅)"
            mid in (11 * 60 + 30)..(13 * 60 + 30) -> 92 to "점심 시간대 최적 교집합"
            mid in (14 * 60)..(17 * 60 + 30) -> 85 to "오후 카페·미팅 적합 시간대"
            mid in (9 * 60 + 30)..(11 * 60 + 29) -> 80 to "오전 집중 미팅 시간대"
            else -> 70 to "가용 교집합 시간대"
        }
    }

    private fun formatMinutes(minOfDay: Int): String {
        val h = (minOfDay / 60).coerceIn(0, 24)
        val m = (minOfDay % 60).coerceIn(0, 59)
        return String.format(Locale.US, "%02d:%02d", h, m)
    }
}

// =============================================================================
// 5. Mobile-Agent-v3 `Notetaker` Cross-App Working Memory Scratchpad
// =============================================================================

data class ScratchpadEntry(
    val key: String,
    val value: String,
    val sourcePackage: String = "",
    val autoCaptured: Boolean = false,
    val updatedAtMs: Long = System.currentTimeMillis()
)

class NotetakerScratchpad @JvmOverloads constructor(
    private val storageFile: File
) {
    constructor(context: Context) : this(
        storageFile = File(context.applicationContext.filesDir, SCRATCHPAD_FILE_NAME)
    )

    companion object {
        private const val SCRATCHPAD_FILE_NAME = "hanson_notetaker_scratchpad.json"
        private const val MAX_ENTRIES = 30
        private val COUPON_CODE_REGEX = Regex("\\b([A-Z0-9]{4}-[A-Z0-9]{4})\\b")
        private val ORDER_ID_REGEX = Regex("(?:주문번호|예약번호|예매번호)[:\\s#]*([0-9A-Za-z\\-]{6,20})")
    }

    private val lock = Any()
    private val items = LinkedHashMap<String, ScratchpadEntry>()
    private val _entries = MutableStateFlow<List<ScratchpadEntry>>(emptyList())
    val entries: StateFlow<List<ScratchpadEntry>> = _entries.asStateFlow()

    init {
        loadFromDisk()
    }

    fun put(
        key: String,
        value: String,
        sourcePackage: String = "",
        autoCaptured: Boolean = false
    ): ScratchpadEntry {
        val cleanKey = key.trim().take(64).ifBlank { "note_${System.currentTimeMillis()}" }
        val entry = ScratchpadEntry(
            key = cleanKey,
            value = value.trim().take(320),
            sourcePackage = sourcePackage.trim(),
            autoCaptured = autoCaptured,
            updatedAtMs = System.currentTimeMillis()
        )
        synchronized(lock) {
            items[cleanKey] = entry
            if (items.size > MAX_ENTRIES) {
                val oldest = items.entries.minByOrNull { it.value.updatedAtMs }?.key
                if (oldest != null && oldest != cleanKey) items.remove(oldest)
            }
            saveToDiskLocked()
            _entries.value = items.values.sortedByDescending { it.updatedAtMs }
        }
        return entry
    }

    fun get(key: String): ScratchpadEntry? = synchronized(lock) { items[key.trim()] }

    fun remove(key: String): Boolean {
        synchronized(lock) {
            val removed = items.remove(key.trim()) != null
            if (removed) {
                saveToDiskLocked()
                _entries.value = items.values.sortedByDescending { it.updatedAtMs }
            }
            return removed
        }
    }

    fun clear() {
        synchronized(lock) {
            items.clear()
            saveToDiskLocked()
            _entries.value = emptyList()
        }
    }

    fun listEntries(): List<ScratchpadEntry> = synchronized(lock) {
        items.values.sortedByDescending { it.updatedAtMs }
    }

    /**
     * Automatically captures transient cross-app facts (order IDs, coupon/reservation codes,
     * primary amounts) so multi-app facts survive rolling screenshot compaction.
     * Always applies BlindCredentialVault redaction when available so raw secrets are never cached.
     */
    fun observeAndAutoExtract(
        snapshot: AtomicSnapshot?,
        vault: BlindCredentialVault? = null
    ): List<ScratchpadEntry> {
        if (snapshot == null || snapshot.indexedElements.isEmpty()) return emptyList()
        val captured = mutableListOf<ScratchpadEntry>()
        val rawSurface = snapshot.indexedElements.joinToString("\n") { el ->
            listOf(el.text, el.contentDesc).filter { it.isNotBlank() }.joinToString(" ")
        }
        val safeSurface = vault?.redactSensitiveText(rawSurface) ?: rawSurface

        ORDER_ID_REGEX.find(safeSurface)?.let { m ->
            val orderCode = m.groupValues[1]
            if (!orderCode.contains("REDACTED")) {
                captured.add(
                    put(
                        key = "최근 주문·예약번호",
                        value = orderCode,
                        sourcePackage = snapshot.activePackage,
                        autoCaptured = true
                    )
                )
            }
        }

        COUPON_CODE_REGEX.find(safeSurface)?.let { m ->
            val code = m.groupValues[1]
            if (!code.contains("REDACTED")) {
                captured.add(
                    put(
                        key = "캡처 코드(${code.take(4)})",
                        value = code,
                        sourcePackage = snapshot.activePackage,
                        autoCaptured = true
                    )
                )
            }
        }

        val amount = FinancialAndTakeoverGuard.extractPrimaryCurrencyAmount(snapshot)
        if (amount.isNotBlank()) {
            captured.add(
                put(
                    key = "관측 금액(${snapshot.activePackage.substringAfterLast('.').ifBlank { "screen" }})",
                    value = amount,
                    sourcePackage = snapshot.activePackage,
                    autoCaptured = true
                )
            )
        }
        return captured
    }

    fun toPromptBlock(maxChars: Int = 600): String {
        val current = listEntries()
        if (current.isEmpty()) return ""
        return buildString {
            appendLine("[Notetaker Working Memory Scratchpad]")
            for (e in current) {
                val src = e.sourcePackage.substringAfterLast('.').takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                val line = "- ${e.key}: ${e.value}$src"
                if (length + line.length + 2 > maxChars) break
                appendLine(line)
            }
        }.trim()
    }

    private fun loadFromDisk() {
        synchronized(lock) {
            items.clear()
            if (!storageFile.isFile) {
                _entries.value = emptyList()
                return
            }
            runCatching {
                val arr = JSONObject(storageFile.readText(StandardCharsets.UTF_8))
                    .optJSONArray("entries") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val key = o.optString("key").trim()
                    val value = o.optString("value").trim()
                    if (key.isEmpty() || value.isEmpty()) continue
                    items[key] = ScratchpadEntry(
                        key = key,
                        value = value,
                        sourcePackage = o.optString("sourcePackage", ""),
                        autoCaptured = o.optBoolean("autoCaptured", false),
                        updatedAtMs = o.optLong("updatedAtMs", System.currentTimeMillis())
                    )
                }
            }
            _entries.value = items.values.sortedByDescending { it.updatedAtMs }
        }
    }

    private fun saveToDiskLocked() {
        val parent = storageFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val arr = JSONArray()
        items.values.forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("key", e.key)
                    put("value", e.value)
                    put("sourcePackage", e.sourcePackage)
                    put("autoCaptured", e.autoCaptured)
                    put("updatedAtMs", e.updatedAtMs)
                }
            )
        }
        storageFile.writeText(
            JSONObject().put("entries", arr).toString(2),
            StandardCharsets.UTF_8
        )
    }
}

// =============================================================================
// 6. Minitap `mobile-use` Meta-Cognitive Loop Breaker & Contextor Compactor
// =============================================================================

enum class LoopPattern {
    NONE,
    STALE_ACTION_REPEAT,
    TWO_STEP_OSCILLATION
}

data class LoopDiagnosis(
    val isLooping: Boolean,
    val pattern: LoopPattern,
    val repeatCount: Int,
    val currentFingerprint: String,
    val escapeDirectiveKo: String
)

class MetaCognitiveLoopBreaker {

    private data class TracePoint(
        val fingerprint: String,
        val toolName: String,
        val argsJson: String
    )

    private val lock = Any()
    private val history = ArrayDeque<TracePoint>()
    private var consecutiveStaleRepeats = 0

    fun reset() {
        synchronized(lock) {
            history.clear()
            consecutiveStaleRepeats = 0
        }
    }

    fun computeScreenFingerprint(snapshot: AtomicSnapshot): String {
        val raw = buildString {
            append(snapshot.activePackage)
            append('|')
            append(snapshot.activeWindowTitle)
            snapshot.indexedElements.take(20).forEach { el ->
                append('|')
                append(el.shortClass)
                append(':')
                append(el.text.take(24))
                append(':')
                append(el.contentDesc.take(24))
                append(':')
                append(el.normX / 20)
                append(',')
                append(el.normY / 20)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(StandardCharsets.UTF_8))
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    fun recordAndDiagnose(
        snapshot: AtomicSnapshot,
        toolName: String,
        argsJson: String,
        treeDeltaRatio: Float
    ): LoopDiagnosis {
        val fp = computeScreenFingerprint(snapshot)
        synchronized(lock) {
            val normalizedArgs = argsJson.trim()
            val last = history.lastOrNull()
            val isStaleRepeat = last != null &&
                last.fingerprint == fp &&
                last.toolName == toolName &&
                last.argsJson == normalizedArgs &&
                treeDeltaRatio < 0.05f

            if (isStaleRepeat) {
                consecutiveStaleRepeats += 1
            } else if (!isStaleRepeat && last?.fingerprint != fp) {
                // Screen actually changed, though check 2-step oscillation below
                consecutiveStaleRepeats = 0
            }

            history.addLast(TracePoint(fp, toolName, normalizedArgs))
            while (history.size > 16) history.removeFirst()

            if (isStaleRepeat) {
                return LoopDiagnosis(
                    isLooping = true,
                    pattern = LoopPattern.STALE_ACTION_REPEAT,
                    repeatCount = consecutiveStaleRepeats,
                    currentFingerprint = fp,
                    escapeDirectiveKo = "[루프 탈출 지시] 직전 '$toolName' 시도 후 화면 변화가 없습니다(fingerprint=$fp). 같은 좌표를 반복하지 말고 BACK 키, 스크롤(swipe), 또는 다른 인덱스 요소 탐색으로 경로를 전환하세요."
                )
            }

            if (history.size >= 4) {
                val n = history.size
                val a1 = history.elementAt(n - 4)
                val b1 = history.elementAt(n - 3)
                val a2 = history.elementAt(n - 2)
                val b2 = history.elementAt(n - 1)
                val oscillating = a1.fingerprint == a2.fingerprint &&
                    b1.fingerprint == b2.fingerprint &&
                    a1.fingerprint != b1.fingerprint
                if (oscillating) {
                    return LoopDiagnosis(
                        isLooping = true,
                        pattern = LoopPattern.TWO_STEP_OSCILLATION,
                        repeatCount = 2,
                        currentFingerprint = fp,
                        escapeDirectiveKo = "[왕복 진동 탈출 지시] 두 화면(${a1.fingerprint} ↔ ${b1.fingerprint}) 사이를 왕복하고 있습니다. 상단 검색/딥링크 또는 BACK 뒤 다른 메뉴를 선택하세요."
                    )
                }
            }

            return LoopDiagnosis(
                isLooping = false,
                pattern = LoopPattern.NONE,
                repeatCount = 0,
                currentFingerprint = fp,
                escapeDirectiveKo = ""
            )
        }
    }
}

object ContextorCompactor {

    fun buildCompactOutline(
        snapshot: AtomicSnapshot,
        vault: BlindCredentialVault? = null,
        maxElements: Int = 36
    ): String {
        val prioritized = snapshot.indexedElements.sortedByDescending { el ->
            var score = 0
            if (el.focused) score += 8
            if (el.editable) score += 6
            if (el.clickable) score += 4
            if (el.scrollable) score += 2
            val txt = "${el.text} ${el.contentDesc}"
            if (txt.contains("원") || txt.contains("$") || txt.contains("결제") || txt.contains("검색")) score += 3
            score
        }.take(maxElements).sortedBy { it.index }

        val sb = StringBuilder()
        sb.appendLine("[Contextor Compact Outline]")
        sb.appendLine("pkg=${snapshot.activePackage.ifBlank { "unknown" }} win=\"${snapshot.activeWindowTitle.ifBlank { "main" }}\" total=${snapshot.indexedElements.size}")
        prioritized.forEach { el ->
            val flags = buildList {
                if (el.editable) add("edit")
                if (el.focused) add("focus")
                if (el.clickable) add("tap")
                if (el.scrollable) add("scroll")
            }.joinToString(",")
            val rawLabel = el.text.ifBlank { el.contentDesc }.replace("\n", " ").take(64)
            val safeLabel = vault?.redactSensitiveText(rawLabel) ?: rawLabel
            val idTag = el.resourceId.substringAfterLast('/').takeIf { it.isNotBlank() }?.let { " #$it" }.orEmpty()
            sb.appendLine("  [${el.index}] ${el.shortClass} \"$safeLabel\"$idTag (${el.normX},${el.normY}) [$flags]")
        }
        return vault?.redactSensitiveText(sb.toString().trim()) ?: sb.toString().trim()
    }
}
