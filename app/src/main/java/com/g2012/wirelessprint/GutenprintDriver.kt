package com.g2012.wirelessprint

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object GutenprintDriver {
    init {
        System.loadLibrary("g2012driver")
    }

    @JvmStatic
    private external fun nativeInit(xmlDirPath: String): String

    @JvmStatic
    private external fun nativeRenderPageToFile(
        rgbBytes: ByteArray,
        width: Int,
        height: Int,
        isColor: Boolean,
        outputPath: String
    ): String

    @Volatile
    private var initialized = false

    @Synchronized
    fun ensureInitialized(context: Context): String {
        if (initialized) return "OK"
        val xmlDir = File(context.filesDir, "gutenprint_xml")
        val marker = File(xmlDir, ".extracted_v2")
        if (!marker.exists()) {
            xmlDir.deleteRecursively()
            xmlDir.mkdirs()
            context.assets.open("gutenprint_xml.zip").use { input ->
                ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = File(xmlDir, entry.name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                zis.copyTo(fos)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            marker.writeText("ok")
        }
        val res = nativeInit(xmlDir.absolutePath)
        if (res == "OK") initialized = true
        return res
    }

    fun renderBitmapToCanonFile(bitmap: Bitmap, isColor: Boolean, outFile: File): String {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val rgb = ByteArray(w * h * 3)
        var j = 0
        for (i in pixels.indices) {
            val p = pixels[i]
            rgb[j++] = ((p shr 16) and 0xFF).toByte()
            rgb[j++] = ((p shr 8) and 0xFF).toByte()
            rgb[j++] = (p and 0xFF).toByte()
        }
        return nativeRenderPageToFile(rgb, w, h, isColor, outFile.absolutePath)
    }
}
