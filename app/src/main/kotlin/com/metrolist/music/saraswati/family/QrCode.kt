/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 7 — the owner's pairing link as a QR code, for a family
// member sitting next to them whose camera app opens it. Encoding only: no
// scanning code and no camera permission are added to the app.
package com.metrolist.music.saraswati.family

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** A square grid of QR modules without a quiet zone; the screen draws the margin. */
class QrMatrix internal constructor(
    private val bits: BitMatrix,
) {
    val size: Int get() = bits.width

    operator fun get(
        x: Int,
        y: Int,
    ): Boolean = bits.get(x, y)
}

object QrCode {
    fun encode(text: String): QrMatrix? =
        try {
            val hints =
                mapOf(
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    EncodeHintType.CHARACTER_SET to "UTF-8",
                    EncodeHintType.MARGIN to 0,
                )
            QrMatrix(QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints))
        } catch (e: WriterException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
}
