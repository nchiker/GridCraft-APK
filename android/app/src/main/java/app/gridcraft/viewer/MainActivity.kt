package app.gridcraft.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.Executors

/**
 * One screen: a slim bar (menu, file name, open, undo, redo, save) over GridCraft's web build,
 * which runs unchanged in a WebView from the app's assets (see host.js for the page side).
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var title: TextView
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var ready = false
    private var nextId = 1
    private val waiting = HashMap<Int, (JSONObject) -> Unit>()

    /** The open file: where it came from (null = new workbook) and its name. */
    private var docUri: Uri? = null
    private var docName = "Book1.xlsx"
    private var queuedIntent: Intent? = null
    private var lastCopied: String? = null

    /** Bytes the page hands over for a save, and what to do once they are written. */
    private var saveBytes: ByteArray? = null
    private var afterSave: (() -> Unit)? = null

    @Volatile private var pendingName: String? = null
    @Volatile private var pendingData: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        setUpWebView()
        queuedIntent = intent
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { onBack() }
        }
    }

    @Deprecated("Below Android 13 only; newer versions use the OnBackInvokedCallback")
    override fun onBackPressed() = onBack()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (ready) openIntent(intent) else queuedIntent = intent
    }

    override fun onDestroy() {
        web.destroy()
        io.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- screen

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildUi() {
        window.setDecorFitsSystemWindowsCompat()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bar))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        fun button(icon: Int, label: String, click: (View) -> Unit) = ImageButton(this).apply {
            setImageResource(icon)
            contentDescription = label
            tooltipText = label
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener(click)
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        }
        bar.addView(button(R.drawable.ic_menu, "Menu") { showMenu(it) })
        title = TextView(this).apply {
            text = "Loading GridCraft…"
            textSize = 17f
            setTextColor(getColor(R.color.bar_text))
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(dp(6), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(title)
        bar.addView(button(R.drawable.ic_open, "Open") { askToOpen() })
        bar.addView(button(R.drawable.ic_undo, "Undo") { run("edit.undo") })
        bar.addView(button(R.drawable.ic_redo, "Redo") { run("edit.redo") })
        bar.addView(button(R.drawable.ic_save, "Save") { save() })
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))

        web = WebView(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        root.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Edge to edge: keep the bar below the status bar and the sheet above the keyboard.
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val sys = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(sys.left, sys.top, sys.right, maxOf(sys.bottom, ime.bottom))
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            WindowInsets.CONSUMED
        }
        setContentView(root)
    }

    private fun android.view.Window.setDecorFitsSystemWindowsCompat() {
        if (Build.VERSION.SDK_INT >= 30) setDecorFitsSystemWindows(false)
    }

    private fun showTitle() {
        title.text = docName
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun showError(heading: String, msg: String) {
        AlertDialog.Builder(this).setTitle(heading).setMessage(msg).setPositiveButton("OK", null).show()
    }

    // ---------------------------------------------------------------- menu

    private sealed class Item {
        class Cmd(val label: String, val id: String, val params: String = "{}") : Item()
        class Act(val label: String, val action: () -> Unit) : Item()
        class Group(val label: String, val items: List<Item>) : Item()
    }

    private fun color(label: String, id: String, value: String) = Item.Cmd(label, id, """{"color":"$value"}""")
    private fun numberFormat(label: String, format: String) = Item.Cmd(label, "home.numberFormat", """{"format":"$format"}""")

    private val menuItems: List<Item> by lazy {
        listOf(
            Item.Group("Edit", listOf(
                Item.Act("Cut") { copy(cut = true) },
                Item.Act("Copy") { copy(cut = false) },
                Item.Act("Paste") { paste() },
                Item.Cmd("Delete", "edit.clearContents"),
            )),
            Item.Group("Text", listOf(
                Item.Cmd("Bold", "home.bold"),
                Item.Cmd("Italic", "home.italic"),
            )),
            Item.Group("Fill Color", listOf(
                color("Yellow", "home.fillColor", "#FFFF00"),
                color("Green", "home.fillColor", "#C6EFCE"),
                color("Blue", "home.fillColor", "#BDD7EE"),
                color("Red", "home.fillColor", "#FFC7CE"),
                color("No Fill", "home.fillColor", "none"),
            )),
            Item.Group("Font Color", listOf(
                color("Automatic", "home.fontColor", "auto"),
                color("Red", "home.fontColor", "#C00000"),
                color("Blue", "home.fontColor", "#0070C0"),
                color("Green", "home.fontColor", "#00B050"),
            )),
            Item.Group("Numbers", listOf(
                numberFormat("General", "General"),
                numberFormat("Number", "Number"),
                numberFormat("Currency", "Currency"),
                numberFormat("Percent", "Percentage"),
                numberFormat("Date", "Short Date"),
            )),
            Item.Group("Rows & Columns", listOf(
                Item.Cmd("Insert Row", "home.insertRows"),
                Item.Cmd("Delete Row", "home.deleteRows"),
                Item.Cmd("Insert Column", "home.insertColumns"),
                Item.Cmd("Delete Column", "home.deleteColumns"),
                Item.Cmd("AutoFit Column Width", "home.autofitColumnWidth"),
            )),
            Item.Cmd("Find…", "ui.dialog", """{"name":"find"}"""),
            Item.Cmd("Format Cells…", "home.formatCells"),
            Item.Act("Open…") { askToOpen() },
            Item.Act("Save a Copy…") { save(asCopy = true) },
        )
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val actions = HashMap<Int, Item>()
        var nextItem = 1
        for (item in menuItems) {
            when (item) {
                is Item.Group -> {
                    val sub = popup.menu.addSubMenu(item.label)
                    for (child in item.items) {
                        val id = nextItem++
                        actions[id] = child
                        sub.add(0, id, id, label(child))
                    }
                }
                else -> {
                    val id = nextItem++
                    actions[id] = item
                    popup.menu.add(0, id, id, label(item))
                }
            }
        }
        popup.setOnMenuItemClickListener { mi ->
            when (val item = actions[mi.itemId]) {
                is Item.Cmd -> { run(item.id, item.params); true }
                is Item.Act -> { item.action(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun label(item: Item) = when (item) {
        is Item.Cmd -> item.label
        is Item.Act -> item.label
        is Item.Group -> item.label
    }

    private fun copy(cut: Boolean) {
        run(if (cut) "edit.cut" else "edit.copy") { r ->
            val text = r.optJSONObject("value")?.optString("text") ?: return@run
            lastCopied = text
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("GridCraft", text))
        }
    }

    /** Paste: GridCraft's own copy keeps formatting; text copied in another app comes in as text. */
    private fun paste() {
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (clip.isNullOrEmpty() || clip == lastCopied) run("edit.paste")
        else run("edit.paste", JSONObject().put("text", clip).toString())
    }

    // ---------------------------------------------------------------- the page

    private fun setUpWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true // GridCraft keeps its preferences in localStorage
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
        }
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = serve(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host != HOST) {
                    // Hyperlinks in a sheet open in the browser.
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                }
                return true
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.e(TAG, "WebView renderer gone (crash=${detail.didCrash()})")
                Toast.makeText(this@MainActivity, "GridCraft stopped; reopening", Toast.LENGTH_LONG).show()
                recreate()
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.d(TAG, "page: ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                return true
            }
        }
        web.loadUrl("https://$HOST/gridcraft/host.html?chrome=none")
    }

    /** Serves GridCraft's files from assets/gridcraft/ with the right types; nothing else. */
    private fun serve(url: Uri): WebResourceResponse {
        val path = url.path?.removePrefix("/").orEmpty()
        if (url.host != HOST || !path.startsWith("gridcraft/") || path.contains("..")) return notFound()
        return try {
            val type = when (path.substringAfterLast('.', "")) {
                "html" -> "text/html"
                "js", "mjs" -> "text/javascript"
                "wasm" -> "application/wasm"
                "css" -> "text/css"
                "ico" -> "image/x-icon"
                "png" -> "image/png"
                "json" -> "application/json"
                else -> "application/octet-stream"
            }
            val text = type.startsWith("text/") || type == "application/json"
            WebResourceResponse(type, if (text) "utf-8" else null, assets.open(path)).apply {
                responseHeaders = mapOf("Cache-Control" to "no-cache")
            }
        } catch (e: IOException) {
            notFound()
        }
    }

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun js(code: String) = web.evaluateJavascript(code, null)

    private fun register(done: (JSONObject) -> Unit): Int {
        val id = nextId++
        waiting[id] = done
        return id
    }

    /** Runs a GridCraft command; errors show as a short message. */
    private fun run(command: String, params: String = "{}", done: ((JSONObject) -> Unit)? = null) {
        if (!ready) return
        val id = register { r ->
            if (r.optBoolean("ok")) done?.invoke(r) else toast(r.optString("error", "That didn't work"))
        }
        js("GC.run($id, ${JSONObject.quote(command)}, ${JSONObject.quote(params)})")
    }

    inner class Bridge {
        @JavascriptInterface fun onReady() = main.post {
            ready = true
            showTitle()
            queuedIntent?.let { queuedIntent = null; openIntent(it) }
        }

        @JavascriptInterface fun onFailed(msg: String) = main.post {
            Log.e(TAG, "GridCraft failed: $msg")
            showError("GridCraft couldn't start", msg)
        }

        @JavascriptInterface fun log(msg: String) {
            Log.i(TAG, "page: $msg")
        }

        @JavascriptInterface fun pendingName(): String = pendingName.orEmpty()

        @JavascriptInterface fun pendingData(): String {
            val d = pendingData.orEmpty()
            pendingData = null
            return d
        }

        @JavascriptInterface fun onResult(id: Int, json: String) {
            val r = runCatching { JSONObject(json) }.getOrElse { JSONObject().put("ok", false).put("error", json) }
            main.post { waiting.remove(id)?.invoke(r) }
        }

        @JavascriptInterface fun onSaveBytes(id: Int, name: String, b64: String) {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            main.post {
                saveBytes = bytes
                waiting.remove(id)?.invoke(JSONObject().put("ok", true).put("name", name))
            }
        }
    }

    // ---------------------------------------------------------------- back / leaving

    private fun onBack() {
        if (!ready) { finish(); return }
        // Close an open GridCraft dialog or the open ribbon first, as back does elsewhere.
        val id = register { r ->
            if (r.optBoolean("ok") && r.optBoolean("value")) return@register
            confirmDiscard { finish() }
        }
        js("GC.back($id)")
    }

    /** Asks about unsaved changes, then runs [proceed]. */
    private fun confirmDiscard(proceed: () -> Unit) {
        if (!ready) { proceed(); return }
        val id = register { r ->
            if (!r.optBoolean("ok") || !r.optBoolean("value")) { proceed(); return@register }
            AlertDialog.Builder(this)
                .setTitle("Save changes to ${docName}?")
                .setPositiveButton("Save") { _, _ -> save(after = proceed) }
                .setNegativeButton("Don't Save") { _, _ -> proceed() }
                .setNeutralButton("Cancel", null)
                .show()
        }
        js("GC.isDirty($id)")
    }

    // ---------------------------------------------------------------- opening

    private fun askToOpen() = confirmDiscard {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, OPEN_TYPES)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(pick, REQ_OPEN)
    }

    private fun openIntent(intent: Intent) {
        val uri: Uri = when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        } ?: return
        confirmDiscard { load(uri) }
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Workbook.xlsx"
    }

    private fun load(uri: Uri) {
        title.text = "Opening…"
        io.execute {
            val result = runCatching {
                val name = displayName(uri)
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("Can't read that file")
                if (bytes.size > MAX_OPEN_BYTES) throw IOException("That file is too large to open here (${bytes.size / 1_048_576} MB)")
                name to Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
            main.post {
                result.onFailure { e ->
                    showTitle()
                    showError("Couldn't open the file", e.message ?: e.toString())
                }.onSuccess { (name, b64) ->
                    pendingName = name
                    pendingData = b64
                    val id = register { r ->
                        if (r.optBoolean("ok")) {
                            docUri = uri
                            docName = name
                            showTitle()
                        } else {
                            showTitle()
                            showError("Couldn't open $name", r.optString("error"))
                        }
                    }
                    js("GC.openPending($id)")
                }
            }
        }
    }

    // ---------------------------------------------------------------- saving

    private fun stem(name: String) = name.substringBeforeLast('.', name)

    /**
     * Save: back to the same file when it is an .xlsx we may write; otherwise (another format, a
     * new workbook, a read-only source, or [asCopy]) Android asks where to put an .xlsx file.
     */
    private fun save(asCopy: Boolean = false, after: (() -> Unit)? = null) {
        if (!ready) return
        title.text = "Saving…"
        val id = register { r ->
            val bytes = saveBytes
            saveBytes = null
            if (!r.optBoolean("ok") || bytes == null) {
                showTitle()
                showError("Couldn't save", r.optString("error", "GridCraft didn't hand over the file"))
                return@register
            }
            val target = docUri
            if (!asCopy && target != null && docName.endsWith(".xlsx", ignoreCase = true)) {
                write(target, bytes) { ok ->
                    if (ok) savedTo(target, docName, after)
                    else {
                        toast("This file can't be changed in place; choose where to save it")
                        askWhereToSave(bytes, after)
                    }
                }
            } else {
                if (!asCopy && target != null) toast("Saving as a new .xlsx file")
                askWhereToSave(bytes, after)
            }
        }
        js("GC.save($id)")
    }

    private fun write(uri: Uri, bytes: ByteArray, done: (Boolean) -> Unit) {
        io.execute {
            val ok = runCatching {
                val out = runCatching { contentResolver.openOutputStream(uri, "wt") }.getOrNull()
                    ?: contentResolver.openOutputStream(uri, "w")
                    ?: throw IOException("no stream")
                out.use { it.write(bytes) }
            }.onFailure { Log.w(TAG, "write to $uri failed", it) }.isSuccess
            main.post { done(ok) }
        }
    }

    private fun askWhereToSave(bytes: ByteArray, after: (() -> Unit)?) {
        saveBytes = bytes
        afterSave = after
        val create = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = XLSX
            putExtra(Intent.EXTRA_TITLE, stem(docName) + ".xlsx")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(create, REQ_CREATE)
    }

    private fun savedTo(uri: Uri, name: String, after: (() -> Unit)?) {
        docUri = uri
        docName = name
        showTitle()
        toast("Saved")
        val id = register { after?.invoke() }
        js("GC.markSaved($id)")
    }

    @Deprecated("Framework Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        when (requestCode) {
            REQ_OPEN -> if (resultCode == RESULT_OK && uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                load(uri)
            }
            REQ_CREATE -> {
                val bytes = saveBytes
                val after = afterSave
                saveBytes = null
                afterSave = null
                if (resultCode != RESULT_OK || uri == null || bytes == null) { showTitle(); return }
                write(uri, bytes) { ok ->
                    if (ok) savedTo(uri, displayName(uri), after)
                    else { showTitle(); showError("Couldn't save", "Android didn't let the app write there.") }
                }
            }
        }
    }

    companion object {
        private const val TAG = "GridCraftPhone"
        private const val HOST = "appassets.androidplatform.net"
        private const val REQ_OPEN = 1
        private const val REQ_CREATE = 2
        private const val MAX_OPEN_BYTES = 200L * 1024 * 1024
        private const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        private val OPEN_TYPES = arrayOf(
            XLSX,
            "application/vnd.ms-excel",
            "application/vnd.ms-excel.sheet.macroEnabled.12",
            "application/vnd.ms-excel.sheet.binary.macroEnabled.12",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
            "application/vnd.oasis.opendocument.spreadsheet",
            "text/csv",
            "text/comma-separated-values",
            "text/tab-separated-values",
        )
    }
}
