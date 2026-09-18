package com.nogirelay.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GIF 动图的判定是纯字节逻辑，放在 JVM 测试里钉住：博客正文图只有命中这里才会走
 * AnimatedImageDrawable，否则又会退回 BitmapFactory 的第一帧静态图。
 */
class RemoteImageGifTest {

    private fun ascii(value: String) = value.toByteArray(Charsets.US_ASCII)

    @Test
    fun acceptsBothGifSignatures() {
        assertTrue(isGifSignature(ascii("GIF87a")))
        assertTrue(isGifSignature(ascii("GIF89a")))
    }

    @Test
    fun acceptsHeaderCarryingTrailingBytes() {
        // 真实文件头后面还跟着逻辑屏幕尺寸等数据，判定只看前 6 个字节。
        assertTrue(isGifSignature(ascii("GIF89a").plus(byteArrayOf(0x40, 0x01, 0x40, 0x01))))
    }

    @Test
    fun rejectsNonGifAndShortHeaders() {
        assertFalse(isGifSignature(ascii("GIF90a")))
        assertFalse(isGifSignature(ascii("GIF89")))
        assertFalse(isGifSignature(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertFalse(isGifSignature(ByteArray(0)))
    }
}
