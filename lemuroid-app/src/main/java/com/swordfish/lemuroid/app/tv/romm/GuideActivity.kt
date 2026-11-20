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
                            // After the reader layout settles, go back to where you were
                            pendingScroll?.takeIf { it.first == url }?.let { (_, y) -> view.postDelayed({ view.scrollTo(0, y) }, 600) }
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
            status.text = if (suggestions.isEmpty()) "Back where you left off" else "Back where you left off · Y jumps to where you are"
        } else {
            openSearch()
        }
        web.requestFocus()
    }

    override fun onPause() {
        super.onPause()
        val url = web.url?.takeIf { isGuide(it) } ?: return
        // Next time the guide opens right here, unless you ask to jump somewhere else
        Guides.remember(this, gameKey, url, web.scrollY)
        // Where you ended up reading after a jump is the right spot for this place in the game: remember it
        val key = locationKey
        if (jumped && key != null) Guides.learn(this, gameKey, guideId(url), key, url, web.scrollY)
    }

    private fun openSearch() {
        status.text = "Pick a guide for $gameTitle"
        web.loadUrl("https://gamefaqs.gamespot.com/search?game=" + URLEncoder.encode(gameTitle, "UTF-8"))
    }

    private fun isGuide(url: String) = Regex("gamefaqs\\.gamespot\\.com/.+/faqs/\\d+").containsMatchIn(url)

    /** The place RetroAchievements says you're at, as one key for remembering spots ("kremwood forest"). */
    private val locationKey get() = suggestions.firstOrNull()?.lowercase()?.replace(Regex("\\s+"), " ")

    /**
     * Suggested spot: where you were reading the last time you were at this place in the game (learned), or
     * else the guide section that best matches where RetroAchievements says you are.
     */
    private fun jumpToSuggestion() {
        if (suggestions.isEmpty()) {
            status.text = "RetroAchievements isn't saying where you are in this game"
            return
        }
        val url = web.url
        if (url == null || !isGuide(url)) {
            status.text = "Open a guide first, then jump to your spot"
            return
        }
        val learned = locationKey?.let { Guides.spot(this, gameKey, guideId(url), it) }
        if (learned != null) {
            status.text = "Your spot for “${suggestions.first()}”"
            if (learned.url == url) {
                web.scrollTo(0, learned.scroll)
            } else {
                pendingScroll = learned.url to learned.scroll
                web.loadUrl(learned.url)
            }
            jumped = true
            return
        }
        status.text = "Looking for “${suggestions.first()}”…"
        val candidates = org.json.JSONArray(suggestions).toString()
        web.evaluateJavascript("($LOCATE_JS)($candidates)") { raw ->
            val result =
                runCatching {
                    JSONObject(org.json.JSONTokener(raw).nextValue() as String)
                }.getOrNull()
            val how = result?.optString("how")
            val label = result?.optString("label").orEmpty()
            status.text =
                when (how) {
                    "section" -> "Section: $label"
                    "phrase", "word" -> "Mentions “$label”"
                    else -> "This page doesn't mention “${suggestions.first()}”. Find your spot and it'll be remembered."
                }
            jumped = true
        }
    }

    /** Whether you used Suggested spot this time (then where you finish reading is remembered for this place). */
    private var jumped = false

    /** Guides with several pages share one id, so a learned spot works on any of its pages. */
    private fun guideId(url: String) = Regex("/faqs/(\\d+)").find(url)?.groupValues?.get(1) ?: url

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

        private fun read(
            context: Context,
            key: String,
        ) = runCatching { JSONObject(file(context, key).readText()) }.getOrDefault(JSONObject())

        fun remember(
            context: Context,
            key: String,
            url: String,
            scroll: Int? = null,
        ) {
            val o = read(context, key)
            val y = scroll ?: if (o.optString("url") == url) o.optInt("scroll") else 0
            o.put("url", url).put("scroll", y)
            runCatching { file(context, key).writeText(o.toString()) }
        }

        /** A spot you read at for one place in the game ("kremwood forest"), per guide. */
        fun spot(
            context: Context,
            key: String,
            guide: String,
            place: String,
        ): Saved? =
            read(context, key).optJSONObject("spots")?.optJSONObject("$guide|$place")?.let {
                Saved(it.getString("url"), it.optInt("scroll"))
            }

        fun learn(
            context: Context,
            key: String,
            guide: String,
            place: String,
            url: String,
            scroll: Int,
        ) {
            val o = read(context, key)
            val spots = o.optJSONObject("spots") ?: JSONObject()
            spots.put("$guide|$place", JSONObject().put("url", url).put("scroll", scroll))
            o.put("spots", spots)
            runCatching { file(context, key).writeText(o.toString()) }
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

        /** Finds where you are on the guide page: best-matching section, then the name anywhere, then its rarest word. */
        private const val LOCATE_JS = """
function(cands){
  function norm(t){
    return (t||'').toLowerCase()
      .replace(/[‐-―−]/g,'-')
      .replace(/(\d)\s*-\s*(\d)/g,'$1x$2')
      .replace(/[^a-z0-9À-ɏ ]+/g,' ')
      .replace(/\s+/g,' ').trim();
  }
  var STOP = {the:1,of:1,in:1,at:1,a:1,an:1,and:1,to:1,on:1,is:1,'for':1,with:1,from:1};
  function toks(t){ return norm(t).split(' ').filter(function(w){ return w && !STOP[w]; }); }
  var root = document.querySelector('#faqwrap') || document.querySelector('#faqtext') || document.body;
  var lines = [];
  var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
  var n, pos = 0;
  while ((n = walker.nextNode())) {
    var p = n.parentElement;
    if (!p || (p.offsetParent === null && p !== document.body)) continue;
    var text = n.nodeValue, start = 0;
    var parts = text.split('\n');
    for (var i = 0; i < parts.length; i++) {
      var raw = parts[i];
      if (raw.trim().length) lines.push({node:n, offset:start, text:raw.trim(), pos:pos + start, tag:p.tagName});
      start += raw.length + 1;
    }
    pos += text.length;
  }
  if (!lines.length) return JSON.stringify({how:'none'});
  var total = pos || 1;
  var docNorm = lines.map(function(l){ return norm(l.text); }).join(' | ');
  function count(w){ var c = 0, i = 0; while ((i = docNorm.indexOf(w, i)) >= 0 && c < 60) { c++; i += w.length; } return c; }
  function isRule(t){ return /^[=\-*_#~+]{4,}\s*$/.test(t); }
  var heads = [];
  for (var k = 0; k < lines.length; k++) {
    var l = lines[k], t = l.text;
    if (t.length < 3 || t.length > 90 || isRule(t)) continue;
    var letters = t.replace(/[^A-Za-z]/g,''), upper = t.replace(/[^A-Z]/g,'');
    var h = /^H[1-6]$/.test(l.tag) ||
      (letters.length >= 3 && upper.length / letters.length >= 0.7) ||
      (t.length <= 60 && k + 1 < lines.length && isRule(lines[k+1].text)) ||
      /^\s*(\[?[a-z]{0,3}\d+[\.\)\]:]|[ivx]+\.|(chapter|stage|level|world|area|zone|part|act|mission|walkthrough)\b)/i.test(t);
    if (h) heads.push({line:l, idx:k, toks:toks(t), n:norm(t)});
  }
  var weights = {};
  function weight(w){
    if (weights[w] === undefined) {
      var c = count(w);
      weights[w] = c === 0 ? 0 : (/^[0-9x]+$/.test(w) ? 0.6 : 1) * (1 / (1 + c / 8));
    }
    return weights[w];
  }
  var best = null;
  for (var hi = 0; hi < heads.length; hi++) {
    var hd = heads[hi], score = 0;
    for (var ci = 0; ci < cands.length; ci++) {
      var ct = toks(cands[ci]), cp = norm(cands[ci]), s = 0, hits = 0;
      for (var ti = 0; ti < ct.length; ti++) if (hd.toks.indexOf(ct[ti]) >= 0) { s += weight(ct[ti]); hits++; }
      if (ct.length && hits === ct.length) s += 1.5;
      if (cp.length >= 4 && hd.n.indexOf(cp) >= 0) s += 2;
      s *= (ci === 0 ? 1 : 0.8);
      if (s > score) score = s;
    }
    if (score <= 0) continue;
    // A line in a list of headings (the table of contents) loses a tie to the heading of the section itself
    hd.score = score;
    hd.inList = (hi > 0 && heads[hi - 1].idx === hd.idx - 1) || (hi + 1 < heads.length && heads[hi + 1].idx === hd.idx + 1);
    if (!best || score > best.score + 0.01 || (Math.abs(score - best.score) <= 0.01 && best.inList && !hd.inList)) best = hd;
  }
  function go(line, how, label){
    var r = document.createRange();
    r.setStart(line.node, Math.min(line.offset, line.node.length));
    r.setEnd(line.node, Math.min(line.offset + 1, line.node.length));
    var rect = r.getBoundingClientRect();
    window.scrollTo(0, Math.max(0, rect.top + window.scrollY - 70));
    var mark = document.createElement('div');
    mark.style.cssText = 'position:absolute;left:0;width:6px;height:' + Math.max(24, rect.height * 3) + 'px;background:#8fdc4e;z-index:99999;transition:opacity 1.5s';
    mark.style.top = (rect.top + window.scrollY - 4) + 'px';
    document.body.appendChild(mark);
    setTimeout(function(){ mark.style.opacity = '0'; }, 2500);
    setTimeout(function(){ mark.remove(); }, 4200);
    return JSON.stringify({how:how, label:label});
  }
  if (best && best.score >= 1.5) return go(best.line, 'section', best.line.text);
  function find(p){
    var hits = [];
    for (var k = 0; k < lines.length && hits.length < 3; k++) if (norm(lines[k].text).indexOf(p) >= 0) hits.push(lines[k]);
    if (!hits.length) return null;
    return (hits.length > 1 && hits[0].pos < total * 0.15) ? hits[1] : hits[0];
  }
  for (var c2 = 0; c2 < cands.length; c2++) {
    var p2 = norm(cands[c2]);
    if (p2.length < 4) continue;
    var f = find(p2);
    if (f) return go(f, 'phrase', cands[c2]);
  }
  var words = [];
  cands.forEach(function(c){ toks(c).forEach(function(w){ if (w.length >= 5 && /[a-z]/.test(w) && count(w) > 0) words.push(w); }); });
  words.sort(function(a, b){ return count(a) - count(b); });
  if (words.length) { var f2 = find(words[0]); if (f2) return go(f2, 'word', words[0]); }
  return JSON.stringify({how:'none'});
}
"""

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
