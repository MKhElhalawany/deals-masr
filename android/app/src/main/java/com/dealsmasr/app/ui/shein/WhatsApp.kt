package com.dealsmasr.app.ui.shein

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.net.URLEncoder

object WhatsApp {
    private val packages = listOf("com.whatsapp", "com.whatsapp.w4b")

    private fun installed(ctx: Context): String? = packages.firstOrNull {
        runCatching { ctx.packageManager.getPackageInfo(it, 0) }.isSuccess
    }

    /**
     * Sends the order summary. With local images (no backend upload) the photos are attached;
     * otherwise opens a chat with the shop number (wa.me) if configured.
     */
    fun sendOrder(ctx: Context, text: String, shopNumber: String, localImages: List<String>) {
        val images = localImages.map { File(it) }.filter { it.exists() }
        val pkg = installed(ctx)
        val intent = when {
            images.isNotEmpty() -> {
                val uris = ArrayList<Uri>(images.map { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", it) })
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "image/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    putExtra(Intent.EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    pkg?.let { setPackage(it) }
                }
            }
            shopNumber.isNotBlank() -> Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://wa.me/${shopNumber.filter(Char::isDigit)}?text=${URLEncoder.encode(text, "UTF-8")}"),
            )
            else -> Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                pkg?.let { setPackage(it) }
            }
        }
        val launch = if (intent.`package` == null && intent.action != Intent.ACTION_VIEW)
            Intent.createChooser(intent, "إرسال الطلب") else intent
        runCatching { ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { ctx.startActivity(Intent.createChooser(intent, "إرسال الطلب").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
