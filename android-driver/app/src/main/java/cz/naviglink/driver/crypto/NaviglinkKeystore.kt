package cz.naviglink.driver.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toSeed
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * Ed25519 klíče driveru. Privátní klíč je uložen v EncryptedSharedPreferences,
 * který šifruje hodnoty AES-GCM 256 s master key vázaným na Android Keystore.
 *
 * Tj. privátní klíč:
 *   - je software-Ed25519 (Bouncy Castle), ne hardware-backed
 *   - v paměti je jen krátce, jinak je šifrovaný
 *   - master key je vázaný na zařízení (po factory reset se rozbije, jak má)
 *
 * Pro v2 zvážit přímou hardware-backed Ed25519 (Android Keystore API 33+),
 * jakmile pilot validuje minimum SDK 33 jako akceptovatelné.
 */
class NaviglinkKeystore(context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private var cachedPrivateKey: Ed25519PrivateKeyParameters? = null
    private var cachedPublicKeyHex: String? = null

    /** Hex-encoded Ed25519 public key (32 bajtů = 64 hex znaků). Použít jako `authors[0]`. */
    val publicKeyHex: String
        get() {
            cachedPublicKeyHex?.let { return it }
            ensureKeysExist()
            return prefs.getString(KEY_PUBLIC, null)
                ?: throw IllegalStateException("Public key missing after generation")
        }

    /** Podepiš UTF-8 bajt-sekvenci. Vrací base64-encoded signature (kompatibilní s POST do backendu). */
    fun sign(canonicalBytes: ByteArray): String {
        val signer = Ed25519Signer()
        signer.init(true, getPrivateKey())
        signer.update(canonicalBytes, 0, canonicalBytes.size)
        val sig = signer.generateSignature()
        return android.util.Base64.encodeToString(sig, android.util.Base64.NO_WRAP)
    }

    /** Smaže klíče. Po volání musí být regenerated (volat ensureKeysExist nebo publicKeyHex). */
    fun resetKeys() {
        prefs.edit().clear().apply()
        cachedPrivateKey = null
        cachedPublicKeyHex = null
    }

    /**
     * Vrací BIP39 mnemonic (12 slov), pokud byl klíč vygenerován v této verzi.
     * Klíče vygenerované ve starší verzi vrátí null — uživatel musí vytvořit
     * novou identitu, aby získal mnemonic.
     */
    fun getMnemonicOrNull(): String? = prefs.getString(KEY_MNEMONIC, null)

    /**
     * Obnoví identitu z BIP39 frázové zálohy (12 slov, anglický slovník).
     *
     * Algoritmus identický s admin webem (@scure/bip39):
     *   1) Validate mnemonic (throws při neplatné)
     *   2) BIP39 PBKDF2-HMAC-SHA512 → 64-byte seed
     *   3) Vezmi prvních 32 bajtů jako Ed25519 private key
     *
     * Po volání nahradí stávající klíč. Předchozí klíč ztratí dohledatelnost.
     *
     * @throws Mnemonics.InvalidWordException pokud slovo není v BIP39 slovníku
     * @throws Mnemonics.WordCountException pokud počet slov není 12/15/18/21/24
     * @throws Mnemonics.ChecksumException pokud kontrolní suma fráze nesedí
     */
    fun restoreFromMnemonic(words: String) {
        val cleaned = words.trim().lowercase().split(Regex("\\s+")).joinToString(" ")
        val code = Mnemonics.MnemonicCode(cleaned.toCharArray())
        code.validate()  // throws checked exception při neplatné frázi

        val seed = code.toSeed()  // 64-byte BIP39 seed
        val privBytes = seed.copyOfRange(0, 32)
        val priv = Ed25519PrivateKeyParameters(privBytes, 0)
        val pub = priv.generatePublicKey()

        prefs.edit()
            .putString(KEY_PRIVATE, privBytes.toHex())
            .putString(KEY_PUBLIC, pub.encoded.toHex())
            .putString(KEY_MNEMONIC, cleaned)
            .apply()

        cachedPrivateKey = priv
        cachedPublicKeyHex = pub.encoded.toHex()
    }

    private fun getPrivateKey(): Ed25519PrivateKeyParameters {
        cachedPrivateKey?.let { return it }
        ensureKeysExist()
        val privHex = prefs.getString(KEY_PRIVATE, null)
            ?: throw IllegalStateException("Private key missing after generation")
        val privBytes = privHex.hexToBytes()
        val parsed = Ed25519PrivateKeyParameters(privBytes, 0)
        cachedPrivateKey = parsed
        return parsed
    }

    private fun ensureKeysExist() {
        if (prefs.contains(KEY_PUBLIC) && prefs.contains(KEY_PRIVATE)) return
        // Nová identita — vždy přes BIP39, aby měla mnemonic backup
        val code = Mnemonics.MnemonicCode(Mnemonics.WordCount.COUNT_12)
        val mnemonic = String(code.chars)
        val seed = code.toSeed()
        val privBytes = seed.copyOfRange(0, 32)
        val priv = Ed25519PrivateKeyParameters(privBytes, 0)
        val pub = priv.generatePublicKey()

        prefs.edit()
            .putString(KEY_PRIVATE, privBytes.toHex())
            .putString(KEY_PUBLIC, pub.encoded.toHex())
            .putString(KEY_MNEMONIC, mnemonic)
            .apply()
        cachedPublicKeyHex = pub.encoded.toHex()
    }

    companion object {
        private const val PREFS_FILE = "naviglink_keys"
        private const val KEY_PRIVATE = "ed25519_private_hex"
        private const val KEY_PUBLIC = "ed25519_public_hex"
        private const val KEY_MNEMONIC = "bip39_mnemonic"
    }
}

/** Hex helpers — jednoduché, bez external dependency. */
internal fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte) }

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length" }
    return ByteArray(length / 2) { i ->
        ((this[i * 2].digitToInt(16) shl 4) + this[i * 2 + 1].digitToInt(16)).toByte()
    }
}

/** Content-addressed identifier — BLAKE2b-128 → base32 (bez paddingu) → lowercase. */
object ContentId {
    fun compute(canonicalBytes: ByteArray): String {
        val digest = org.bouncycastle.crypto.digests.Blake2bDigest(128)
        digest.update(canonicalBytes, 0, canonicalBytes.size)
        val hash = ByteArray(16)
        digest.doFinal(hash, 0)
        return "naviglink:" + base32NoPadding(hash).lowercase()
    }

    private fun base32NoPadding(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var bits = 0
        var value = 0
        val sb = StringBuilder()
        for (b in bytes) {
            value = (value shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(alphabet[(value ushr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) {
            sb.append(alphabet[(value shl (5 - bits)) and 31])
        }
        return sb.toString()
    }
}
