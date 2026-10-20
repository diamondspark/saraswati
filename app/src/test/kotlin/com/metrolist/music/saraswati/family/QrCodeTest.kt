/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 7 — the QR code is checked by decoding it again with
// ZXing's own reader, so a code that merely looks right but would not scan
// fails here. The rendering mirrors what the screen draws: modules scaled up
// inside a white quiet zone of four modules.
package com.metrolist.music.saraswati.family

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeTest {
    private fun decode(matrix: QrMatrix): String {
        val scale = 6
        val quiet = 4
        val side = (matrix.size + 2 * quiet) * scale
        val pixels = IntArray(side * side) { 0xFFFFFFFF.toInt() }
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (!matrix[x, y]) continue
                for (dy in 0 until scale) {
                    for (dx in 0 until scale) {
                        pixels[((y + quiet) * scale + dy) * side + (x + quiet) * scale + dx] = 0xFF000000.toInt()
                    }
                }
            }
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        return QRCodeReader().decode(bitmap).text
    }

    @Test
    fun `a pairing link encodes to a code that decodes back to the same link`() {
        val link = "https://saraswati-sangat.example.workers.dev/pair#" + "0123456789abcdef".repeat(4)

        val matrix = QrCode.encode(link)

        assertNotNull(matrix)
        assertEquals(link, decode(matrix!!))
    }

    @Test
    fun `the code is square and carries no quiet zone of its own`() {
        val matrix = QrCode.encode("https://example.com/pair#abc")!!

        assertTrue(matrix.size in 21..177)
        // The top-left finder pattern starts at the very first module.
        assertTrue(matrix[0, 0])
        assertTrue(matrix[matrix.size - 1, 0])
        assertTrue(matrix[0, matrix.size - 1])
    }

    @Test
    fun `text too long for any QR code gives no code instead of throwing`() {
        assertNull(QrCode.encode("x".repeat(10_000)))
    }
}
