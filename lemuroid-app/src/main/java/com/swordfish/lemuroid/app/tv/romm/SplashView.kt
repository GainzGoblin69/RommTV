package com.swordfish.lemuroid.app.tv.romm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.swordfish.lemuroid.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Larry Slop Software start-up animation, the same one as in Tanks Rebirth (Build 227 with its start-up chime):
 * a bright white room, green, lime and silver ribbons of light sweeping in to the middle, a flash and a shockwave ring,
 * then a glossy green orb with Larry's face pops in and the name fades in under it.
 * It has its own surface and draws from its own thread, so it keeps playing smoothly while the app loads.
 */
class SplashView(context: Context, private val face: Bitmap?) : SurfaceView(context), SurfaceHolder.Callback {
    private class Ribbon(val angle: Float, val dir: Float, val delay: Float, val width: Float, val turns: Float, val kind: Int)

    private class Spark(val angle: Float, val speed: Float, val size: Float, val white: Boolean)

    // The same layout as Tanks Rebirth (its .NET Random(11) sequence, reproduced)
    private val ribbons =
        arrayOf(
            Ribbon(0.18917f, 1f, 0.01348f, 0.86982f, 1.34362f, 0),
            Ribbon(0.76708f, -1f, 0.05153f, 1.13426f, 1.37642f, 1),
            Ribbon(1.53164f, 1f, 0.09899f, 0.82716f, 0.83310f, 2),
            Ribbon(2.17224f, -1f, 0.05770f, 1.02729f, 1.34006f, 0),
            Ribbon(2.98705f, 1f, 0.24460f, 1.23247f, 1.14431f, 1),
            Ribbon(3.60205f, -1f, 0.16403f, 1.14567f, 1.32526f, 2),
            Ribbon(4.49999f, 1f, 0.18039f, 0.82400f, 1.10198f, 0),
            Ribbon(5.12099f, -1f, 0.28202f, 0.72366f, 1.01048f, 1),
            Ribbon(5.87327f, 1f, 0.07930f, 0.88856f, 0.94381f, 2),
        )
    private val sparks =
        arrayOf(
            Spark(0.89370f, 1.17749f, 0.62691f, true),
            Spark(5.96767f, 1.07203f, 0.81257f, false),
            Spark(4.44029f, 0.78180f, 0.47960f, false),
            Spark(1.39145f, 0.97518f, 1.08805f, false),
            Spark(3.85890f, 1.02896f, 0.79744f, false),
            Spark(1.21877f, 0.88146f, 0.52803f, false),
            Spark(4.90294f, 0.63134f, 0.77555f, false),
            Spark(0.51451f, 0.34514f, 0.73464f, false),
            Spark(4.45263f, 0.74216f, 1.15786f, false),
            Spark(3.69719f, 1.07167f, 1.02406f, false),
            Spark(0.84310f, 0.63270f, 0.56874f, true),
            Spark(3.27795f, 1.06190f, 0.68185f, false),
            Spark(1.58348f, 0.87165f, 1.13664f, false),
            Spark(5.44677f, 0.85750f, 0.44571f, false),
            Spark(4.70901f, 0.95583f, 0.82296f, false),
            Spark(2.49120f, 0.31420f, 0.83637f, false),
            Spark(0.92468f, 0.44929f, 1.32088f, false),
            Spark(5.76368f, 0.37922f, 0.48800f, false),
            Spark(0.65913f, 1.19099f, 1.19198f, false),
            Spark(2.59322f, 1.13183f, 0.44938f, true),
            Spark(0.31096f, 0.76557f, 0.54635f, false),
            Spark(1.93837f, 0.64307f, 0.46342f, true),
            Spark(2.49098f, 0.49708f, 1.25937f, false),
            Spark(2.19098f, 0.30562f, 0.50996f, true),
            Spark(5.63183f, 0.80718f, 0.54903f, false),
            Spark(5.28431f, 0.80256f, 1.39266f, true),
            Spark(3.03882f, 1.19414f, 1.28667f, false),
            Spark(4.80987f, 0.48444f, 0.54805f, false),
            Spark(3.81048f, 0.70453f, 0.96895f, false),
            Spark(5.45289f, 1.22613f, 0.75305f, false),
            Spark(0.53957f, 1.06971f, 0.78284f, false),
            Spark(1.38137f, 0.45541f, 0.89379f, true),
            Spark(4.90735f, 0.97200f, 1.36494f, false),
            Spark(4.29013f, 0.97697f, 0.57740f, false),
            Spark(2.97135f, 1.27355f, 0.42660f, false),
            Spark(2.86764f, 0.38753f, 0.78565f, true),
            Spark(0.30808f, 1.24173f, 0.72614f, false),
            Spark(2.74367f, 0.47904f, 0.65213f, false),
            Spark(0.96842f, 0.64493f, 0.68653f, true),
            Spark(0.75217f, 0.57243f, 1.35295f, true),
        )

