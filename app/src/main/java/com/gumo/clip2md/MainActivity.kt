package com.gumo.clip2md

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONTokener
import java.io.OutputStreamWriter

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var editResult: EditText
    private var bridgeReady = false
    private var pendingHtml: String? = null
    private var lastRawHtml: String? = null
    private var showingRaw = false

    private val createDocumentLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
            if (uri != null) writeMarkdownToUri(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editResult = findViewById(R.id.editResult)

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                bridgeReady = true
                pendingHtml?.let { convert(it) }
                pendingHtml = null
            }
        }
        webView.loadUrl("file:///android_asset/bridge.html")

        findViewById<Button>(R.id.btnPaste).setOnClickListener { pasteFromClipboard() }
        findViewById<Button>(R.id.btnCopy).setOnClickListener { copyResultToClipboard() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveResultToFile() }
        findViewById<Button>(R.id.btnRaw).setOnClickListener { toggleRawHtml() }

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val htmlText = intent.getStringExtra(Intent.EXTRA_HTML_TEXT)
            val plainText = intent.getStringExtra(Intent.EXTRA_TEXT)
            when {
                !htmlText.isNullOrBlank() -> convertOrQueue(htmlText)
                !plainText.isNullOrBlank() -> editResult.setText(plainText)
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
        if (bridgeReady) convert(html) else pendingHtml = html
    }

    private fun convert(html: String) {
        val escaped = org.json.JSONObject.quote(html)
        webView.evaluateJavascript("convertHtml($escaped)") { result ->
            val markdown = try {
                JSONTokener(result).nextValue() as? String ?: result
            } catch (e: Exception) {
                result
            }
            runOnUiThread { editResult.setText(markdown) }
        }
    }

    private fun toggleRawHtml() {
        val raw = lastRawHtml
        if (raw == null) {
            Toast.makeText(this, "아직 변환한 내용이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        showingRaw = !showingRaw
        if (showingRaw) {
            editResult.setText(raw)
        } else {
            convert(raw)
        }
    }

    private fun copyResultToClipboard() {
        val text = editResult.text.toString()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("markdown", text))
        Toast.makeText(this, "클립보드에 복사했습니다", Toast.LENGTH_SHORT).show()
    }

    private fun saveResultToFile() {
        createDocumentLauncher.launch("clip.md")
    }

    private fun writeMarkdownToUri(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                OutputStreamWriter(out).use { writer ->
                    writer.write(editResult.text.toString())
                }
            }
            Toast.makeText(this, "저장했습니다", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "저장 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
