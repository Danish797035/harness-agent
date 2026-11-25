package com.meenai.mina.cap.phone.shell.adb

import android.content.Context
import android.net.ssl.SSLSockets
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.meenai.mina.core.adb.AdbIdentity
import com.meenai.mina.core.adb.AdbIdentitySource
import com.meenai.mina.core.adb.AdbKeyStore
import com.meenai.mina.core.adb.AdbTls
import com.meenai.mina.core.adb.KeyingMaterialExporter
import com.meenai.mina.core.adb.WrappedKeyFile
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.net.ssl.SSLException

/** Adb identity and TLS on Android: The key is wrapped with the AES key in Keystore and placed in the App private directory; export the key and use platform TLS. */
object AndroidAdb {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val WRAP_ALIAS = "mina_adb_key_wrap"
    private const val TAG = "MinaAdbKey"

    // The device side exports without context; in TLS 1.3, the result of empty context is the same as
    // without context (RFC 8446 §7.5)
    val exporter = KeyingMaterialExporter { socket, label, length ->
        SSLSockets.exportKeyingMaterial(socket, label, ByteArray(0), length) ?: throw SSLException("keying material export failed")
    }

    @Volatile private var source: AdbIdentitySource? = null

    /** The only source of identity in this process: pairing and connection are obtained from here, and the same key is obtained. */
    fun identities(context: Context): AdbIdentitySource = source ?: synchronized(this) {
        source ?: AdbIdentitySource(keyStore(context), { "mina@${Build.MODEL}" }) { msg, e -> Log.w(TAG, msg, e) }.also { source = it }
    }

    /** The identity used for connection; [com.meenai.mina.core.adb.AdbKeyUnreadableException] is thrown when the stored key cannot be read. */
    fun identity(context: Context): AdbIdentity = identities(context).forConnect()

    /** Identity for pairing: Replace with a new one when the stored key cannot be read (see [AdbIdentitySource.forPairing]). */
    fun pairingIdentity(context: Context): AdbIdentity = identities(context).forPairing()

    fun tls(identity: AdbIdentity) = AdbTls(identity, exporter)

    /** Delete the key and the cache in this process (for "unpairing"); the authorization record on the device must be revoked by the user in the system settings. */
    fun forget(context: Context) = identities(context).forget()

    /** Key file + the Keystore key that wraps it: both are deleted when clearing. */
    private fun keyStore(context: Context): AdbKeyStore {
        val file = WrappedKeyFile(File(context.applicationContext.noBackupFilesDir, "adb/adbkey.bin")) { wrappingKey() }
        return object : AdbKeyStore by file {
            override fun clear() {
                file.clear()
                runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(WRAP_ALIAS) }
            }
        }
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}
