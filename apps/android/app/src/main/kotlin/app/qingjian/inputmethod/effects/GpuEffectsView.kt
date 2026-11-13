package app.qingjian.inputmethod.effects

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Shared GPU ripple layer for Android IME surfaces.
 * The layer is transparent, touch-through and renders only while an effect is alive.
 */
object GpuEffectBus {
    @Volatile internal var sink: ((Float, Float, Float) -> Unit)? = null

    fun pulse(screenX: Float, screenY: Float, strength: Float = 0.72f) {
        sink?.invoke(screenX, screenY, strength.coerceIn(0f, 1.5f))
    }
}

class GpuEffectsView(context: Context) : GLSurfaceView(context) {
    private val ticker = Handler(Looper.getMainLooper())
    private var activeUntil = 0L
    private val frame = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() <= activeUntil) {
                requestRender()
                ticker.postDelayed(this, FRAME_MS)
            } else {
                requestRender()
            }
        }
    }

    private val effectRenderer = EffectRenderer()

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        setRenderer(effectRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        isClickable = false
        isFocusable = false
        GpuEffectBus.sink = { x, y, strength -> triggerScreen(x, y, strength) }
    }

    fun triggerScreen(screenX: Float, screenY: Float, strength: Float) {
        val location = IntArray(2)
        getLocationOnScreen(location)
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val x = ((screenX - location[0]) / w).coerceIn(0f, 1f)
        val y = (1f - (screenY - location[1]) / h).coerceIn(0f, 1f)
        effectRenderer.update(x, y, strength)
        activeUntil = System.currentTimeMillis() + EFFECT_DURATION_MS
        ticker.removeCallbacks(frame)
        ticker.post(frame)
    }

    override fun onDetachedFromWindow() {
        ticker.removeCallbacks(frame)
        if (GpuEffectBus.sink != null) GpuEffectBus.sink = null
        super.onDetachedFromWindow()
    }

    private class EffectRenderer : Renderer {
        private var program = 0
        private var position = 0
        private var timeLocation = 0
        private var centerLocation = 0
        private var strengthLocation = 0
        private var resolutionLocation = 0
        private var startNanos = System.nanoTime()
        @Volatile private var centerX = 0.5f
        @Volatile private var centerY = 0.5f
        @Volatile private var strength = 0f
        private val vertices: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
                position(0)
            }

        fun update(x: Float, y: Float, amount: Float) {
            centerX = x
            centerY = y
            strength = amount
            startNanos = System.nanoTime()
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
            val vertex = """
                attribute vec2 aPosition;
                varying vec2 vUv;
                void main() {
                    vUv = aPosition * 0.5 + 0.5;
                    gl_Position = vec4(aPosition, 0.0, 1.0);
                }
            """.trimIndent()
            val fragment = """
                precision mediump float;
                varying vec2 vUv;
                uniform float uTime;
                uniform vec2 uCenter;
                uniform vec2 uResolution;
                uniform float uStrength;
                float hash(vec2 p) {
                    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
                }
                void main() {
                    vec2 delta = (vUv - uCenter) * vec2(uResolution.x / max(uResolution.y, 1.0), 1.0);
                    float r = length(delta);
                    float life = exp(-uTime * 2.35);
                    float wave1 = exp(-pow((r - uTime * 0.72) * 22.0, 2.0));
                    float wave2 = exp(-pow((r - uTime * 0.48 - 0.035) * 29.0, 2.0));
                    float core = exp(-r * 13.0) * exp(-uTime * 5.0);
                    float rings = (wave1 * 0.72 + wave2 * 0.38) * life;
                    float angle = atan(delta.y, delta.x);
                    float shimmer = 0.82 + 0.18 * sin(angle * 7.0 - uTime * 5.0);
                    vec3 cyan = vec3(0.10, 0.88, 1.0);
                    vec3 violet = vec3(0.48, 0.24, 1.0);
                    vec3 pink = vec3(1.0, 0.22, 0.72);
                    float mixWave = 0.5 + 0.5 * sin(angle * 1.7 + uTime * 1.8);
                    vec3 color = mix(cyan, violet, mixWave);
                    color = mix(color, pink, clamp(rings * 0.55, 0.0, 0.7));
                    vec2 cell = floor(vUv * uResolution / 18.0);
                    float star = step(0.992, hash(cell)) * exp(-r * 2.8) * life;
                    float alpha = (rings * 0.46 + core * 0.24 + star * 0.65) * shimmer * uStrength;
                    gl_FragColor = vec4(color, clamp(alpha, 0.0, 0.58));
                }
            """.trimIndent()
            program = try {
                link(compile(GLES20.GL_VERTEX_SHADER, vertex), compile(GLES20.GL_FRAGMENT_SHADER, fragment))
            } catch (_: RuntimeException) {
                0 // Shader failure disables decoration only; the IME input path remains usable.
            }
            position = GLES20.glGetAttribLocation(program, "aPosition")
            timeLocation = GLES20.glGetUniformLocation(program, "uTime")
            centerLocation = GLES20.glGetUniformLocation(program, "uCenter")
            strengthLocation = GLES20.glGetUniformLocation(program, "uStrength")
            resolutionLocation = GLES20.glGetUniformLocation(program, "uResolution")
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (program == 0) return
            val elapsed = ((System.nanoTime() - startNanos) / 1_000_000_000.0).toFloat()
            if (elapsed > 0.95f) return
            GLES20.glUseProgram(program)
            GLES20.glEnableVertexAttribArray(position)
            vertices.position(0)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices)
            GLES20.glUniform1f(timeLocation, elapsed)
            GLES20.glUniform2f(centerLocation, centerX, centerY)
            GLES20.glUniform2f(resolutionLocation, width.toFloat(), height.toFloat())
            GLES20.glUniform1f(strengthLocation, strength)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(position)
        }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }
            return shader
        }

        private fun link(vertex: Int, fragment: Int): Int {
            val result = GLES20.glCreateProgram()
            GLES20.glAttachShader(result, vertex)
            GLES20.glAttachShader(result, fragment)
            GLES20.glLinkProgram(result)
            val status = IntArray(1)
            GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(result) }
            return result
        }
    }

    private companion object {
        const val FRAME_MS = 16L
        const val EFFECT_DURATION_MS = 950L
    }
}
