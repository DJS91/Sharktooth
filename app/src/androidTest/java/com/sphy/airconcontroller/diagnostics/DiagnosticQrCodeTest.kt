package com.sphy.airconcontroller.diagnostics

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.sphy.airconcontroller.DebugHubActivity
import com.sphy.airconcontroller.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticQrCodeTest {
    @Test fun generatedBitmapsScanToTheExactReportUrl() {
        for (url in listOf(REPORT_URL, "$REPORT_URL-${"aB9_-".repeat(80)}")) {
            for (size in listOf(240, 512)) {
                assertEquals(url, decode(diagnosticQrCode(url, size)))
            }
        }
    }

    @Test fun successfulUploadShowsScannableCodeAndOtherStatesClearIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, DebugHubActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as DebugHubActivity
        try {
            instrumentation.waitForIdleSync()
            runBlocking {
                withContext(Dispatchers.Main) {
                    val result = activity.findViewById<View>(R.id.diagStorageQrResult)
                    val image = activity.findViewById<ImageView>(R.id.diagStorageQrImage)
                    val status = activity.findViewById<TextView>(R.id.diagStorageStatus)
                    val button = activity.findViewById<Button>(R.id.sendDiagnosticStorageButton)
                    assertEquals(View.GONE, result.visibility)
                    for (nextState in listOf(
                        DiagnosticUpload.State(busy = true, progress = "Running diagnostics"),
                        DiagnosticUpload.State(error = "Upload unavailable"),
                        DiagnosticUpload.State(),
                    )) {
                        activity.renderDiagnosticUpload(DiagnosticUpload.State(url = REPORT_URL))
                        assertEquals(View.VISIBLE, result.visibility)
                        assertEquals(REPORT_URL, decode((image.drawable as BitmapDrawable).bitmap))
                        assertEquals(activity.getString(R.string.diag_storage_done, REPORT_URL), status.text.toString())
                        assertTrue(button.isEnabled)

                        activity.renderDiagnosticUpload(nextState)
                        assertEquals(View.GONE, result.visibility)
                        assertNull(image.drawable)
                        assertEquals(!nextState.busy, button.isEnabled)
                        assertFalse(status.text.contains(REPORT_URL))
                    }
                    activity.renderDiagnosticUpload(DiagnosticUpload.State(url = "$REPORT_URL-${"x".repeat(4000)}"))
                    assertEquals("An oversized URL must not crash or show a stale QR code", View.GONE, result.visibility)
                    assertNull(image.drawable)
                    assertTrue(status.text.contains(REPORT_URL))
                }
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun decode(bitmap: Bitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    private companion object {
        const val REPORT_URL = "https://aisenseapi.com/services/v1/storage/qr-code-test"
    }
}
