package dev.pprog.viewer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/**
 * Thin, read-only Android shell. All PProg parsing and UI stay in GitHub Pages;
 * app shell changes require a new APK, but web changes do not.
 *
 * TXT is parsed by the existing web app and is never uploaded by the shell.
 */
class MainActivity : Activity() {
    companion object {
        private const val SITE_URL = "https://fda1111.github.io/Pprogmobile.github.io/"
        private const val TRUSTED_HOST = "fda1111.github.io"
        private const val TRUSTED_PATH = "/Pprogmobile.github.io/"
        private const val PICK_TXT = 1101
        private const val SAVE_EXPORT = 1102
        private const val MAX_EXPORT_BYTES = 64L * 1024L * 1024L
    }

    private data class ExportTransfer(
        val token: String,
        val file: File,
        val name: String,
        val mime: String,
        val stream: FileOutputStream,
        var bytesWritten: Long = 0L
    )

    private data class PendingSave(val file: File, val name: String, val mime: String)

    private lateinit var browser: WebView
    private lateinit var loadingBar: ProgressBar
    private val transferLock = Any()
    private var activeTransfer: ExportTransfer? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingSave: PendingSave? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(8, 15, 27)
        window.navigationBarColor = Color.rgb(8, 15, 27)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.rgb(8, 15, 27))
        browser = WebView(this)
        root.addView(browser, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        loadingBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        loadingBar.max = 100
        val progressParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP
        )
        root.addView(loadingBar, progressParams)
        setContentView(root)

        // Android 15+ enforces edge-to-edge drawing for target SDK 35.
        // Preserve system bar space so the existing PWA header is not obscured.
        if (Build.VERSION.SDK_INT >= 35) {
            window.decorView.setOnApplyWindowInsetsListener { _, insets ->
                val barInsets = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                root.setPadding(barInsets.left, barInsets.top, barInsets.right, barInsets.bottom)
                insets
            }
        }

        browser.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            if (Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false)
        browser.addJavascriptInterface(ExportBridge(), "PProgAndroid")

        browser.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null) return false
                this@MainActivity.fileChooserCallback?.onReceiveValue(null)
                this@MainActivity.fileChooserCallback = filePathCallback
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*" // Permit .TXT even if a document provider mislabels its MIME.
                    putExtra(
                        Intent.EXTRA_ALLOW_MULTIPLE,
                        fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                    )
                }
                return try {
                    startActivityForResult(intent, PICK_TXT)
                    true
                } catch (_: ActivityNotFoundException) {
                    this@MainActivity.fileChooserCallback = null
                    filePathCallback.onReceiveValue(null)
                    showToast("На устройстве нет выбора файлов")
                    true
                }
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                loadingBar.progress = newProgress
                loadingBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }

        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                val destination = request?.url ?: return false
                if (!request.isForMainFrame) return false
                if (isTrusted(destination)) return false
                openExternally(destination)
                return true
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    showToast("Нет доступа к PProg Viewer. Для первого запуска нужен интернет.")
                }
            }
        }

        // Ordinary URL downloads are opened by the system browser.
        // CSV/JSON/TXT blobs are instead saved via the native ExportBridge.
        browser.setDownloadListener { url, _, _, _, _ ->
            if (url.startsWith("https://")) {
                openExternally(Uri.parse(url))
            } else {
                showToast("Сохранение этого файла недоступно")
            }
        }

        browser.loadUrl(SITE_URL)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun isTrusted(uri: Uri): Boolean =
        uri.scheme.equals("https", true) &&
        uri.host.equals(TRUSTED_HOST, true) &&
        uri.path.orEmpty().startsWith(TRUSTED_PATH)

    private fun openExternally(uri: Uri) {
        val scheme = uri.scheme.orEmpty().lowercase()
        if (scheme !in setOf("https", "http", "mailto", "tel")) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            showToast("Не удалось открыть ссылку")
        }
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * The bridge is write-only: it cannot read device files or TXT data.
     * Each export is streamed to a temporary private file in small chunks,
     * then copied only to the location explicitly chosen by the user.
     */
    private inner class ExportBridge {
        @JavascriptInterface
        fun beginExport(fileName: String, mime: String): String = synchronized(transferLock) {
            try {
                activeTransfer?.let {
                    runCatching { it.stream.close() }
                    it.file.delete()
                }
                activeTransfer = null
                val token = UUID.randomUUID().toString()
                val tempFile = File(cacheDir, "pprog-export-" + token + ".tmp")
                val cleanedName = fileName
                    .replace('/', '_').replace('\\', '_')
                    .trim().take(120).ifBlank { "PProg-export.txt" }
                activeTransfer = ExportTransfer(
                    token, tempFile, cleanedName,
                    mime.substringBefore(';').trim().ifBlank { "application/octet-stream" },
                    FileOutputStream(tempFile)
                )
                token
            } catch (_: Exception) {
                ""
            }
        }

        @JavascriptInterface
        fun appendChunk(token: String, chunk: String): Boolean = synchronized(transferLock) {
            val transfer = activeTransfer ?: return@synchronized false
            if (token != transfer.token || chunk.length > 300_000) return@synchronized false
            try {
                val raw = Base64.decode(chunk, Base64.NO_WRAP)
                if (transfer.bytesWritten + raw.size > MAX_EXPORT_BYTES) {
                    runCatching { transfer.stream.close() }
                    transfer.file.delete()
                    activeTransfer = null
                    return@synchronized false
                }
                transfer.stream.write(raw)
                transfer.bytesWritten += raw.size
                true
            } catch (_: Exception) {
                runCatching { transfer.stream.close() }
                transfer.file.delete()
                activeTransfer = null
                false
            }
        }

        @JavascriptInterface
        fun finishExport(token: String): Boolean {
            val result = synchronized(transferLock) {
                val transfer = activeTransfer ?: return@synchronized null
                if (transfer.token != token) return@synchronized null
                activeTransfer = null
                try {
                    transfer.stream.close()
                    PendingSave(transfer.file, transfer.name, transfer.mime)
                } catch (_: Exception) {
                    transfer.file.delete()
                    null
                }
            } ?: return false

            runOnUiThread { launchSavePicker(result) }
            return true
        }

        @JavascriptInterface
        fun cancelExport(token: String) {
            synchronized(transferLock) {
                val transfer = activeTransfer ?: return@synchronized
                if (transfer.token != token) return@synchronized
                runCatching { transfer.stream.close() }
                transfer.file.delete()
                activeTransfer = null
            }
        }
    }

    private fun launchSavePicker(export: PendingSave) {
        if (isFinishing || pendingSave != null) {
            export.file.delete()
            showToast("Сначала завершите сохранение предыдущего файла")
            return
        }
        pendingSave = export
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = export.mime
            putExtra(Intent.EXTRA_TITLE, export.name)
        }
        try {
            startActivityForResult(intent, SAVE_EXPORT)
        } catch (_: ActivityNotFoundException) {
            pendingSave = null
            export.file.delete()
            showToast("На устройстве нет приложения для сохранения файла")
        }
    }

    @Deprecated("Android Activity result API; supported from minSdk 24.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            PICK_TXT -> {
                val callback = fileChooserCallback
                fileChooserCallback = null
                callback?.onReceiveValue(
                    if (resultCode == RESULT_OK)
                        WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                    else null
                )
            }
            SAVE_EXPORT -> {
                val export = pendingSave
                pendingSave = null
                if (export != null) {
                    val location = if (resultCode == RESULT_OK) data?.data else null
                    if (location == null) {
                        export.file.delete()
                    } else {
                        Thread {
                            val success = try {
                                contentResolver.openOutputStream(location, "w")?.use { output ->
                                    FileInputStream(export.file).use { input -> input.copyTo(output) }
                                    true
                                } ?: false
                            } catch (_: Exception) {
                                false
                            } finally {
                                export.file.delete()
                            }
                            showToast(if (success) "Файл сохранён" else "Ошибка сохранения файла")
                        }.start()
                    }
                }
            }
        }
    }

    @Deprecated("Legacy Activity navigation method")
    override fun onBackPressed() {
        if (::browser.isInitialized && browser.canGoBack()) browser.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        synchronized(transferLock) {
            activeTransfer?.let {
                runCatching { it.stream.close() }
                it.file.delete()
            }
            activeTransfer = null
        }
        if (::browser.isInitialized) {
            browser.stopLoading()
            browser.removeJavascriptInterface("PProgAndroid")
            browser.destroy()
        }
        super.onDestroy()
    }
}
