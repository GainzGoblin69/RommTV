package com.swordfish.lemuroid.app.tv.romm

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * The game guide, shown beside the game menu: the guide fills the left of the screen (where the paused game is)
 * and its buttons are in the menu on the right, so going back to the game is one press.
 *
 * Finds the game's top walkthrough on GameFAQs by itself (matching the game's name and console), shows it in
 * a dark, easy-to-read layout, and remembers it and your place in it. "Jump to where you are" uses what
 * RetroAchievements says about where you are: a spot you read at last time you were there, else the best
 * matching section of the guide, else the name anywhere, else its rarest word.
 */
@SuppressLint("SetJavaScriptEnabled")
class GuidePanel(
    private val activity: Activity,
    private val gameTitle: String,
    private val gameKey: String,
    private val systemId: String,
) {
    /** Called with a short line about what the guide is doing ("Section: 2. KREMWOOD FOREST"). */
    var onStatus: (String) -> Unit = {}
        set(value) {
            field = value
            value(status)
        }

    var status: String = ""
        private set(value) {
            field = value
            onStatus(value)
        }

    /** Place names from RetroAchievements' description of where you are, best first. */
    val suggestions: List<String> =
        locationsIn(runCatching { com.swordfish.libretrodroid.RetroAchievements.richPresence() }.getOrDefault(""))

    // Mostly dark so the guide reads easily, with the paused game faintly visible behind it
    val frame = FrameLayout(activity).apply { setBackgroundColor(SHADE); visibility = View.GONE }
    val web: WebView =
        WebView(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.textZoom = 110
            isFocusable = true
            isFocusableInTouchMode = true
        }

    private var pendingScroll: Pair<String, Int>? = null
    private var autoPick = false

    /** The open guide is one you picked yourself, so it's kept even if its title looks different. */
    private var chosen = false

    /** If the guide's own title doesn't name this game, it's someone else's guide: find the right one. */
    private fun checkIsThisGame(view: WebView) {
        view.evaluateJavascript("document.title") { raw ->
            val pageTitle = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
            if (pageTitle.isBlank()) return@evaluateJavascript
            val want = words(gameTitle).filter { it !in STOP }
            if (want.isEmpty()) return@evaluateJavascript
            val have = words(pageTitle).toSet()
            if (want.count { it in have }.toDouble() / want.size >= 0.5) return@evaluateJavascript
            Guides.forget(activity, gameKey)
            status = "That guide was for another game. Finding the right one…"
            autoPick = true
            openSearch()
        }
    }

    /** True after "Choose another guide": the guide you open next is kept as yours. */
    private var userPicking = false
    private var jumped = false
    private var loaded = false
    val isOpen get() = frame.visibility == View.VISIBLE

    init {
        frame.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        web.webViewClient =
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
                    if (autoPick) autoStep(view, url)
                    if (isGuide(url)) {
                        Guides.remember(activity, gameKey, url)
                        if (userPicking) {
                            Guides.choose(activity, gameKey)
                            chosen = true
                        }
                        if (!chosen) checkIsThisGame(view)
                    }
                    // After the reader layout settles, go back to where you were
                    pendingScroll?.takeIf { it.first == url }?.let { (_, y) -> view.postDelayed({ view.scrollTo(0, y) }, 600) }
                    pendingScroll = null
                }
            }
        web.setFindListener { active, count, done ->
            if (done && count > 0) status = "Match ${active + 1} of $count"
        }
    }

    fun show() {
        frame.visibility = View.VISIBLE
        if (loaded) return
        loaded = true
        val saved = Guides.saved(activity, gameKey)?.takeIf { it.chosen || titleMatch(it.url) >= MIN_MATCH }
        if (saved != null) {
            chosen = saved.chosen
            pendingScroll = saved.url to saved.scroll
            web.loadUrl(saved.url)
            status = if (suggestions.isEmpty()) "Back where you left off" else "Back where you left off · Y jumps to where you are"
        } else {
            // First time (or the saved guide was for another game): find the top walkthrough by itself
            Guides.forget(activity, gameKey)
            autoPick = true
            openSearch()
            status = "Finding the best guide for $gameTitle…"
        }
    }

    fun hide() {
        rememberPlace()
        frame.visibility = View.GONE
    }

    fun rememberPlace() {
        val url = web.url?.takeIf { isGuide(it) } ?: return
        Guides.remember(activity, gameKey, url, web.scrollY)
        val key = locationKey
        if (jumped && key != null) Guides.learn(activity, gameKey, guideId(url), key, url, web.scrollY)
    }

    fun pageUp() = web.pageUp(false)

    fun pageDown() = web.pageDown(false)

    fun scrollBy(dy: Int) = web.scrollBy(0, dy)

    fun nextMatch() = web.findNext(true)

    private fun openSearch() {
        web.loadUrl("https://gamefaqs.gamespot.com/search?game=" + URLEncoder.encode(gameTitle, "UTF-8"))
    }

    /** This game's list of guides to pick another (or the search, if the open guide isn't this game's). */
    fun otherGuides() {
        autoPick = false
        userPicking = true
        val url = web.url.orEmpty()
        val list = Regex("^(.*/faqs)/\\d+").find(url)?.groupValues?.get(1)
        if (list != null && titleMatch(url) >= MIN_MATCH) {
            status = "Pick a guide (D-pad left to reach it)"
            web.loadUrl(list)
        } else {
            status = "Pick $gameTitle, then a guide"
            openSearch()
        }
    }

    fun askFind() {
        val input = EditText(activity).apply { inputType = InputType.TYPE_CLASS_TEXT; hint = "Text to find" }
        AlertDialog.Builder(activity)
            .setTitle("Find in guide")
            .setView(input)
            .setPositiveButton("Find") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    web.setFindListener { active, count, done ->
                        if (done) status = if (count > 0) "Match ${active + 1} of $count" else "Not found: “$text”"
                    }
                    web.findAllAsync(text)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private val locationKey get() = suggestions.firstOrNull()?.lowercase()?.replace(Regex("\\s+"), " ")

    fun jumpToSuggestion() {
        if (suggestions.isEmpty()) {
            status = "RetroAchievements isn't saying where you are in this game"
            return
        }
        val url = web.url
        if (url == null || !isGuide(url)) {
            status = "Open a guide first, then jump to your spot"
            return
        }
        val learned = locationKey?.let { Guides.spot(activity, gameKey, guideId(url), it) }
        if (learned != null) {
            status = "Your spot for “${suggestions.first()}”"
            if (learned.url == url) {
                web.scrollTo(0, learned.scroll)
            } else {
                pendingScroll = learned.url to learned.scroll
                web.loadUrl(learned.url)
            }
            jumped = true
            return
        }
        status = "Looking for “${suggestions.first()}”…"
        val candidates = org.json.JSONArray(suggestions).toString()
        web.evaluateJavascript("($LOCATE_JS)($candidates)") { raw ->
            val result = runCatching { JSONObject(org.json.JSONTokener(raw).nextValue() as String) }.getOrNull()
            val label = result?.optString("label").orEmpty()
            status =
                when (result?.optString("how")) {
                    "section" -> "Section: $label"
                    "phrase", "word" -> "Mentions “$label”"
                    else -> "This page doesn't mention “${suggestions.first()}”. Scroll to your spot and it'll be remembered."
                }
            jumped = true
        }
    }

    private fun autoStep(
        view: WebView,
        url: String,
    ) {
        when {
            url.contains("/search") ->
                view.evaluateJavascript("($PICK_GAME_JS)(${JSONObject.quote(gameTitle)}, ${JSONObject.quote(platformSlug(systemId))})") { href ->
                    follow(view, href, "Couldn't find $gameTitle on GameFAQs. Pick it (D-pad left).")
                }
            Regex("/faqs/?(\\?.*)?$").containsMatchIn(url) ->
                view.evaluateJavascript("($PICK_GUIDE_JS)()") { href ->
                    follow(view, href, "Pick a guide (D-pad left)")
                    autoPick = false
                }
            isGuide(url) -> {
                autoPick = false
                status = "Opened the top walkthrough · \"Choose another guide\" to switch"
            }
        }
    }

    private fun follow(
        view: WebView,
        raw: String?,
        failure: String,
    ) {
        val href = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
        if (href.isBlank()) {
            autoPick = false
            status = failure
        } else {
            view.loadUrl(href)
        }
    }

    /** How well a GameFAQs address's game name ("…/586135-sonic-the-hedgehog-2/…") matches this game. */
    private fun titleMatch(url: String) = slugScore(url, gameTitle, platformSlug(systemId))

    companion object {
        private val SHADE = Color.argb(214, 13, 17, 23)
        private const val MIN_MATCH = 3.0

        private val STOP = setOf("the", "of", "a", "an", "and", "in", "to", "on")

        private fun words(text: String) =
            text.lowercase().replace("&", " and ").replace(Regex("[^a-z0-9]+"), " ").trim().split(" ").filter { it.isNotBlank() }

        /** Matching words count up, extra words and a different console count down. */
        fun slugScore(
            url: String,
            title: String,
            platform: String,
        ): Double {
            val m = Regex("gamefaqs\\.gamespot\\.com/([a-z0-9]+)/\\d+-([a-z0-9-]+)").find(url) ?: return 0.0
            val slugWords = m.groupValues[2].split('-').filter { it.isNotBlank() }
            val want = words(title).filter { it !in STOP }
            val common = want.count { it in slugWords }
            if (common == 0) return 0.0
            var score = 3.0 * common - (slugWords.size - common) - (want.size - common)
            if (platform.isNotBlank() && m.groupValues[1] == platform) score += 3
            return score
        }

        private fun isGuide(url: String) = Regex("gamefaqs\\.gamespot\\.com/.+/faqs/\\d+").containsMatchIn(url)

        /** Guides with several pages share one id, so a learned spot works on any of its pages. */
        private fun guideId(url: String) = Regex("/faqs/(\\d+)").find(url)?.groupValues?.get(1) ?: url

        /** The first part of GameFAQs' addresses for each console ("/snes/…"). */
        fun platformSlug(systemId: String) =
            when (systemId) {
                "nes" -> "nes"
                "snes" -> "snes"
                "gb" -> "gameboy"
                "gbc" -> "gbc"
                "gba" -> "gba"
                "n64" -> "n64"
                "nds" -> "ds"
                "md", "genesis" -> "genesis"
                "scd", "segacd" -> "segacd"
                "sms" -> "sms"
                "gg" -> "gamegear"
                "psx" -> "ps"
                "psp" -> "psp"
                "pce" -> "tg16"
                "lynx" -> "lynx"
                "atari2600" -> "2600"
                "atari7800" -> "7800"
                "ngp" -> "ngpocket"
                "ngc" -> "ngpc"
                "ws" -> "wonderswan"
                "wsc" -> "wsc"
                "fbneo", "mame2003plus" -> "arcade"
                else -> ""
            }



        private val GENERIC = setOf("zone", "stage", "level", "world", "area", "act", "round", "chapter", "mission", "floor", "dungeon")

        /**
         * Place names in RetroAchievements' description of where you are, best first. For
         * "9º stage of Kremwood Forest, 🐵:0" that's "Kremwood Forest"; for "Kulche is rollin' around in
         * Stage 1-1 [😀-9]" it's "Stage 1-1"; "Mainbrace Mayhem within Gangplank Galleon. 9 17" gives
         * "Mainbrace Mayhem" then "Gangplank Galleon".
         */
        fun locationsIn(presence: String): List<String> {
            if (presence.isBlank()) return emptyList()
            val found = LinkedHashSet<String>()

            fun add(raw: String) {
                val parts = raw.split(Regex("\\.\\s|\\s(?:within|inside|in|of|at|on|near)\\s"))
                if (parts.size > 1) parts.forEach { add(it) }
                val cleaned =
                    raw.substringBefore(". ").replace(Regex("[^\\p{L}\\p{N}'’ .&-]"), " ")
                        .replace(Regex("\\s+"), " ")
                        .trim(' ', '.', '-', '\'')
                        .removePrefix("the ").removePrefix("The ")
                if (cleaned.length >= 4 && cleaned.any { it.isLetter() } && cleaned.lowercase() !in GENERIC) found += cleaned
            }
            val head = presence.split('[', '|', '•', '·').first()
            Regex("(?i)\\b(?:stage|level|world|area|zone|chapter|act|round|mission|floor|dungeon)\\s*[0-9]+(?:[-.][0-9]+)?").findAll(head).forEach { add(it.value) }
            Regex("\\b(?:in|at|of|on|inside|exploring|visiting|near|entering|through)\\s+([^,:;\\[\\(|]+)").findAll(head).forEach { add(it.groupValues[1]) }
            Regex("\\b([A-Z][\\p{L}'’]+(?:\\s+(?:of|the|de|du|[A-Z][\\p{L}'’]+))*\\s+[A-Z][\\p{L}'’]+)").findAll(head).forEach { add(it.groupValues[1]) }
            Regex("\\b([A-Z][\\p{L}'’]{4,})").findAll(head).forEach { add(it.groupValues[1]) }
            return found.toList()
        }

        /** On GameFAQs' search results: the guide list of the result whose address best matches the game. */
        private const val PICK_GAME_JS = """
function(title, slug){
  function words(t){ return (t||'').toLowerCase().replace(/&/g,' and ').replace(/[^a-z0-9]+/g,' ').trim().split(' ').filter(function(w){ return w; }); }
  var STOP = {the:1, of:1, a:1, an:1, and:1, 'in':1, to:1, on:1};
  var want = words(title).filter(function(w){ return !STOP[w]; });
  var best = null, bestScore = 2.5, seen = {};
  Array.prototype.slice.call(document.querySelectorAll('a[href]')).forEach(function(a){
    var m = (a.getAttribute('href') || '').match(/^(?:https?:\/\/gamefaqs\.gamespot\.com)?\/([a-z0-9]+)\/([0-9]+)-([a-z0-9-]+)/);
    if (!m) return;
    var base = '/' + m[1] + '/' + m[2] + '-' + m[3];
    if (seen[base]) return; seen[base] = 1;
    var sw = m[3].split('-').filter(function(w){ return w; });
    var common = want.filter(function(w){ return sw.indexOf(w) >= 0; }).length;
    if (!common) return;
    var score = 3 * common - (sw.length - common) - (want.length - common);
    if (slug && m[1] === slug) score += 3;
    if (score > bestScore) { bestScore = score; best = base; }
  });
  return best ? 'https://gamefaqs.gamespot.com' + best + '/faqs' : '';
}
"""

        /** On a game's guide list: its top full walkthrough (only this game's guides count). */
        private const val PICK_GUIDE_JS = """
function(){
  var here = location.pathname.replace(/\/faqs\/?$/, '');
  var links = Array.prototype.slice.call(document.querySelectorAll('a[href]')).filter(function(a){
    var h = a.getAttribute('href') || '';
    return h.indexOf(here + '/faqs/') >= 0 && /\/faqs\/[0-9]+/.test(h);
  });
  if (!links.length) return '';
  var walk = links.filter(function(a){ return /walkthrough/i.test(a.textContent || ''); });
  var faq = links.filter(function(a){ return /faq|guide/i.test(a.textContent || ''); });
  return (walk[0] || faq[0] || links[0]).href;
}
"""

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

        private const val READER_JS = """
(function(){
  var css = 'html,body{background:transparent!important;color:#e6edf3!important}' +
    'a{color:#8fdc4e!important}' +
    'pre,#faqtext,#faqwrap,.ffaq{color:#e6edf3!important;background:transparent!important;white-space:pre-wrap!important;font-size:15px!important;line-height:1.45!important}' +
    'img{max-width:100%!important;height:auto!important}' +
    'div,section,main,article,header,nav,table,td,th,tr,ul,li,p,span{background-color:transparent!important;background-image:none!important}' +
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
    parent.style.background = 'transparent'; parent.style.margin = '0'; parent.style.padding = '8px';
    node = parent;
  }
})();
"""
    }
}

/** The guide picked for each game, where you were in it, and spots you read at for places in the game. */
object Guides {
        data class Saved(val url: String, val scroll: Int, val chosen: Boolean = false)

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
                Saved(o.getString("url"), o.optInt("scroll"), o.optBoolean("chosen"))
            }.getOrNull()

        /** Forgets the guide (and its spots) for a game, e.g. when it turns out to be for another game. */
        fun forget(
            context: Context,
            key: String,
        ) {
            file(context, key).delete()
        }

        /** Marks the guide as one you picked yourself, so it's kept even if its name doesn't look like the game's. */
        fun choose(
            context: Context,
            key: String,
        ) {
            val o = read(context, key)
            o.put("chosen", true)
            runCatching { file(context, key).writeText(o.toString()) }
        }

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
            val sameGuide = Regex("/faqs/(\\d+)").find(o.optString("url"))?.value == Regex("/faqs/(\\d+)").find(url)?.value
            val y = scroll ?: if (o.optString("url") == url) o.optInt("scroll") else 0
            if (!sameGuide) o.remove("chosen")
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

