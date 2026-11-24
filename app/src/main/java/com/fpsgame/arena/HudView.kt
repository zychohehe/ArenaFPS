package com.fpsgame.arena

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Lapisan kontrol sentuh + HUD. Kiri: joystick melayang. Kanan: geser untuk melihat. */
class HudView(ctx: Context, private val g: Game) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var moveId = -1; private var lookId = -1; private var fireId = -1; private var relId = -1
    private var ox = 0f; private var oy = 0f; private var mx = 0f; private var my = 0f
    private var lx = 0f; private var ly = 0f

    private val fireX: Float get() = width - 105f * d
    private val fireY: Float get() = height - 95f * d
    private val fireR: Float get() = 58f * d
    private val relX: Float get() = width - 235f * d
    private val relY: Float get() = height - 70f * d
    private val relR: Float get() = 34f * d
    private val stickR: Float get() = 70f * d

    private fun down(id: Int, x: Float, y: Float) {
        if (g.dead) { g.restartReq = true; return }
        if (hypot(x - fireX, y - fireY) <= fireR * 1.25f) { fireId = id; g.firing = true; return }
        if (hypot(x - relX, y - relY) <= relR * 1.3f) { relId = id; g.reloadReq = true; return }
        if (x < width * 0.42f) {
            if (moveId == -1) { moveId = id; ox = x; oy = y; mx = x; my = y }
        } else if (lookId == -1) { lookId = id; lx = x; ly = y }
    }

    private fun move(id: Int, x: Float, y: Float) {
        if (id == moveId) {
            mx = x; my = y
            val dx = x - ox; val dy = y - oy
            val l = hypot(dx, dy) / stickR
            if (l < 0.1f) { g.moveX = 0f; g.moveY = 0f } else {
                val s = min(l, 1f)
                val mag = (s - 0.1f) / 0.9f
                g.moveX = dx / (l * stickR) * mag
                g.moveY = -dy / (l * stickR) * mag
            }
        } else if (id == lookId) {
            g.addLook(x - lx, y - ly); lx = x; ly = y
        }
    }

    private fun up(id: Int) {
        if (id == moveId) { moveId = -1; g.moveX = 0f; g.moveY = 0f }
        if (id == lookId) lookId = -1
        if (id == fireId) { fireId = -1; g.firing = false }
        if (id == relId) relId = -1
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex; down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> {
                moveId = -1; lookId = -1; fireId = -1; relId = -1
                g.moveX = 0f; g.moveY = 0f; g.firing = false
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.LEFT

        if (g.hurt > 0f) {
            p.style = Paint.Style.FILL
            p.color = Color.argb((g.hurt * 110).toInt().coerceIn(0, 255), 255, 0, 0)
            c.drawRect(0f, 0f, w, h, p)
        }

        // crosshair
        val cx = w / 2f; val cy = h / 2f
        val gap = (6f + (if (g.firing) 6f else 0f) + min(8f, g.speedNow)) * d
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d
        p.color = Color.argb(220, 255, 255, 255)
        val ln = 9f * d
        c.drawLine(cx - gap - ln, cy, cx - gap, cy, p); c.drawLine(cx + gap, cy, cx + gap + ln, cy, p)
        c.drawLine(cx, cy - gap - ln, cx, cy - gap, p); c.drawLine(cx, cy + gap, cx, cy + gap + ln, p)
        if (g.hitMarker > 0f) {
            p.color = Color.argb((g.hitMarker * 255).toInt().coerceIn(0, 255), 255, 70, 70)
            val a = 10f * d; val b = 17f * d
            c.drawLine(cx - b, cy - b, cx - a, cy - a, p); c.drawLine(cx + b, cy - b, cx + a, cy - a, p)
            c.drawLine(cx - b, cy + b, cx - a, cy + a, p); c.drawLine(cx + b, cy + b, cx + a, cy + a, p)
        }

        // HP
        val f = (g.hp / 100f).coerceIn(0f, 1f)
        p.style = Paint.Style.FILL
        p.color = Color.argb(140, 0, 0, 0)
        c.drawRoundRect(16f * d, 16f * d, 196f * d, 32f * d, 8f * d, 8f * d, p)
        p.color = Color.rgb((230 * (1f - f) + 30).toInt().coerceIn(0, 255), (60 + 170 * f).toInt(), 60)
        c.drawRoundRect(16f * d, 16f * d, (16f + 180f * f) * d, 32f * d, 8f * d, 8f * d, p)

        // teks
        p.color = Color.WHITE
        p.textSize = 16f * d
        c.drawText("HP ${g.hp.toInt()}", 16f * d, 54f * d, p)
        p.textAlign = Paint.Align.RIGHT
        c.drawText("SKOR ${g.score}", w - 16f * d, 30f * d, p)
        c.drawText("GELOMBANG ${g.wave}  •  MUSUH ${g.alive}", w - 16f * d, 54f * d, p)
        p.textAlign = Paint.Align.CENTER
        p.textSize = 20f * d
        c.drawText(if (g.reloading) "ISI ULANG..." else "${g.ammo} / 30", w / 2f, h - 20f * d, p)

        // joystick
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f * d
        if (moveId != -1) {
            p.color = Color.argb(110, 255, 255, 255)
            c.drawCircle(ox, oy, stickR, p)
            val dx = mx - ox; val dy = my - oy
            val l = max(hypot(dx, dy), 0.001f); val s = min(l, stickR) / l
            p.style = Paint.Style.FILL; p.color = Color.argb(150, 255, 255, 255)
            c.drawCircle(ox + dx * s, oy + dy * s, 28f * d, p)
        } else {
            p.color = Color.argb(50, 255, 255, 255)
            c.drawCircle(130f * d, h - 100f * d, stickR, p)
        }

        // tombol
        p.style = Paint.Style.FILL
        p.color = Color.argb(if (fireId != -1) 170 else 90, 255, 60, 60)
        c.drawCircle(fireX, fireY, fireR, p)
        p.color = Color.argb(if (relId != -1) 170 else 90, 255, 255, 255)
        c.drawCircle(relX, relY, relR, p)
        p.color = Color.WHITE; p.textSize = 15f * d; p.textAlign = Paint.Align.CENTER
        c.drawText("TEMBAK", fireX, fireY + 5f * d, p)
        c.drawText("R", relX, relY + 6f * d, p)

        if (g.banner > 0f && !g.dead) {
            p.textSize = 34f * d
            p.color = Color.argb((min(1f, g.banner) * 255).toInt(), 255, 220, 80)
            c.drawText("GELOMBANG ${g.wave}", cx, h * 0.28f, p)
        }

        if (g.dead) {
            p.style = Paint.Style.FILL
            p.color = Color.argb(150, 0, 0, 0)
            c.drawRect(0f, 0f, w, h, p)
            p.color = Color.WHITE
            p.textSize = 40f * d
            c.drawText("KAMU MATI", cx, h * 0.4f, p)
            p.textSize = 20f * d
            c.drawText("Skor: ${g.score}  •  Gelombang: ${g.wave}", cx, h * 0.4f + 36f * d, p)
            c.drawText("Ketuk layar untuk main lagi", cx, h * 0.4f + 70f * d, p)
        }

        postInvalidateOnAnimation()
    }
}
