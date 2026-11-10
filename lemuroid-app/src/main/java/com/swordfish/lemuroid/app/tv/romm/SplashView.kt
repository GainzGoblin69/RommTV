package com.swordfish.lemuroid.app.tv.romm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The Larry Slop Software start-up animation, the same one as in Tanks Rebirth: a bright white room,
 * green ribbons of light spiralling in to the middle, a flash, then a glossy green orb with Larry's face pops in,
 * and the name fades in under it. Drawn with plain Android graphics, so it plays while the app is still loading.
 */
class SplashView(context: Context, private val face: Bitmap?) : View(context) {
    private val start = SystemClock.uptimeMillis()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rng = Random(7)
    private val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val bold: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private class Ribbon(val angle: Float, val dir: Float, val delay: Float, val width: Float, val turns: Float)

    private class Spark(val angle: Float, val speed: Float, val size: Float, val white: Boolean)

    private val ribbons =
        Array(7) { i ->
            Ribbon(
                angle = (i * 2 * PI / 7).toFloat() + rng.nextFloat() * 0.5f,
                dir = if (i % 2 == 0) 1f else -1f,
                delay = rng.nextFloat() * 0.35f,
                width = 0.6f + rng.nextFloat() * 0.6f,
                turns = 0.9f + rng.nextFloat() * 0.7f,
            )
        }
    private val sparks =
        Array(36) {
            Spark(
                angle = rng.nextFloat() * 2 * PI.toFloat(),
                speed = 0.35f + rng.nextFloat() * 0.9f,
                size = 0.4f + rng.nextFloat(),
                white = rng.nextInt(3) == 0,
            )
        }

    private fun clamp01(v: Float) = max(0f, min(1f, v))

    private fun easeOut(t: Float) = 1f - (1f - clamp01(t)).pow(3)

    private fun easeInOut(t: Float): Float {
        val c = clamp01(t)
        return c * c * (3f - 2f * c)
    }

    /** Overshoots a little past 1 and settles: a "pop". */
    private fun pop(t: Float): Float {
        val c = clamp01(t)
        val c1 = 1.9f
        val c3 = c1 + 1f
        return 1f + c3 * (c - 1f).pow(3) + c1 * (c - 1f).pow(2)
    }

    private fun withAlpha(
        color: Int,
        a: Float,
    ): Int = Color.argb((255 * clamp01(a)).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        try {
            drawFrame(canvas, (SystemClock.uptimeMillis() - start) / 1000f)
        } catch (e: Throwable) {
            // Never let the intro stop the app: fall back to a plain white screen
            canvas.drawColor(Color.WHITE)
            return
        }
        if ((SystemClock.uptimeMillis() - start) / 1000f < LENGTH + 0.2f) postInvalidateOnAnimation()
    }

    private fun drawFrame(
        canvas: Canvas,
        t: Float,
    ) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h * 0.44f
        val unit = min(w, h)
        val orbR = unit * 0.2f

        // a bright white room, a touch greyer at the edges
        p.reset()
        p.isAntiAlias = true
        p.shader = RadialGradient(cx, cy, max(w, h) * 0.75f, Color.WHITE, Color.rgb(222, 228, 222), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, p)
        p.shader = null

        // green ribbons of light spiralling in to the middle
        for (rb in ribbons) {
            val head = easeInOut((t - rb.delay) / 1.15f)
            if (head <= 0f) continue
            val fadeOut = 1f - clamp01((t - 1.25f) / 0.3f)
            if (fadeOut <= 0f) continue
            val tail = max(0f, head - 0.45f)
            val segs = 28
            for (s in 0 until segs) {
                val u0 = tail + (head - tail) * s / segs
                val u1 = tail + (head - tail) * (s + 1) / segs
                val a = spiral(rb, u0, cx, cy, w, h, orbR)
                val b = spiral(rb, u1, cx, cy, w, h, orbR)
                val along = (s + 1f) / segs
                val thick = unit * 0.05f * rb.width * (0.25f + 0.75f * along) * (1f - 0.5f * head)
                p.style = Paint.Style.STROKE
                p.strokeCap = Paint.Cap.ROUND
                p.strokeWidth = thick * 2.4f
                p.color = withAlpha(LIME, 0.16f * along * fadeOut)
                canvas.drawLine(a.x, a.y, b.x, b.y, p)
                p.strokeWidth = thick
                p.color = withAlpha(GREEN, 0.75f * along * fadeOut)
                canvas.drawLine(a.x, a.y, b.x, b.y, p)
                p.strokeWidth = thick * 0.35f
                p.color = withAlpha(Color.WHITE, 0.6f * along * fadeOut)
                canvas.drawLine(a.x, a.y, b.x, b.y, p)
            }
        }
        p.style = Paint.Style.FILL

        // the flash where they meet
        val flashT = (t - 1.2f) / 0.6f
        if (flashT > 0f && flashT < 1f) {
            val fr = unit * (0.1f + 0.9f * easeOut(flashT))
            val fa = 1f - flashT
            p.shader =
                RadialGradient(
                    cx, cy, fr,
                    intArrayOf(withAlpha(Color.WHITE, fa), withAlpha(LIME, 0.45f * fa), withAlpha(LIME, 0f)),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP,
                )
            canvas.drawCircle(cx, cy, fr, p)
            p.shader = null
        }

