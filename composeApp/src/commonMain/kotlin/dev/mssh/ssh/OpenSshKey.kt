package dev.mssh.ssh

/**
 * Minimal parser for OpenSSH private keys ("openssh-key-v1" PEM, unencrypted).
 * Supports ed25519; RSA keys are parsed but not yet usable for auth.
 */
object OpenSshKey {

    class ParsedKey(
        val type: String,
        val comment: String,
        val publicKeyBlob: ByteArray,
        val privateKey: ByteArray, // ed25519: 32-byte seed
    )

    fun parsePem(pem: String): ParsedKey {
        val lines = pem.lineSequence()
            .filter { !it.startsWith("-----") && it.isNotBlank() }
            .joinToString("")
        val decoded = base64Decode(lines)
        val r = SshReader(decoded)
        val magic = r.readStringUtf8()
        if (magic != "openssh-key-v1") throw SshException("不支持的私钥格式: $magic")
        val cipher = r.readStringUtf8()
        val kdf = r.readStringUtf8()
        r.readString() // kdf options
        val nkeys = r.readUInt32()
        val pubBlob = r.readString()
        val privateBlob = r.readString()

        if (cipher != "none" || kdf != "none") {
            throw SshException("暂不支持加密的私钥（请去除 passphrase 或使用其它工具转换）")
        }

        val pr = SshReader(privateBlob)
        val check1 = pr.readUInt32()
        val check2 = pr.readUInt32()
        if (check1 != check2) throw SshException("私钥文件损坏")
        val keyType = pr.readStringUtf8()
        when (keyType) {
            "ssh-ed25519" -> {
                val pub = pr.readString()
                val priv = pr.readString()
                if (priv.size != 64) throw SshException("ed25519 私钥长度错误")
                val comment = pr.readStringUtf8()
                return ParsedKey("ssh-ed25519", comment, pubBlob, priv.copyOf(32))
            }
            else -> throw SshException("暂不支持的密钥类型: $keyType（目前支持 ed25519）")
        }
    }

    fun ed25519PublicBlob(seed: ByteArray): ByteArray {
        val (pub) = dev.mssh.crypto.Ed25519.keyPairFromSeed(seed)
        val w = SshWriter()
        w.stringUtf8("ssh-ed25519")
        w.string(pub)
        return w.toByteArray()
    }

    private fun base64Decode(s: String): ByteArray {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val clean = s.filter { it != '=' }
        val out = ArrayList<Byte>(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = chars.indexOf(c)
            if (v < 0) continue
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add((buffer shr bits).toByte())
            }
        }
        return ByteArray(out.size) { out[it] }
    }
}
