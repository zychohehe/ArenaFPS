package com.fpsgame.arena

import kotlin.math.*
import kotlin.random.Random

class Box(val cx: Float, val cz: Float, val hx: Float, val hz: Float, val h: Float)

class Enemy(var x: Float, var z: Float, val speed: Float, var hp: Float) {
    var flash = 0f
    var atk = 0.6f
    var yaw = 0f
    var phase = Random.nextFloat() * 6f
}

class Particle(
    var x: Float, var y: Float, var z: Float,
    var vx: Float, var vy: Float, var vz: Float,
    var life: Float, val r: Float, val g: Float, val b: Float
)

class Game(private val sfx: Sfx) {
    companion object { const val HALF = 15f }

    // ---- input (thread UI -> thread GL) ----
    @Volatile var moveX = 0f
    @Volatile var moveY = 0f
    @Volatile var firing = false
    @Volatile var reloadReq = false
    @Volatile var restartReq = false
    private var lookX = 0f
    private var lookY = 0f
    private val lock = Any()
    fun addLook(dx: Float, dy: Float) { synchronized(lock) { lookX += dx; lookY += dy } }
    var onHurt: (() -> Unit)? = null

    // ---- state ----
    var px = 0f; var pz = 10f
    var yaw = 0f; var pitch = 0f
    private var vx = 0f; private var vz = 0f
    var bob = 0f; var speedNow = 0f
    @Volatile var hp = 100f
    @Volatile var score = 0
    @Volatile var wave = 0
    @Volatile var ammo = 30
    @Volatile var alive = 0
    @Volatile var reloading = false
    @Volatile var hitMarker = 0f
    @Volatile var hurt = 0f
    @Volatile var banner = 0f
    @Volatile var dead = false
    var flash = 0f; var kick = 0f; var shake = 0f
    private var cooldown = 0f; private var reloadT = 0f; private var nextWave = 1f
    private var fdx = 0f; private var fdy = 0f
    private var tx = 0f; private var tz = 0f

    val enemies = ArrayList<Enemy>()
    val parts = ArrayList<Particle>()
    val pillars = listOf(
        Box(-8f, -6f, 1.5f, 1.5f, 3.5f), Box(8f, -6f, 1.5f, 1.5f, 3.5f),
        Box(-7f, 5f, 1.2f, 1.2f, 2.5f), Box(7f, 5f, 1.2f, 1.2f, 2.5f),
        Box(0f, -1f, 2.2f, 0.8f, 2.2f)
    )

    private fun reset() {
        px = 0f; pz = 10f; yaw = 0f; pitch = 0f; vx = 0f; vz = 0f
        hp = 100f; score = 0; wave = 0; ammo = 30; reloading = false; dead = false
        enemies.clear(); parts.clear(); nextWave = 1f; fdx = 0f; fdy = 0f
    }

    /** Dorong posisi keluar dari dinding arena & pilar. Hasil di tx, tz. */
    private fun push(x0: Float, z0: Float, r: Float) {
        var x = x0.coerceIn(-HALF + r, HALF - r)
        var z = z0.coerceIn(-HALF + r, HALF - r)
        for (b in pillars) {
            val nx = x.coerceIn(b.cx - b.hx, b.cx + b.hx)
            val nz = z.coerceIn(b.cz - b.hz, b.cz + b.hz)
            val dx = x - nx; val dz = z - nz
            val d2 = dx * dx + dz * dz
            if (d2 < r * r) {
                if (d2 > 1e-6f) {
                    val d = sqrt(d2)
                    x = nx + dx / d * r; z = nz + dz / d * r
                } else {
                    x = b.cx + (if (x >= b.cx) b.hx + r else -(b.hx + r))
                }
            }
        }
        tx = x; tz = z
    }

    private fun rayBox(
        ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float,
        x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float
    ): Float {
        var t0 = 0f; var t1 = 1e9f
        fun slab(o: Float, d: Float, lo: Float, hi: Float): Boolean {
            if (abs(d) < 1e-6f) return o >= lo && o <= hi
            var a = (lo - o) / d; var b = (hi - o) / d
            if (a > b) { val t = a; a = b; b = t }
            if (a > t0) t0 = a
            if (b < t1) t1 = b
            return t0 <= t1
        }
        if (!slab(ox, dx, x0, x1) || !slab(oy, dy, y0, y1) || !slab(oz, dz, z0, z1)) return -1f
        return t0
    }

    private fun burst(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, n: Int) {
        if (parts.size > 300) return
        repeat(n) {
            parts.add(Particle(x, y, z,
                (Random.nextFloat() - .5f) * 4f, Random.nextFloat() * 3f + .5f, (Random.nextFloat() - .5f) * 4f,
                .5f + Random.nextFloat() * .4f, r, g, b))
        }
    }

    private fun damage(a: Float) {
        hp -= a; hurt = 1f; shake = 0.6f
        sfx.hurt(); onHurt?.invoke()
        if (hp <= 0f) { hp = 0f; dead = true; firing = false }
    }

    private fun spawnWave() {
        val n = min(3 + wave * 2, 22)
        repeat(n) {
            var x = 0f; var z = 0f; var tries = 0
            do {
                val side = Random.nextInt(4)
                val r = Random.nextFloat() * 26f - 13f
                when (side) {
                    0 -> { x = r; z = -13.5f }
                    1 -> { x = r; z = 13.5f }
                    2 -> { x = -13.5f; z = r }
                    else -> { x = 13.5f; z = r }
                }
                tries++
            } while (hypot(x - px, z - pz) < 11f && tries < 30)
            enemies.add(Enemy(x, z, min(2f + wave * 0.2f, 4.2f) * (0.85f + Random.nextFloat() * 0.3f), 3f + (wave / 3)))
        }
    }