    private var startedAt = SystemClock.uptimeMillis()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    private val mid = Array(N + 1) { PointF() }
    private val half = FloatArray(N + 1)
    private val nx = FloatArray(N + 1)
    private val ny = FloatArray(N + 1)

    // the drawing thread
    private var thread: Thread? = null

    @Volatile private var running = false

    @Volatile private var fadeStart = -1L

    @Volatile private var onGone: (() -> Unit)? = null
    private var started = false

    /** Seconds since the animation started playing (0 until its surface exists). */
    val elapsed: Float
        get() = if (started) (SystemClock.uptimeMillis() - startedAt) / 1000f else 0f

    init {
        // above everything else, and see-through (so it can fade out to reveal the app)
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
    }

    /** Fades the intro out, then calls [gone] (on the drawing thread) once nothing is left. */
    fun fadeOut(gone: () -> Unit) {
        onGone = gone
        fadeStart = SystemClock.uptimeMillis()
        if (!running) gone() // (never got a surface)
    }

    /** The start-up chime (an original whoosh into a bright chord, timed to the ribbons and the flash). */
    private fun playChime() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null && am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
            val mp = MediaPlayer.create(context, R.raw.intro_chime) ?: return
            chime = mp // (held, so it isn't collected mid-play)
            mp.setVolume(0.55f, 0.55f)
            mp.setOnCompletionListener {
                it.release()
                chime = null
            }
            mp.start()
        } catch (e: Throwable) {
            Log.w(TAG, "Chime unavailable: $e")
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (running) return
        if (!started) {
            started = true
            startedAt = SystemClock.uptimeMillis()
            playChime()
        }
        running = true
        thread = Thread({ loop() }, "Intro").apply { isDaemon = true; start() }
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        try {
            thread?.join(500)
        } catch (_: Throwable) {
        }
    }

    private fun loop() {
        var hardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        var broken = false
        while (running) {
            var fade = if (fadeStart < 0) 1f else 1f - (SystemClock.uptimeMillis() - fadeStart) / 1000f / FADE_SECONDS
            var canvas: Canvas? = null
            try {
                canvas =
                    try {
                        if (hardware) holder.lockHardwareCanvas() else holder.lockCanvas()
                    } catch (_: Throwable) {
                        hardware = false
                        null
                    }
                if (canvas == null) {
                    Thread.sleep(16)
                    continue
                }
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                if (fade > 0f) {
                    val w = canvas.width.toFloat()
                    val h = canvas.height.toFloat()
                    val layer = fade < 1f
                    if (layer) canvas.saveLayerAlpha(0f, 0f, w, h, (255 * clamp01(fade)).toInt())
                    if (broken) {
                        canvas.drawColor(Color.WHITE) // (a plain white screen rather than ever stopping the app from starting)
                    } else {
                        try {
                            drawFrame(canvas, w, h)
                        } catch (e: Throwable) {
                            broken = true
                            Log.w(TAG, "Intro animation failed", e)
                        }
                    }
                    if (layer) canvas.restore()
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Intro surface failed: $e")
                fade = 0f
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (_: Throwable) {
                    }
                }
            }
            if (fade <= 0f) {
                running = false
                try {
                    onGone?.invoke()
                } catch (_: Throwable) {
                }
                return
            }
            Thread.sleep(if (hardware) 6L else 12L)
        }
    }

    private fun fill() {
        p.reset()
        p.isAntiAlias = true
        p.style = Paint.Style.FILL
        p.letterSpacing = 0f
    }

    private fun drawFrame(
        canvas: Canvas,
        w: Float,
        h: Float,
    ) {
        val t = (SystemClock.uptimeMillis() - startedAt) / 1000f
        val cx = w / 2f
        val cy = h * 0.43f
        val unit = min(w, h)
        val orbR = unit * 0.2f

        // a bright white room, a touch greyer at the edges
        fill()
        p.shader = RadialGradient(cx, cy, max(w, h) * 0.75f, intArrayOf(argb(1f, 255, 255, 255), argb(1f, 218, 226, 218)), null, CLAMP)
        canvas.drawRect(0f, 0f, w, h, p)

        // ribbons of light sweeping in to the middle: tapered bands, each with a soft glow and a bright core
        val ribbonFade = 1f - clamp01((t - 1.22f) / 0.25f)
        if (ribbonFade > 0f) {
            for (rb in ribbons) {
                val head = easeInOut((t - rb.delay) / 1.1f)
                if (head <= 0f) continue
                val tail = max(0f, head - 0.5f)
                for (s in 0..N) spiral(rb, tail + (head - tail) * s / N, cx, cy, w, h, orbR, mid[s])
                for (s in 0..N) {
                    val a = mid[max(0, s - 1)]
                    val b = mid[min(N, s + 1)]
                    val dx = b.x - a.x
                    val dy = b.y - a.y
                    val len = max(0.001f, sqrt(dx * dx + dy * dy))
                    nx[s] = -dy / len
                    ny[s] = dx / len
                    half[s] = unit * 0.034f * rb.width * sin(s.toFloat() / N * PI.toFloat()).pow(0.7f) * (1f - 0.55f * head)
                }
                val k = KINDS[rb.kind]
                for (pass in 0..1) {
                    val scale = if (pass == 0) 2.2f else 1f
                    val alpha = if (pass == 0) 0.10f else 0.62f
                    path.reset()
                    path.moveTo(mid[0].x + nx[0] * half[0] * scale, mid[0].y + ny[0] * half[0] * scale)
                    for (s in 1..N) path.lineTo(mid[s].x + nx[s] * half[s] * scale, mid[s].y + ny[s] * half[s] * scale)
                    for (s in N downTo 0) path.lineTo(mid[s].x - nx[s] * half[s] * scale, mid[s].y - ny[s] * half[s] * scale)
                    path.close()
                    fill()
                    p.shader =
                        LinearGradient(
                            mid[0].x, mid[0].y, mid[N].x, mid[N].y,
                            intArrayOf(argb(0f, k[0], k[1], k[2]), argb(alpha * ribbonFade, k[0], k[1], k[2])),
                            null, CLAMP,
                        )
                    canvas.drawPath(path, p)
                }
                fill()
                p.style = Paint.Style.STROKE
                p.strokeCap = Paint.Cap.ROUND
                for (s in N / 2 until N) {
                    val along = s.toFloat() / N
                    p.strokeWidth = unit * 0.006f * rb.width * sin(along * PI.toFloat()).pow(0.7f)
                    p.color = argb(0.7f * (along - 0.5f) * 2f * ribbonFade, 255, 255, 255)
                    canvas.drawLine(mid[s].x, mid[s].y, mid[s + 1].x, mid[s + 1].y, p)
                }
            }
        }

        // light gathering in the middle while the ribbons arrive
        val gather = clamp01((t - 0.5f) / 0.7f) * (1f - clamp01((t - 1.3f) / 0.3f))
        if (gather > 0f) {
            val rr = orbR * (0.6f + 0.8f * gather)
            fill()
            p.shader =
                RadialGradient(
                    cx, cy, rr,
                    intArrayOf(argb(0.85f * gather, 255, 255, 255), argb(0.35f * gather, 150, 220, 90), argb(0f, 150, 220, 90)),
                    floatArrayOf(0f, 0.4f, 1f), CLAMP,
                )
            canvas.drawCircle(cx, cy, rr, p)
        }

        // the flash where they meet, and a ring racing outward
        val flash = (t - 1.22f) / 0.6f
        if (flash > 0f && flash < 1f) {
            val fr = unit * (0.12f + 0.95f * easeOut(flash))
            val fa = 1f - flash
            fill()
            p.shader =
                RadialGradient(
                    cx, cy, fr,
                    intArrayOf(argb(fa, 255, 255, 255), argb(0.45f * fa, 150, 220, 90), argb(0f, 150, 220, 90)),
                    floatArrayOf(0f, 0.45f, 1f), CLAMP,
                )
            canvas.drawCircle(cx, cy, fr, p)
            fill()
            p.style = Paint.Style.STROKE
            p.strokeWidth = max(1f, unit * 0.012f * (1f - flash))
            p.color = argb(0.6f * (1f - flash), 16, 124, 16)
            canvas.drawCircle(cx, cy, orbR * (1f + 2.6f * easeOut(flash)), p)
        }

        // sparkles drifting out after the flash
        val sparkT = t - 1.3f
        if (sparkT > 0f && sparkT < 1.6f) {
            fill()
            for (sp in sparks) {
                val d = orbR * (1.05f + sp.speed * 2.3f * easeOut(sparkT / 1.6f))
                val a = (1f - sparkT / 1.6f) * 0.9f
                p.color = if (sp.white) argb(a, 255, 255, 255) else argb(a, 122, 205, 60)
                canvas.drawCircle(cx + cos(sp.angle) * d, cy + sin(sp.angle) * d, unit * 0.006f * sp.size, p)
            }
        }

        // the orb pops in: a glossy green ball with Larry in a round window
        val orbT = (t - 1.3f) / 0.5f
        if (orbT > 0f) {
            val scale = pop(orbT)
            val r = orbR * scale * (1f + 0.012f * sin(max(0f, t - 1.8f) * 3f)) // (it breathes a little)
            // its shadow on the floor
            val sy = cy + orbR * 1.22f
            val sr = orbR * 1.15f * scale
            canvas.save()
            canvas.scale(1f, 0.2f, cx, sy)
            fill()
            p.shader = RadialGradient(cx, sy, sr, intArrayOf(argb(0.26f, 0, 40, 0), argb(0f, 0, 40, 0)), null, CLAMP)
            canvas.drawCircle(cx, sy, sr, p)
            canvas.restore()
            // a green halo around it
            fill()
            p.shader =
                RadialGradient(
                    cx, cy, r * 1.35f,
                    intArrayOf(argb(0f, 122, 205, 60), argb(0.35f, 122, 205, 60), argb(0f, 122, 205, 60)),
                    floatArrayOf(0.7f, 0.76f, 1f), CLAMP,
                )
            canvas.drawCircle(cx, cy, r * 1.35f, p)
            // the ball: lit from the upper left
            fill()
            p.shader =
                RadialGradient(
                    cx - r * 0.3f, cy - r * 0.38f, r * 1.45f,
                    intArrayOf(argb(1f, 178, 240, 120), argb(1f, 76, 176, 44), argb(1f, 14, 96, 14), argb(1f, 5, 46, 8)),
                    floatArrayOf(0f, 0.32f, 0.72f, 1f), CLAMP,
                )
            canvas.drawCircle(cx, cy, r, p)
            // Larry
            val fr = r * 0.78f
            if (face != null) {
                canvas.save()
                path.reset()
                path.addCircle(cx, cy, fr, Path.Direction.CW)
                canvas.clipPath(path)
                fill()
                p.isFilterBitmap = true
                canvas.drawBitmap(face, null, RectF(cx - fr, cy - fr, cx + fr, cy + fr), p)
                // a green shade at the rim, so he sits inside the ball
                fill()
                p.shader =
                    RadialGradient(
                        cx, cy, fr,
                        intArrayOf(argb(0f, 10, 80, 10), argb(0f, 10, 80, 10), argb(0.62f, 8, 60, 8)),
                        floatArrayOf(0f, 0.7f, 1f), CLAMP,
                    )
                canvas.drawCircle(cx, cy, fr, p)
                canvas.restore()
            }
            // the window's rim, with a thin bright edge
            fill()
            p.style = Paint.Style.STROKE
            p.strokeWidth = r * 0.045f
            p.color = Color.rgb(8, 58, 8)
            canvas.drawCircle(cx, cy, fr, p)
            p.strokeWidth = max(1f, r * 0.012f)
            p.color = argb(0.5f, 190, 245, 150)
            canvas.drawCircle(cx, cy, fr + r * 0.03f, p)
            // glossy shine across the top
            fill()
            p.shader = LinearGradient(0f, cy - r, 0f, cy - r * 0.1f, intArrayOf(argb(0.62f, 255, 255, 255), argb(0f, 255, 255, 255)), null, CLAMP)
            canvas.drawOval(RectF(cx - r * 0.66f, cy - r * 0.96f, cx + r * 0.66f, cy - r * 0.12f), p)
            // one sweep of light across the ball, just after it lands
            val sweep = (t - 1.9f) / 0.6f
            if (sweep > 0f && sweep < 1f) {
                canvas.save()
                path.reset()
                path.addCircle(cx, cy, r, Path.Direction.CW)
                canvas.clipPath(path)
                canvas.rotate(20f, cx, cy)
                val sx = cx - r * 1.6f + r * 3.2f * sweep
                fill()
                p.shader =
                    LinearGradient(
                        sx - r * 0.3f, 0f, sx + r * 0.3f, 0f,
                        intArrayOf(argb(0f, 255, 255, 255), argb(0.5f, 255, 255, 255), argb(0f, 255, 255, 255)),
                        null, CLAMP,
                    )
                canvas.drawRect(cx - r * 2f, cy - r * 2f, cx + r * 2f, cy + r * 2f, p)
                canvas.restore()
            }
        }

        // the name slides up and fades in under the orb, with a thin line growing between the two words
        val textT = easeOut((t - 1.85f) / 0.6f)
        if (textT > 0f) {
            val ty = cy + orbR * 1.78f + (1f - textT) * unit * 0.03f
            fill()
            p.textAlign = Paint.Align.CENTER
            p.typeface = bold
            p.textSize = unit * 0.085f
            p.color = argb(textT, 70, 76, 70)
            canvas.drawText("Larry Slop", cx, ty, p)
            val lw = unit * 0.26f * textT
            val ly = ty + unit * 0.03f
            fill()
            p.color = argb(0.5f * textT, 16, 124, 16)
            canvas.drawRect(cx - lw, ly, cx + lw, ly + max(1f, unit * 0.003f), p)
            fill()
            p.textAlign = Paint.Align.CENTER
            p.typeface = light
            p.textSize = unit * 0.04f
            p.letterSpacing = 0.5f
            p.color = argb(textT, 16, 124, 16)
            canvas.drawText("SOFTWARE", cx + p.textSize * 0.25f, ty + unit * 0.083f, p)
        }
    }

    /** A point along a ribbon: from off-screen (u = 0), spiralling in to the middle (u = 1). */
    private fun spiral(
        rb: Ribbon,
        u: Float,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        orbR: Float,
        out: PointF,
    ) {
        val far = sqrt(w * w + h * h) * 0.62f
        val radius = orbR * 0.55f + (far - orbR * 0.55f) * (1f - u).pow(1.7f)
        val ang = rb.angle + rb.dir * u * rb.turns * PI.toFloat()
        out.set(cx + cos(ang) * radius, cy + sin(ang) * radius * 0.8f)
    }

    companion object {
        private const val TAG = "RommTV"

        /** How long the animation runs (seconds); it holds its last frame after that. */
        const val LENGTH = 2.6f
        private const val FADE_SECONDS = 0.45f
        private const val N = 36 // pieces per ribbon

        // ribbon colors: deep green, lime, silver
        private val KINDS = arrayOf(intArrayOf(16, 124, 16), intArrayOf(122, 205, 60), intArrayOf(190, 198, 190))
        private val CLAMP = Shader.TileMode.CLAMP

        private var chime: MediaPlayer? = null

        private fun clamp01(v: Float) = max(0f, min(1f, v))

        private fun easeOut(t: Float) = 1f - (1f - clamp01(t)).pow(3)

        private fun easeInOut(t: Float): Float {
            val c = clamp01(t)
            return c * c * (3f - 2f * c)
        }

        /** Overshoots a little past 1 and settles (a "pop"). */
        private fun pop(t: Float): Float {
            val c = clamp01(t)
            val c1 = 1.9f
            val c3 = c1 + 1f
            return 1f + c3 * (c - 1f).pow(3) + c1 * (c - 1f).pow(2)
        }

        private fun argb(
            a: Float,
            r: Int,
            g: Int,
            b: Int,
        ): Int = Color.argb((255 * clamp01(a)).toInt(), r, g, b)
    }
}
