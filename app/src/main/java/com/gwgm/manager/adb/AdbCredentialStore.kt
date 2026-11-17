package com.gwgm.manager.adb

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * This phone's ADB identity (RSA key + self-signed certificate) that the watch trusts after pairing.
 *
 * Why not keep the RSA key itself inside Android Keystore? The ADB TLS handshake is done by the
 * bundled Conscrypt/libadb, which needs a key it can use directly; hardware-backed Keystore keys
 * can't be handed to it. So the RSA key is stored ENCRYPTED (AES-256-GCM) with a non-exportable
 * Android Keystore key, in no-backup storage. The certificate is public and stored as plain DER.
 */
class AdbCredentialStore(context: Context) {

    data class Credentials(val privateKey: PrivateKey, val certificate: X509Certificate)

    private val dir = context.noBackupFilesDir
    private val keyFile = File(dir, "adb_identity_key.enc")
    private val certFile = File(dir, "adb_identity_cert.der")

    fun hasCredentials(): Boolean = keyFile.exists() && certFile.exists()

    @Synchronized
    fun loadOrCreate(): Credentials = load() ?: create()

    @Synchronized
    fun delete() {
        keyFile.delete()
        certFile.delete()
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(WRAP_ALIAS)
        }
    }

    private fun load(): Credentials? {
        if (!hasCredentials()) return null
        return try {
            val pkcs8 = decrypt(keyFile.readBytes())
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(certFile.inputStream()) as X509Certificate
            Credentials(key, cert)
        } catch (e: Exception) {
            // Keystore key lost (e.g. after a security reset) — the old identity is unusable.
            // Caller will create a new identity; the watch will need to be paired again.
            delete()
            null
        }
    }

    private fun create(): Credentials {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val kp = kpg.generateKeyPair()

        val subject = X500Name("CN=GalaxyWatchGamingManager")
        val notBefore = Date(System.currentTimeMillis() - 24L * 3600 * 1000)
        val notAfter = Date(System.currentTimeMillis() + 3650L * 24 * 3600 * 1000)
        val builder = JcaX509v3CertificateBuilder(
            subject, BigInteger.valueOf(System.currentTimeMillis()), notBefore, notAfter, subject, kp.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        keyFile.writeBytes(encrypt(kp.private.encoded))
        certFile.writeBytes(cert.encoded)
        return Credentials(kp.private, cert)
    }

    // ---- Android Keystore wrapping ----

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        return byteArrayOf(iv.size.toByte()) + iv + ct
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        val ivLen = blob[0].toInt()
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val ct = blob.copyOfRange(1 + ivLen, blob.size)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val WRAP_ALIAS = "gwgm_adb_identity_wrap"
        const val AES_GCM = "AES/GCM/NoPadding"
    }
}