    private fun shoot() {
        cooldown = 0.11f; ammo--; flash = 0.05f; kick = 1f
        sfx.shot()
        val cp = cos(pitch)
        val sp = 0.004f + min(speedNow, 6f) * 0.003f
        val dx = sin(yaw) * cp + (Random.nextFloat() - .5f) * sp
        val dy = sin(pitch) + (Random.nextFloat() - .5f) * sp
        val dz = -cos(yaw) * cp + (Random.nextFloat() - .5f) * sp
        val ox = px; val oy = 1.6f; val oz = pz
        var best = 60f
        var target: Enemy? = null
        var head = false
        for (b in pillars) {
            val t = rayBox(ox, oy, oz, dx, dy, dz, b.cx - b.hx, 0f, b.cz - b.hz, b.cx + b.hx, b.h, b.cz + b.hz)
            if (t >= 0f && t < best) best = t
        }
        if (dy < -1e-4f) { val t = -oy / dy; if (t < best) best = t }
        for (e in enemies) {
            val t = rayBox(ox, oy, oz, dx, dy, dz, e.x - .45f, 0f, e.z - .45f, e.x + .45f, 1.85f, e.z + .45f)
            if (t >= 0f && t < best) { best = t; target = e; head = oy + dy * t > 1.45f }
        }
        val hx = ox + dx * best; val hy = oy + dy * best; val hz = oz + dz * best
        val e = target
        if (e != null) {
            e.hp -= if (head) 3f else 1f
            e.flash = 0.12f
            hitMarker = 1f
            sfx.hit()
            burst(hx, hy, hz, .8f, .1f, .1f, 5)
            if (e.hp <= 0f) {
                score += if (head) 150 else 100
                burst(e.x, 1.1f, e.z, .8f, .1f, .1f, 16)
                sfx.kill()
                enemies.remove(e)
            }
        } else {
            burst(hx, hy, hz, .8f, .8f, .7f, 3)
        }
    }

    fun update(dt: Float) {
        if (restartReq) { restartReq = false; reset() }
        hitMarker = max(0f, hitMarker - dt * 5f)
        hurt = max(0f, hurt - dt * 2f)
        banner = max(0f, banner - dt)
        flash = max(0f, flash - dt)
        kick *= exp(-dt * 14f)
        shake = max(0f, shake - dt * 3f)

        for (p in parts) {
            p.life -= dt; p.vy -= 9.8f * dt
            p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt
            if (p.y < 0.03f) { p.y = 0.03f; p.vy *= -0.3f; p.vx *= 0.6f; p.vz *= 0.6f }
        }
        parts.removeAll { it.life <= 0f }
        if (dead) return

        // --- look: dihaluskan supaya tidak kaku ---
        var dx = 0f; var dy = 0f
        synchronized(lock) { dx = lookX; dy = lookY; lookX = 0f; lookY = 0f }
        fdx = fdx * 0.3f + dx * 0.7f
        fdy = fdy * 0.3f + dy * 0.7f
        yaw += fdx * 0.0045f
        pitch = (pitch - fdy * 0.0045f).coerceIn(-1.3f, 1.3f)

        // --- gerak: akselerasi & peredaman halus ---
        val sy = sin(yaw); val cy = cos(yaw)
        val mx = moveX; val my = moveY
        val len = hypot(mx, my)
        val k = if (len > 1f) 1f / len else 1f
        val ix = mx * k; val iy = my * k
        val tvx = (sy * iy + cy * ix) * 6f
        val tvz = (-cy * iy + sy * ix) * 6f
        val a = 1f - exp(-dt * (if (len > 0.05f) 14f else 10f))
        vx += (tvx - vx) * a
        vz += (tvz - vz) * a
        push(px + vx * dt, pz + vz * dt, 0.4f)
        px = tx; pz = tz
        speedNow = hypot(vx, vz)
        bob += speedNow * dt * 1.7f

        // --- senjata ---
        cooldown -= dt
        if (reloading) {
            reloadT -= dt
            if (reloadT <= 0f) { reloading = false; ammo = 30 }
        } else if ((reloadReq && ammo < 30) || ammo == 0) {
            reloading = true; reloadT = 1.4f; sfx.reload()
        }
        reloadReq = false
        if (firing && !reloading && ammo > 0 && cooldown <= 0f) shoot()

        // --- musuh ---
        for (e in enemies) {
            val dxp = px - e.x; val dzp = pz - e.z
            val d = max(hypot(dxp, dzp), 0.001f)
            e.yaw = atan2(dxp, dzp)
            var nx = e.x; var nz = e.z
            if (d > 1.1f) {
                val step = e.speed * dt
                nx += dxp / d * step; nz += dzp / d * step
                e.phase += e.speed * dt * 2.2f
            }
            for (o in enemies) {
                if (o === e) continue
                val ddx = nx - o.x; val ddz = nz - o.z
                val dd2 = ddx * ddx + ddz * ddz
                if (dd2 < 0.9f && dd2 > 1e-4f) {
                    val dd = sqrt(dd2); val pp = (0.95f - dd) * 0.5f
                    nx += ddx / dd * pp; nz += ddz / dd * pp
                }
            }
            push(nx, nz, 0.45f)
            e.x = tx; e.z = tz
            e.atk -= dt
            e.flash = max(0f, e.flash - dt)
            if (d < 1.4f && e.atk <= 0f) { e.atk = 0.8f; damage(10f) }
        }
        alive = enemies.size

        if (enemies.isEmpty()) {
            nextWave -= dt
            if (nextWave <= 0f) {
                wave++
                if (wave > 1) { hp = min(100f, hp + 20f); ammo = 30; reloading = false }
                spawnWave()
                banner = 2.2f
                nextWave = 2f
            }
        }
    }
}
