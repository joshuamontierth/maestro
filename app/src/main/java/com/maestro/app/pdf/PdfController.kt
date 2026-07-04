package com.maestro.app.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Thin wrapper around the platform [PdfRenderer]. PdfRenderer is not thread-safe and
 * only allows one page open at a time, so every method is synchronized on this
 * instance and renders are expected to run on a single background dispatcher.
 */
class PdfController {

    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null

    var pageCount: Int = 0
        private set

    @Synchronized
    fun open(file: File) {
        close()
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        descriptor = fd
        val r = PdfRenderer(fd)
        renderer = r
        pageCount = r.pageCount
    }

    /** Renders [index] into a bitmap [targetWidthPx] wide, preserving aspect ratio. */
    @Synchronized
    fun renderPage(index: Int, targetWidthPx: Int): Bitmap? {
        val r = renderer ?: return null
        if (index < 0 || index >= r.pageCount || targetWidthPx <= 0) return null

        val page = r.openPage(index)
        try {
            val ratio = page.height.toFloat() / page.width.toFloat()
            val width = targetWidthPx
            val height = (width * ratio).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            // Sheet music is black-on-white; paint a white backdrop before rendering.
            Canvas(bitmap).drawColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        } finally {
            page.close()
        }
    }

    @Synchronized
    fun close() {
        renderer?.close()
        renderer = null
        descriptor?.close()
        descriptor = null
        pageCount = 0
    }
}