        // sparkles drifting out after the flash
        val sparkT = t - 1.3f
        if (sparkT > 0f && sparkT < 1.6f) {
            for (sp in sparks) {
                val d = orbR * (1.05f + sp.speed * 2.2f * easeOut(sparkT / 1.6f))
                val a = (1f - sparkT / 1.6f) * 0.9f
                p.color = withAlpha(if (sp.white) Color.WHITE else LIME, a)
                canvas.drawCircle(cx + cos(sp.angle) * d, cy + sin(sp.angle) * d, unit * 0.006f * sp.size, p)
            }
        }

        // the orb pops in: a glossy green ball with Larry in the middle
        val orbT = (t - 1.3f) / 0.5f
        if (orbT > 0f) {
            val scale = pop(orbT)
            val r = orbR * scale
            // shadow on the floor
            p.shader =
                RadialGradient(
                    cx, cy + orbR * 1.25f, orbR * 1.1f,
                    Color.argb(60, 0, 40, 0), Color.argb(0, 0, 40, 0), Shader.TileMode.CLAMP,
                )
            canvas.save()
            canvas.scale(1f, 0.22f, cx, cy + orbR * 1.25f)
            canvas.drawCircle(cx, cy + orbR * 1.25f, orbR * 1.1f * scale, p)
            canvas.restore()
            // the green ball
            p.shader =
                RadialGradient(
                    cx - r * 0.3f, cy - r * 0.35f, r * 1.5f,
                    intArrayOf(Color.rgb(170, 235, 110), Color.rgb(70, 170, 40), Color.rgb(14, 92, 14), Color.rgb(6, 50, 8)),
                    floatArrayOf(0f, 0.35f, 0.75f, 1f),
                    Shader.TileMode.CLAMP,
                )
            canvas.drawCircle(cx, cy, r, p)
            p.shader = null
            // Larry, in a round window
            val fr = r * 0.8f
            if (face != null) {
                canvas.save()
                path.reset()
                path.addCircle(cx, cy, fr, Path.Direction.CW)
                canvas.clipPath(path)
                p.isFilterBitmap = true
                canvas.drawBitmap(face, null, RectF(cx - fr, cy - fr, cx + fr, cy + fr), p)
                // a little green tint at the rim, so he sits inside the ball
                p.shader =
                    RadialGradient(
                        cx, cy, fr,
                        intArrayOf(Color.argb(0, 10, 80, 10), Color.argb(0, 10, 80, 10), Color.argb(150, 10, 80, 10)),
                        floatArrayOf(0f, 0.72f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                canvas.drawCircle(cx, cy, fr, p)
                p.shader = null
                canvas.restore()
            }
            // a dark rim around the window
            p.style = Paint.Style.STROKE
            p.strokeWidth = r * 0.05f
            p.color = Color.rgb(8, 60, 8)
            canvas.drawCircle(cx, cy, fr, p)
            p.style = Paint.Style.FILL
            // glossy shine across the top
            p.shader = LinearGradient(0f, cy - r, 0f, cy, Color.argb(170, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
            canvas.drawOval(RectF(cx - r * 0.72f, cy - r * 0.95f, cx + r * 0.72f, cy - r * 0.05f), p)
            p.shader = null
            // a sweep of light across the ball, once, just after it lands
            val sweep = (t - 1.9f) / 0.6f
            if (sweep > 0f && sweep < 1f) {
                canvas.save()
                path.reset()
                path.addCircle(cx, cy, r, Path.Direction.CW)
                canvas.clipPath(path)
                val sx = cx - r * 1.6f + r * 3.2f * sweep
                p.shader =
                    LinearGradient(
                        sx - r * 0.3f, 0f, sx + r * 0.3f, 0f,
                        intArrayOf(Color.argb(0, 255, 255, 255), Color.argb(120, 255, 255, 255), Color.argb(0, 255, 255, 255)),
                        null,
                        Shader.TileMode.CLAMP,
                    )
                canvas.drawRect(cx - r, cy - r, cx + r, cy + r, p)
                p.shader = null
                canvas.restore()
            }
        }

        // the name fades and slides in under the orb
        val textT = easeOut((t - 1.85f) / 0.6f)
        if (textT > 0f) {
            p.textAlign = Paint.Align.CENTER
            val ty = cy + orbR * 1.75f + (1f - textT) * unit * 0.03f
            p.typeface = bold
            p.textSize = unit * 0.085f
            p.letterSpacing = 0.02f
            p.color = withAlpha(Color.rgb(72, 76, 72), textT)
            canvas.drawText("Larry Slop", cx, ty, p)
            p.typeface = light
            p.textSize = unit * 0.042f
            p.letterSpacing = 0.45f
            p.color = withAlpha(GREEN, textT)
            canvas.drawText("SOFTWARE", cx, ty + unit * 0.07f, p)
            p.letterSpacing = 0f
        }
    }

    /** A point along a ribbon: from off-screen (u = 0), spiralling in to the orb's edge (u = 1). */
    private fun spiral(
        rb: Ribbon,
        u: Float,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        orbR: Float,
    ): PointF {
        val far = sqrt(w * w + h * h) * 0.65f
        val radius = orbR * 0.9f + (far - orbR * 0.9f) * (1f - u).pow(1.6f)
        val ang = rb.angle + rb.dir * u * rb.turns * PI.toFloat()
        return PointF(cx + cos(ang) * radius, cy + sin(ang) * radius * 0.82f)
    }

    companion object {
        /** How long the animation runs (seconds); it holds its last frame after that. */
        const val LENGTH = 2.8f

        /** The splash stays up at least this long: the whole animation plus a moment on the finished logo. */
        const val MIN_SHOW_MS = 3400L

        private val GREEN = Color.rgb(16, 124, 16)
        private val LIME = Color.rgb(122, 205, 60)
    }
}
