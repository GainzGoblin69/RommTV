package com.swordfish.lemuroid.app.tv.romm

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * A game guide while you play (from "Guide" at the top of the game menu). Finds the game's guides on GameFAQs,
 * shows the one you pick in a dark, easy-to-read layout, and remembers it (and where you were in it) for next
 * time. If RetroAchievements is following the game, "Suggested spot" jumps to the part of the guide matching
 * where RetroAchievements says you are (a level or area name), or you can just browse.
 *
 * Controller: D-pad moves and scrolls, L/R page up and down, Y suggested spot, X next match, B back.
 */
class GuideActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var suggestButton: Button
    private lateinit var nextButton: Button
    private lateinit var status: TextView
    private lateinit var gameKey: String
    private lateinit var gameTitle: String
    private var suggestions: List<String> = emptyList()
    private var pendingScroll: Pair<String, Int>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gameTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        gameKey = intent.getStringExtra(EXTRA_KEY).orEmpty().ifBlank { gameTitle }
        // Where RetroAchievements says you are right now (the game is paused under this screen)
        val presence = runCatching { com.swordfish.libretrodroid.RetroAchievements.richPresence() }.getOrDefault("")
        suggestions = locationsIn(presence)

        val dp = resources.displayMetrics.density
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(BG)
            }
        val bar =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            }
        bar.addView(button("✕ Close") { finish() })
        suggestButton =
            button(if (suggestions.isEmpty()) "Suggested spot" else "Y  Suggested: ${suggestions.first()}") { jumpToSuggestion() }
        bar.addView(suggestButton)
        nextButton = button("X  Next match") { web.findNext(true) }.apply { visibility = View.GONE }
        bar.addView(nextButton)
        bar.addView(button("Find text…") { askFind() })
        bar.addView(button("Other guides") { openSearch() })
        status =
            TextView(this).apply {
                setTextColor(QUIET)
                textSize = 13f
                setPadding((12 * dp).toInt(), 0, 0, 0)
            }
        bar.addView(status)
        root.addView(HorizontalScrollView(this).apply { addView(bar) })

        web =
            WebView(this).apply {
                setBackgroundColor(BG)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.textZoom = 115
                isFocusable = true
                isFocusableInTouchMode = true
                webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ) = false

                        override fun onPageFinished(
                            view: WebView,
                            url: String,
                        ) {
                            view.evaluateJavascript(READER_JS, null)
                            if (isGuide(url)) Guides.remember(this@GuideActivity, gameKey, url)
                            pendingScroll?.takeIf { it.first == url }?.let { (_, y) -> view.postDelayed({ view.scrollTo(0, y) }, 400) }
                            pendingScroll = null
                        }
                    }
                setFindListener { active, count, done ->
                    if (done) {
                        nextButton.visibility = if (count > 1) View.VISIBLE else View.GONE
                        status.text = if (count > 0) "Match ${active + 1} of $count" else status.text
                    }
                }
            }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val saved = Guides.saved(this, gameKey)
        if (saved != null) {
            pendingScroll = saved.url to saved.scroll
            web.loadUrl(saved.url)
            status.text = if (suggestions.isEmpty()) "Your guide for this game" else "Press Y to jump to where you are"
        } else {
            openSearch()
        }
        web.requestFocus()
    }

    override fun onPause() {
        super.onPause()
        web.url?.takeIf { isGuide(it) }?.let { Guides.remember(this, gameKey, it, web.scrollY) }
    }

    private fun openSearch() {
        status.text = "Pick a guide for $gameTitle"
        web.loadUrl("https://gamefaqs.gamespot.com/search?game=" + URLEncoder.encode(gameTitle, "UTF-8"))
    }

    private fun isGuide(url: String) = Regex("gamefaqs\\.gamespot\\.com/.+/faqs/\\d+").containsMatchIn(url)

    /** Tries the place names from RetroAchievements one by one until the guide has one of them. */
    private fun jumpToSuggestion() {
        if (suggestions.isEmpty()) {
            status.text = "RetroAchievements isn't saying where you are in this game"
            return
        }
        if (web.url?.let { isGuide(it) } != true) {
            status.text = "Open a guide first, then jump to your spot"
            return
        }
        tryFind(0)
    }

    private fun tryFind(index: Int) {
        val phrase = suggestions.getOrNull(index)
        if (phrase == null) {
            status.text = "This guide doesn't mention “${suggestions.first()}”"
            return
        }
        web.setFindListener { active, count, done ->
            if (!done) return@setFindListener
            if (count == 0) {
                tryFind(index + 1)
            } else {
                status.text = "“$phrase”: match ${active + 1} of $count"
                nextButton.visibility = if (count > 1) View.VISIBLE else View.GONE
            }
        }
        web.findAllAsync(phrase)
    }

    private fun askFind() {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT; hint = "Text to find" }
        AlertDialog.Builder(this)
            .setTitle("Find in guide")
            .setView(input)
            .setPositiveButton("Find") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    web.setFindListener { active, count, done ->
                        if (done) {
                            status.text = if (count > 0) "Match ${active + 1} of $count" else "Not found: “$text”"
                            nextButton.visibility = if (count > 1) View.VISIBLE else View.GONE
                        }
                    }
                    web.findAllAsync(text)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                if (!down) {
                    if (web.canGoBack()) web.goBack() else finish()
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_Y -> {
                if (down && event.repeatCount == 0) jumpToSuggestion()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_X -> {
                if (down) web.findNext(true)
                return true
            }
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                if (down) web.pageUp(false)
                return true
            }
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (down) web.pageDown(false)
                return true
            }
            KeyEvent.KEYCODE_BUTTON_A ->
                return super.dispatchKeyEvent(KeyEvent(event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER, event.repeatCount))
            KeyEvent.KEYCODE_DPAD_UP ->
                // At the top of the page, Up reaches the buttons
                if (down && web.hasFocus() && web.scrollY == 0) {
                    suggestButton.requestFocus()
                    return true
                }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun button(
        text: String,
        onClick: () -> Unit,
    ): Button {
        val dp = resources.displayMetrics.density
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 14f
            stateListAnimator = null
            val normal = GradientDrawable().apply { cornerRadius = 9 * dp; setColor(RAISED) }
            val focused = GradientDrawable().apply { cornerRadius = 9 * dp; setColor(RAISED); setStroke((2 * dp).toInt(), LIME) }
            background =
                android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), focused)
                    addState(intArrayOf(android.R.attr.state_pressed), focused)
                    addState(intArrayOf(), normal)
                }
            setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (40 * dp).toInt()).apply {
                    marginEnd = (8 * dp).toInt()
                }
            setOnClickListener { onClick() }
        }
    }

    /** The guide picked for each game and where you were in it. */
    object Guides {
        data class Saved(val url: String, val scroll: Int)

        private fun file(
            context: Context,
            key: String,
        ): File {
            val name = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
            return File(File(context.applicationContext.filesDir, "guides").apply { mkdirs() }, "$name.json")
        }

        fun saved(
            context: Context,
            key: String,
        ): Saved? =
            runCatching {
                val o = JSONObject(file(context, key).readText())
                Saved(o.getString("url"), o.optInt("scroll"))
            }.getOrNull()

        fun remember(
            context: Context,
            key: String,
            url: String,
            scroll: Int? = null,
        ) {
            val previous = saved(context, key)
            val y = scroll ?: if (previous?.url == url) previous.scroll else 0
            runCatching { file(context, key).writeText(JSONObject().put("url", url).put("scroll", y).toString()) }
        }
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_KEY = "key"

        private val BG = Color.rgb(13, 17, 23)
        private val RAISED = Color.rgb(30, 38, 51)
        private val LIME = Color.rgb(143, 220, 78)
        private val QUIET = Color.rgb(167, 178, 194)

        fun open(
            context: Context,
            title: String,
            key: String,
        ) {
            context.startActivity(Intent(context, GuideActivity::class.java).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_KEY, key))
        }

        /**
         * Place names in RetroAchievements' description of where you are, best first. For
         * "9º stage of Kremwood Forest, 🐵:0, DKⓒ: 0" that's "Kremwood Forest"; for
         * "Kulche is rollin' around in Stage 1-1 [😀-9]" it's "Stage 1-1".
         */
        fun locationsIn(presence: String): List<String> {
            if (presence.isBlank()) return emptyList()
            val found = LinkedHashSet<String>()

            fun add(raw: String) {
                val cleaned =
                    raw.replace(Regex("[^\\p{L}\\p{N}'’ .&-]"), " ")
                        .replace(Regex("\\s+"), " ")
                        .trim(' ', '.', '-', '\'')
                        .removePrefix("the ").removePrefix("The ")
                if (cleaned.length >= 4 && cleaned.any { it.isLetter() }) found += cleaned
            }
            // Before any [stats] or | separators
            val head = presence.split('[', '|', '•', '·').first()
            Regex("(?i)\\b(?:stage|level|world|area|zone|chapter|act|round|mission|floor|dungeon)\\s*[0-9]+(?:[-.][0-9]+)?").findAll(head).forEach { add(it.value) }
            Regex("\\b(?:in|at|of|on|inside|exploring|visiting|near|entering|through)\\s+([^,:;\\[\\(|]+)").findAll(head).forEach { add(it.groupValues[1]) }
            Regex("\\b([A-Z][\\p{L}'’]+(?:\\s+(?:of|the|de|du|[A-Z][\\p{L}'’]+))*\\s+[A-Z][\\p{L}'’]+)").findAll(head).forEach { add(it.groupValues[1]) }
            Regex("\\b([A-Z][\\p{L}'’]{4,})").findAll(head).forEach { add(it.groupValues[1]) }
            return found.toList()
        }

        /** Makes GameFAQs pages dark and keeps only the guide (and its page links) when one is showing. */
        private const val READER_JS = """
(function(){
  var css = 'html,body{background:#0d1117!important;color:#e6edf3!important}' +
    'a{color:#8fdc4e!important}' +
    'pre,#faqtext,#faqwrap,.ffaq{color:#e6edf3!important;background:#0d1117!important;white-space:pre-wrap!important;font-size:15px!important;line-height:1.45!important}' +
    'img{max-width:100%!important;height:auto!important}' +
    '[id*="ad_"],[class*="ad_"],[class*="adsby"],iframe{display:none!important}';
  var s = document.createElement('style'); s.textContent = css; document.head.appendChild(s);
  var guide = document.querySelector('#faqwrap') || document.querySelector('#faqtext');
  if (!guide) return;
  var node = guide;
  while (node && node.parentElement && node !== document.body) {
    var parent = node.parentElement;
    for (var i = 0; i < parent.children.length; i++) {
      var c = parent.children[i];
      if (c === node) continue;
      if (c.querySelector && (c.querySelector('a[href*="?page="]') || c.querySelector('.paginate') || c.className.indexOf('paginate') >= 0)) continue;
      if (c.tagName === 'SCRIPT' || c.tagName === 'STYLE') continue;
      c.style.display = 'none';
    }
    parent.style.background = '#0d1117'; parent.style.margin = '0'; parent.style.padding = '8px';
    node = parent;
  }
})();
"""
    }
}
