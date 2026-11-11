package com.swordfish.lemuroid.app.tv.romm

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.swordfish.lemuroid.lib.romm.RommSession
import kotlin.math.abs

/**
 * Online play. RomM's netplay lives in its web player, so this screen opens that player for the game,
 * full screen, and makes it usable from the couch:
 *  - a pointer (D-pad or left stick to move, A to click) for signing in and for the netplay menu,
 *  - the controller plays the game once the pointer is hidden,
 *  - Start + Select together (or Back on the remote) opens a small menu: netplay, pointer, reload, exit.
 * Everyone in a room runs the same web player, so TV players and browser players can share rooms.
 */
class NetplayActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var cursor: View
    private lateinit var hint: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var pointer = true
    private var cx = 0f
    private var cy = 0f
    private var stickX = 0f
    private var stickY = 0f
    private var scrollY = 0f
    private var moving = false
    private var selectDown = false
    private var startDown = false
    private var menuOpen = false
    private var gameSeen = false
    private var polls = 0

    private val density by lazy { resources.displayMetrics.density }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.swordfish.lemuroid.app.tv.romm.PhoneUi.apply(this)
        val romId = intent.getIntExtra(EXTRA_ROM_ID, -1)
        val server = RommSession(this).serverUrl

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        CookieManager.getInstance().setAcceptCookie(true)
        web.webViewClient =
            object : WebViewClient() {
                override fun onPageFinished(
                    view: WebView?,
                    url: String?,
                ) {
                    polls = 0
                    handler.removeCallbacks(autoStart)
                    handler.postDelayed(autoStart, 1500)
                }
            }
        web.webChromeClient =
            object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    // The player only ever asks on behalf of the RomM server itself
                    request.grant(request.resources)
                }
            }
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        cursor = View(this)
        cursor.background =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke((3 * density).toInt(), Color.rgb(16, 124, 16))
            }
        val size = (22 * density).toInt()
        root.addView(cursor, FrameLayout.LayoutParams(size, size))

        hint = TextView(this)
        hint.setTextColor(Color.WHITE)
        hint.textSize = 14f
        hint.setBackgroundColor(Color.argb(190, 13, 17, 23))
        val pad = (10 * density).toInt()
        hint.setPadding(pad * 2, pad, pad * 2, pad)
        hint.text = POINTER_HINT
        root.addView(
            hint,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = pad * 2 },
        )

        // On a phone you tap the page directly, so the pointer starts hidden (the menu can bring it back)
        if (!com.swordfish.lemuroid.lib.romm.Device.isTv(this)) {
            pointer = false
            cursor.visibility = View.GONE
            hint.visibility = View.GONE
        }

        setContentView(root)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        root.post {
            cx = root.width / 2f
            cy = root.height / 2f
            placeCursor()
        }
        web.requestFocus()
        web.loadUrl("$server/rom/$romId/ejs")
    }

    /** Presses the page's Play button once it appears, and notices when the game is running. */
    private val autoStart =
        object : Runnable {
            override fun run() {
                if (isFinishing || polls++ > 60) return
                web.evaluateJavascript(JS_AUTO_START) { result ->
                    if (result?.contains("running") == true) {
                        if (!gameSeen) {
                            gameSeen = true
                            Toast.makeText(
                                this@NetplayActivity,
                                "Game running. Hold Start + Select for the online menu.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    } else {
                        handler.postDelayed(this, 1500)
                    }
                }
            }
        }

    private fun placeCursor() {
        val w = web.width.toFloat().coerceAtLeast(1f)
        val h = web.height.toFloat().coerceAtLeast(1f)
        cx = cx.coerceIn(0f, w - 1)
        cy = cy.coerceIn(0f, h - 1)
        cursor.translationX = cx - cursor.width / 2f
        cursor.translationY = cy - cursor.height / 2f
    }

    private fun setPointer(on: Boolean) {
        pointer = on
        cursor.visibility = if (on) View.VISIBLE else View.GONE
        hint.visibility = if (on) View.VISIBLE else View.GONE
        stickX = 0f
        stickY = 0f
        scrollY = 0f
        web.requestFocus()
        if (!on) Toast.makeText(this, "The controller now plays the game. Start + Select opens the menu.", Toast.LENGTH_LONG).show()
    }

    private fun tap() {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cx, cy, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        web.dispatchTouchEvent(down)
        down.recycle()
        val x = cx
        val y = cy
        handler.postDelayed({
            val up = MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            web.dispatchTouchEvent(up)
            up.recycle()
        }, 70)
    }

    private val mover =
        object : Runnable {
            override fun run() {
                if (!pointer || (stickX == 0f && stickY == 0f && scrollY == 0f)) {
                    moving = false
                    return
                }
                cx += stickX * 9 * density
                cy += stickY * 9 * density
                placeCursor()
                if (scrollY != 0f) web.scrollBy(0, (scrollY * 14 * density).toInt())
                handler.postDelayed(this, 16)
            }
        }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val joystick = (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (pointer && joystick && event.action == MotionEvent.ACTION_MOVE) {
            fun axis(a: Int): Float = event.getAxisValue(a).let { if (abs(it) < 0.2f) 0f else it }
            stickX = axis(MotionEvent.AXIS_X) + axis(MotionEvent.AXIS_HAT_X)
            stickY = axis(MotionEvent.AXIS_Y) + axis(MotionEvent.AXIS_HAT_Y)
            scrollY = axis(MotionEvent.AXIS_RZ)
            if (!moving && (stickX != 0f || stickY != 0f || scrollY != 0f)) {
                moving = true
                handler.post(mover)
            }
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val up = event.action == KeyEvent.ACTION_UP
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_SELECT -> selectDown = down
            KeyEvent.KEYCODE_BUTTON_START -> startDown = down
        }
        if (selectDown && startDown) {
            openMenu()
            return true
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (up) openMenu()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_THUMBL -> {
                if (up) setPointer(!pointer)
                return true
            }
        }
        if (!pointer) return super.dispatchKeyEvent(event)

        val step = (14 + 6 * event.repeatCount.coerceAtMost(8)) * density
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) cx -= step
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) cx += step
            KeyEvent.KEYCODE_DPAD_UP -> if (down) cy -= step
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) cy += step
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (up) tap()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                if (up) openMenu()
                return true
            }
            else -> return super.dispatchKeyEvent(event)
        }
        placeCursor()
        return true
    }

    private fun openMenu() {
        if (menuOpen || isFinishing) return
        menuOpen = true
        selectDown = false
        startDown = false
        val items =
            arrayOf(
                "Netplay: host or join a room",
                if (pointer) "Hide the pointer and play" else "Show the pointer",
                "Reload the page",
                "Exit online play",
            )
        AlertDialog.Builder(this)
            .setTitle("Online play")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openNetplayMenu()
                    1 -> setPointer(!pointer)
                    2 -> web.reload()
                    3 -> finish()
                }
            }
            .setOnDismissListener { menuOpen = false }
            .show()
    }

    private fun openNetplayMenu() {
        web.evaluateJavascript(JS_OPEN_NETPLAY) { result ->
            when {
                result?.contains("ok") == true -> {
                    setPointer(true)
                    hint.text = "Use the pointer to create or join a room. Then open the menu (Start + Select) and hide the pointer to play."
                }
                result?.contains("none") == true ->
                    Toast.makeText(this, "Start the game first: click Play on the page.", Toast.LENGTH_LONG).show()
                else ->
                    Toast.makeText(this, "This RomM server's player has no netplay menu. Check that netplay is turned on in RomM.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        web.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ROM_ID = "rom_id"

        private const val POINTER_HINT =
            "Pointer: move with the D-pad or left stick, press A to click. Start + Select (or Back) opens the menu."

        private const val JS_AUTO_START =
            "(function(){if(window.EJS_emulator&&window.EJS_emulator.started)return 'running';" +
                "if(window.EJS_emulator)return 'loading';" +
                "var b=Array.prototype.slice.call(document.querySelectorAll('button')).filter(function(x){" +
                "return /^\\s*play\\s*$/i.test(x.innerText||'');})[0];" +
                "if(b){b.click();return 'clicked';}return 'waiting';})()"

        private const val JS_OPEN_NETPLAY =
            "(function(){var e=window.EJS_emulator;if(!e)return 'none';" +
                "if(typeof e.openNetplayMenu==='function'){e.openNetplayMenu();return 'ok';}return 'nomenu';})()"

        fun launch(
            context: Context,
            romId: Int,
        ) {
            context.startActivity(Intent(context, NetplayActivity::class.java).putExtra(EXTRA_ROM_ID, romId))
        }
    }
}
