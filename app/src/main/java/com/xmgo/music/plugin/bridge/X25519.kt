package com.xmgo.music.plugin.bridge

import java.math.BigInteger

/**
 * X25519（RFC 7748）纯 Kotlin 实现，BigInteger 版本。
 * 用于 netease 插件 xeapi 传输的 ECDH（ephPriv→ephPub、ephPriv×peerPub→shared）。
 *
 * 已用 RFC 7748 §5.2 测试向量验证：
 *   x25519(a, 9) == X(a)；x25519(a, X(b)) == x25519(b, X(a)) == K
 * 性能：每次约几十毫秒（BigInteger 255 位 ladder），插件启动期调用 2 次，可接受。
 */
object X25519 {
    private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24 = BigInteger.valueOf(121665)
    private val TWO = BigInteger.valueOf(2)

    /** base point u = 9（little-endian 32 字节）。 */
    val BASE_POINT: ByteArray = ByteArray(32).also { it[0] = 9 }

    /**
     * @param scalar 32 字节 scalar（内部会做 clamp）
     * @param point  32 字节 u 坐标（内部会 mask 最高位）
     * @return 32 字节共享密钥 / 公钥
     */
    fun scalarMult(scalar: ByteArray, point: ByteArray): ByteArray {
        val kBytes = ByteArray(32).also {
            System.arraycopy(scalar, 0, it, 0, minOf(32, scalar.size))
        }
        kBytes[0] = (kBytes[0].toInt() and 248).toByte()
        kBytes[31] = (kBytes[31].toInt() and 127).toByte()
        kBytes[31] = (kBytes[31].toInt() or 64).toByte()
        val k = fromLittleEndian(kBytes)

        val uBytes = ByteArray(32).also {
            System.arraycopy(point, 0, it, 0, minOf(32, point.size))
        }
        uBytes[31] = (uBytes[31].toInt() and 127).toByte()
        val x1 = fromLittleEndian(uBytes)

        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val kt = if (k.testBit(t)) 1 else 0
            swap = swap xor kt
            if (swap == 1) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = kt

            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            val sum = da.add(cb).mod(P)
            val diff = da.subtract(cb).mod(P)
            x3 = sum.multiply(sum).mod(P)
            z3 = x1.multiply(diff.multiply(diff).mod(P)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e)).mod(P)).mod(P)
        }
        if (swap == 1) {
            val tx = x2; x2 = x3; x3 = tx
            val tz = z2; z2 = z3; z3 = tz
        }
        val result = x2.multiply(z2.modPow(P.subtract(TWO), P)).mod(P)
        return toLittleEndian(result, 32)
    }

    private fun fromLittleEndian(b: ByteArray): BigInteger {
        val rev = ByteArray(b.size)
        for (i in b.indices) rev[i] = b[b.size - 1 - i]
        return BigInteger(1, rev)
    }

    private fun toLittleEndian(v: BigInteger, len: Int): ByteArray {
        val be = v.toByteArray()
        val out = ByteArray(len)
        for (i in 0 until len) {
            val idx = be.size - 1 - i
            out[i] = if (idx >= 0) be[idx] else 0
        }
        return out
    }
}
