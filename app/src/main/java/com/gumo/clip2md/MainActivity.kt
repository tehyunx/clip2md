package com.gumo.clip2md

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var editResult: EditText
    private lateinit var previewContainer: FrameLayout
    private var bridgeReady = false
    private var pendingHtml: String? = null
    private var lastRawHtml: String? = null
    private var showingRaw = false
    private var showingPreview = false
    private var debugServer: DebugServer? = null

    private val openTreeLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) writeMarkdownToTree(uri)
        }

    private val historyLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val merged = result.data?.getStringExtra(HistoryActivity.EXTRA_MERGED_MARKDOWN)
                val id = result.data?.getLongExtra(HistoryActivity.EXTRA_ENTRY_ID, -1) ?: -1
                when {
                    merged != null -> loadMergedMarkdown(merged)
                    id != -1L -> loadHistoryEntry(id)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editResult = findViewById(R.id.editResult)
        previewContainer = findViewById(R.id.previewContainer)

        // Restore state that would otherwise be lost if Android kills this
        // process in the background (very common — this is NOT a fresh
        // launch, it's a state restore after process death) and re-apply it
        // once the WebView bridge is ready to receive convert()/renderPreview().
        val restoredText = savedInstanceState?.getString(KEY_TEXT)
        val restoredRaw = savedInstanceState?.getString(KEY_RAW)
        val restoredShowingRaw = savedInstanceState?.getBoolean(KEY_SHOWING_RAW) ?: false
        val restoredShowingPreview = savedInstanceState?.getBoolean(KEY_SHOWING_PREVIEW) ?: false
        lastRawHtml = restoredRaw
        showingRaw = restoredShowingRaw
        if (restoredText != null) editResult.setText(restoredText)

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                bridgeReady = true
                pendingHtml?.let { html ->
                    convert(html, skipDisplayIfShowingRaw = true) { md ->
                        HistoryStore.save(this@MainActivity, md, html)
                    }
                }
                pendingHtml = null
                if (restoredShowingPreview) togglePreview()
            }
        }
        webView.loadUrl("file:///android_asset/bridge.html")
        previewContainer.addView(webView)

        findViewById<Button>(R.id.btnPaste).setOnClickListener { pasteFromClipboard() }
        findViewById<Button>(R.id.btnCopy).setOnClickListener { copyResultToClipboard() }
        findViewById<Button>(R.id.btnListSave).setOnClickListener { saveToListAndReset() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveResultToFile() }
        findViewById<Button>(R.id.btnRaw).setOnClickListener { toggleRawHtml() }
        findViewById<Button>(R.id.btnPreview).setOnClickListener { togglePreview() }
        findViewById<Button>(R.id.btnHistory).setOnClickListener {
            historyLauncher.launch(Intent(this, HistoryActivity::class.java))
        }

        handleIncomingIntent(intent)

        debugServer = DebugServer(this).also { it.start() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TEXT, editResult.text.toString())
        outState.putString(KEY_RAW, lastRawHtml)
        outState.putBoolean(KEY_SHOWING_RAW, showingRaw)
        outState.putBoolean(KEY_SHOWING_PREVIEW, showingPreview)
    }

    override fun onDestroy() {
        debugServer?.stop()
        super.onDestroy()
    }

    companion object {
        private const val KEY_TEXT = "text"
        private const val KEY_RAW = "raw"
        private const val KEY_SHOWING_RAW = "showing_raw"
        private const val KEY_SHOWING_PREVIEW = "showing_preview"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val htmlText = intent.getStringExtra(Intent.EXTRA_HTML_TEXT)
                val plainText = intent.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    !htmlText.isNullOrBlank() -> convertOrQueue(htmlText)
                    !plainText.isNullOrBlank() -> editResult.setText(plainText)
                }
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val text = input.bufferedReader().readText()
                        showEditMode()
                        showingRaw = false
                        lastRawHtml = null
                        editResult.setText(text)
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "파일을 열 수 없습니다: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip: ClipData? = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) {
            Toast.makeText(this, "클립보드가 비어 있습니다", Toast.LENGTH_SHORT).show()
            return
        }
        val item = clip.getItemAt(0)
        val html = item.htmlText
        if (!html.isNullOrBlank()) {
            convertOrQueue(html)
        } else {
            val text = item.coerceToText(this)?.toString().orEmpty()
            editResult.setText(text)
        }
    }

    private fun convertOrQueue(html: String) {
        lastRawHtml = html
        showingRaw = false
        showEditMode()
        if (bridgeReady) {
            convert(html) { md -> HistoryStore.save(this, md, html) }
        } else {
            pendingHtml = html
        }
    }

    private fun saveToListAndReset() {
        val markdown = editResult.text.toString()
        if (markdown.isBlank()) {
            Toast.makeText(this, "저장할 내용이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        val raw = lastRawHtml
        Toast.makeText(this, "이미지 포함 저장 중...", Toast.LENGTH_SHORT).show()
        Thread {
            val id = HistoryStore.newId()
            val rewritten = try {
                ImageDownloader.process(markdown, HistoryStore.imagesDir(this, id), absoluteLinks = true)
            } catch (e: Exception) {
                markdown
            }
            HistoryStore.save(this, id, rewritten, raw)
            runOnUiThread {
                Toast.makeText(this, "기록에 저장했습니다", Toast.LENGTH_SHORT).show()
                resetToNewMemo()
            }
        }.start()
    }

    private fun resetToNewMemo() {
        lastRawHtml = null
        showingRaw = false
        showEditMode()
        editResult.setText("")
    }

    private fun loadHistoryEntry(id: Long) {
        val md = HistoryStore.loadMarkdown(this, id) ?: return
        lastRawHtml = HistoryStore.loadRawHtml(this, id)
        showingRaw = false
        showEditMode()
        editResult.setText(md)
    }

    private fun loadMergedMarkdown(merged: String) {
        // Merged from multiple entries — no single raw HTML applies anymore.
        lastRawHtml = null
        showingRaw = false
        showEditMode()
        editResult.setText(merged)
        Toast.makeText(this, "선택한 기록을 병합했습니다", Toast.LENGTH_SHORT).show()
    }

    private fun convert(html: String, skipDisplayIfShowingRaw: Boolean = false, onDone: ((String) -> Unit)? = null) {
        val escaped = org.json.JSONObject.quote(html)
        webView.evaluateJavascript("convertHtml($escaped)") { result ->
            val markdown = try {
                JSONTokener(result).nextValue() as? String ?: result
            } catch (e: Exception) {
                result
            }
            runOnUiThread {
                if (!skipDisplayIfShowingRaw || !showingRaw) editResult.setText(markdown)
            }
            onDone?.invoke(markdown)
        }
    }

    private fun toggleRawHtml() {
        val raw = lastRawHtml
        if (raw == null) {
            Toast.makeText(this, "아직 변환한 내용이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        showEditMode()
        showingRaw = !showingRaw
        if (showingRaw) {
            editResult.setText(raw)
        } else {
            convert(raw)
        }
    }

    private fun togglePreview() {
        showingPreview = !showingPreview
        if (showingPreview) {
            val markdown = editResult.text.toString()
            val escaped = org.json.JSONObject.quote(markdown)
            webView.evaluateJavascript("renderPreview($escaped)", null)
            editResult.visibility = View.GONE
            previewContainer.visibility = View.VISIBLE
        } else {
            showEditMode()
        }
    }

    private fun showEditMode() {
        showingPreview = false
        previewContainer.visibility = View.GONE
        editResult.visibility = View.VISIBLE
    }

    private fun copyResultToClipboard() {
        val text = editResult.text.toString()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("markdown", text))
        Toast.makeText(this, "클립보드에 복사했습니다", Toast.LENGTH_SHORT).show()
    }

    private fun saveResultToFile() {
        if (editResult.text.toString().isBlank()) {
            Toast.makeText(this, "저장할 내용이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        openTreeLauncher.launch(null)
    }

    private fun writeMarkdownToTree(treeUri: Uri) {
        val markdown = editResult.text.toString()
        Toast.makeText(this, "이미지 포함 저장 중...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val root = DocumentFile.fromTreeUri(this, treeUri)
                    ?: throw IllegalStateException("폴더를 열 수 없습니다")

                val tempImagesDir = java.io.File(cacheDir, "export_${System.currentTimeMillis()}")
                val rewritten = try {
                    ImageDownloader.process(markdown, tempImagesDir, absoluteLinks = false)
                } catch (e: Exception) {
                    markdown
                }

                root.findFile("note.md")?.delete()
                val noteFile = root.createFile("text/markdown", "note")
                    ?: throw IllegalStateException("note.md 생성 실패")
                contentResolver.openOutputStream(noteFile.uri)?.use { out ->
                    out.write(rewritten.toByteArray())
                }

                if (tempImagesDir.exists() && tempImagesDir.listFiles()?.isNotEmpty() == true) {
                    root.findFile("images")?.delete()
                    val imagesDoc = root.createDirectory("images")
                    tempImagesDir.listFiles()?.forEach { imgFile ->
                        val mime = when (imgFile.extension.lowercase()) {
                            "png" -> "image/png"
                            "gif" -> "image/gif"
                            "webp" -> "image/webp"
                            else -> "image/jpeg"
                        }
                        val dest = imagesDoc?.createFile(mime, imgFile.nameWithoutExtension)
                        if (dest != null) {
                            contentResolver.openOutputStream(dest.uri)?.use { out ->
                                imgFile.inputStream().use { it.copyTo(out) }
                            }
                        }
                    }
                }
                tempImagesDir.deleteRecursively()

                runOnUiThread { Toast.makeText(this, "저장했습니다 (note.md + images)", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "저장 실패: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    // ---- Debug HTTP API hooks (called from DebugServer's background thread
    // only via runOnUiThread; must not be called directly off the UI thread) ----

    fun currentMarkdown(): String = editResult.text.toString()

    fun currentRawHtml(): String? = lastRawHtml

    fun convertForDebug(html: String, callback: (String) -> Unit) {
        lastRawHtml = html
        showingRaw = false
        showEditMode()
        if (bridgeReady) {
            convert(html) { md ->
                HistoryStore.save(this, md, html)
                callback(md)
            }
        } else {
            pendingHtml = html
            callback("")
        }
    }

    fun pasteForDebug(callback: (String) -> Unit) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip: ClipData? = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) {
            callback("")
            return
        }
        val item = clip.getItemAt(0)
        val html = item.htmlText
        if (!html.isNullOrBlank()) {
            convertForDebug(html, callback)
        } else {
            val text = item.coerceToText(this)?.toString().orEmpty()
            editResult.setText(text)
            callback(text)
        }
    }

    /** Renders the CURRENT edit-box markdown to HTML via marked.js and returns
     *  the resulting body innerHTML, so preview correctness can be checked
     *  without eyes on the device (e.g. whether an <img> tag is really there). */
    fun renderPreviewForDebug(callback: (String) -> Unit) {
        val markdown = editResult.text.toString()
        val escaped = org.json.JSONObject.quote(markdown)
        webView.evaluateJavascript("renderPreview($escaped); document.body.innerHTML;") { result ->
            val html = try {
                JSONTokener(result).nextValue() as? String ?: result
            } catch (e: Exception) {
                result
            }
            callback(html)
        }
    }
}
