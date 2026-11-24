package com.fpsgame.arena

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Efek suara dibuat otomatis lewat kode (tanpa file aset). */
class Sfx {
    private val rate = 22050
    private val tau = (2 * PI).toFloat()

    private fun gen(sec: Float, f: (Float, Float) -> Float): ShortArray {
        val n = (rate * sec).toInt()
        return ShortArray(n) { i ->
            val t = i / rate.toFloat()
            (f(t, t / sec).coerceIn(-1f, 1f) * 30000f).toInt().toShort()
        }
    }

    private fun build(data: ShortArray): AudioTrack? = try {
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setBufferSizeInBytes(data.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(data, 0, data.size)
        t
    } catch (e: Exception) { null }

    private val shotT = build(gen(0.18f) { t, p ->
        ((Random.nextFloat() * 2 - 1) * 0.8f + sin(t * tau * 90f * (1f - p)) * 0.6f) * (1f - p) * (1f - p)
    })
    private val hitT = build(gen(0.06f) { t, p -> sin(t * tau * 900f) * 0.4f * (1f - p) })
    private val killT = build(gen(0.25f) { t, p -> sin(t * tau * (500f - 380f * p)) * 0.5f * (1f - p) })
    private val hurtT = build(gen(0.2f) { t, p ->
        (if (sin(t * tau * 70f) > 0f) 0.5f else -0.5f) * (1f - p)
    })
    private val reloadT = build(gen(0.05f) { _, p -> (Random.nextFloat() * 2 - 1) * 0.5f * (1f - p) })

    private fun play(t: AudioTrack?) {
        try {
            t?.let { it.stop(); it.setPlaybackHeadPosition(0); it.play() }
        } catch (e: Exception) { }
    }

    fun shot() = play(shotT)
    fun hit() = play(hitT)
    fun kill() = play(killT)
    fun hurt() = play(hurtT)
    fun reload() = play(reloadT)

    fun release() {
        listOf(shotT, hitT, killT, hurtT, reloadT).forEach { try { it?.release() } catch (e: Exception) { } }
    }
}
