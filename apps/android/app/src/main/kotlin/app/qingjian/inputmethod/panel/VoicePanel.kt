package app.qingjian.inputmethod.panel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import android.widget.FrameLayout
import app.qingjian.inputmethod.keyboard.Haptics
import app.qingjian.inputmethod.panel.base.Ink
import app.qingjian.inputmethod.theme.Palette
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import android.opengl.GLSurfaceView.Renderer

/**
 * GPU-rendered 3D audio fluid sphere.
 *
 * The former Canvas sphere/equalizer implementation is intentionally removed.
 * The visual is rebuilt with OpenGL ES 2.0 shaders, based on the supplied
 * Three.js vertex/fragment shader. Voice capture and recognition stay outside
 * this view and continue to feed addLevel().
 */
@SuppressLint("ViewConstructor")
class VoicePanel(
    context: Context,
    private val palette: Palette,
    private val actionLabel: String,
    private val actions: Actions,
) : FrameLayout(context) {

    interface Actions {
        fun onCancel()
        fun onFinish(act: Boolean, hide: Boolean)
    }

    enum class Target { NONE, CANCEL, ACTION }

    private val ink = Ink(palette, resources.displayMetrics.density)
    private val overlay = OverlayView(context)
    private val glView = FluidSphereSurface(context) { overlay.postInvalidateOnAnimation() }
    private val touchLocation = IntArray(2)

    private var touchTarget = Target.NONE
    var target = Target.NONE
        private set

    var holding = false
        set(value) {
            field = value
            glView.renderer.setActive(value || listening || recognizing)
            overlay.invalidate()
        }

    var listening = false
        set(value) {
            field = value
            glView.renderer.setActive(value || holding || recognizing)
            overlay.invalidate()
        }

    var recognizing = false
        set(value) {
            field = value
            glView.renderer.setActive(value || holding || listening)
            overlay.invalidate()
        }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        clipChildren = true
        clipToPadding = true

        // Keep the GL surface in the normal SurfaceView layer. The translucent
        // holder is sufficient; media-overlay ordering can crash on some devices.
        glView.setZOrderOnTop(false)
        glView.setBackgroundColor(Color.TRANSPARENT)
        addView(glView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        overlay.isClickable = false
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun addLevel(level: Float) {
        glView.renderer.addLevel(level.coerceIn(0f, 1f))
    }

    fun track(screenX: Float, screenY: Float) {
        getLocationOnScreen(touchLocation)
        val next = targetAt(screenX - touchLocation[0], screenY - touchLocation[1])
        if (next == target) return
        target = next
        if (next != Target.NONE) Haptics.tap(context)
        overlay.invalidate()
    }

    private fun targetAt(x: Float, y: Float): Target {
        if (y < height * 0.77f || y > height * 0.96f) return Target.NONE
        return when {
            x < width * 0.34f -> Target.CANCEL
            x > width * 0.66f -> Target.ACTION
            else -> Target.NONE
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val micRadius = 34f * resources.displayMetrics.density
        val micHit = kotlin.math.hypot(x - width / 2f, y - height * 0.475f) <= micRadius + 18f * resources.displayMetrics.density
        val action = targetAt(x, y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchTarget = when {
                    micHit -> Target.NONE
                    action != Target.NONE -> action
                    else -> return super.dispatchTouchEvent(event)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> return touchTarget != Target.NONE
            MotionEvent.ACTION_UP -> {
                when {
                    touchTarget == Target.CANCEL || action == Target.CANCEL -> actions.onCancel()
                    touchTarget == Target.ACTION || action == Target.ACTION ->
                        actions.onFinish(act = true, hide = false)
                    touchTarget == Target.NONE && micHit ->
                        actions.onFinish(act = false, hide = false)
                    else -> {
                        touchTarget = Target.NONE
                        return super.dispatchTouchEvent(event)
                    }
                }
                touchTarget = Target.NONE
                Haptics.tap(context)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                touchTarget = Target.NONE
                return true
            }
        }
        return touchTarget != Target.NONE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        glView.onResume()
    }

    override fun onDetachedFromWindow() {
        glView.onPause()
        super.onDetachedFromWindow()
    }

    private inner class OverlayView(context: Context) : android.view.View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val micRect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0) return
            val cx = width * 0.5f
            val cy = height * 0.475f
            val d = resources.displayMetrics.density

            // A restrained center microphone glyph keeps the voice affordance clear
            // without covering the shader-driven fluid surface.
            val r = 12f * d
            p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.8f * d
            p.strokeCap = Paint.Cap.ROUND
            p.strokeJoin = Paint.Join.ROUND
            p.color = 0xDDF4FCFF.toInt()
            canvas.drawRoundRect(cx - r * 0.34f, cy - r * 0.78f,
                cx + r * 0.34f, cy + r * 0.18f, r * 0.34f, r * 0.34f, p)
            micRect.set(cx - r * 0.68f, cy - r * 0.28f,
                cx + r * 0.68f, cy + r * 0.62f)
            canvas.drawArc(micRect, 0f, 180f, false, p)
            canvas.drawLine(cx, cy + r * 0.62f, cx, cy + r * 0.98f, p)
            canvas.drawLine(cx - r * 0.38f, cy + r * 0.98f,
                cx + r * 0.38f, cy + r * 0.98f, p)

            if (glView.renderer.hasFailed()) {
                val radius = 76f * d
                p.shader = android.graphics.RadialGradient(
                    cx - radius * 0.28f, cy - radius * 0.34f, radius * 1.35f,
                    intArrayOf(0xFFF4FFFF.toInt(), 0xFF39D9FF.toInt(), 0xFF655BFF.toInt(), 0x00A020FF),
                    floatArrayOf(0f, 0.28f, 0.72f, 1f), android.graphics.Shader.TileMode.CLAMP,
                )
                p.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, radius, p)
                p.shader = null
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.2f * d
                p.color = 0xB8A9E9FF.toInt()
                canvas.drawCircle(cx, cy, radius * 1.08f, p)
                p.strokeWidth = 1f * d
                p.color = 0x8865DFFF.toInt()
                canvas.drawOval(cx - radius * 1.2f, cy - radius * 0.42f, cx + radius * 1.2f, cy + radius * 0.42f, p)
                p.style = Paint.Style.FILL
            }

            val actionY = height * 0.91f
            ink.text(canvas, "撤回", 13f,
                if (target == Target.CANCEL) palette.brand else 0xFFD6E8F5.toInt(),
                width * 0.17f, actionY, bold = target == Target.CANCEL)
            ink.text(canvas, actionLabel, 13f,
                if (target == Target.ACTION) palette.brand else 0xFFD6E8F5.toInt(),
                width * 0.83f, actionY, bold = target == Target.ACTION)
        }
    }

    private class FluidSphereSurface(context: Context, onFailure: () -> Unit) : GLSurfaceView(context) {
        val renderer = FluidSphereRenderer(onFailure)

        init {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            setBackgroundColor(Color.TRANSPARENT)
            setRenderer(renderer)
            renderMode = RENDERMODE_CONTINUOUSLY
            isFocusable = false
            isFocusableInTouchMode = false
        }
    }

    private class FluidSphereRenderer(private val onFailure: () -> Unit) : Renderer {
        @Volatile private var failed = false
        fun hasFailed(): Boolean = failed
        private fun failSafely(error: Throwable) {
            if (failed) return
            failed = true
            android.util.Log.e("QingjianVoice", "OpenGL voice effect disabled; using Canvas fallback", error)
            onFailure()
        }
        private val sampleLock = Any()
        private val levels = FloatArray(LEVEL_SAMPLES)
        private var sampleCursor = 0
        @Volatile private var active = false
        private var currentAmplitude = 0f
        private var peakAmplitude = 0f
        private var startNanos = 0L
        private var lastFrameNanos = 0L

        private var program = 0
        private var vertexBuffer: FloatBuffer? = null
        private var indexBuffer: ShortBuffer? = null
        private var indexCount = 0
        private var aPosition = 0
        private var aNormal = 0
        private var uTime = 0
        private var uAudio = 0
        private var uMvp = 0
        private var uModel = 0
        private val projection = FloatArray(16)
        private val view = FloatArray(16)
        private val model = FloatArray(16)
        private val viewModel = FloatArray(16)
        private val mvp = FloatArray(16)

        fun addLevel(level: Float) {
            synchronized(sampleLock) {
                levels[sampleCursor] = level
                sampleCursor = (sampleCursor + 1) % LEVEL_SAMPLES
            }
        }

        fun setActive(value: Boolean) {
            active = value
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            if (failed) return
            try { createSurface(gl, config) } catch (error: Throwable) { failSafely(error) }
        }

        private fun createSurface(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.012f, 0.018f, 0.045f, 0f)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            aPosition = GLES20.glGetAttribLocation(program, "aPosition")
            aNormal = GLES20.glGetAttribLocation(program, "aNormal")
            uTime = GLES20.glGetUniformLocation(program, "uTime")
            uAudio = GLES20.glGetUniformLocation(program, "uAudioAmplitude")
            uMvp = GLES20.glGetUniformLocation(program, "uMvpMatrix")
            uModel = GLES20.glGetUniformLocation(program, "uModelMatrix")
            createSphereWireframe()
            startNanos = System.nanoTime()
            lastFrameNanos = startNanos
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            if (failed) return
            try { changeSurface(gl, width, height) } catch (error: Throwable) { failSafely(error) }
        }

        private fun changeSurface(gl: GL10?, width: Int, height: Int) {
            val safeHeight = max(1, height)
            GLES20.glViewport(0, 0, width, safeHeight)
            Matrix.perspectiveM(projection, 0, 45f, width.toFloat() / safeHeight, 0.1f, 2000f)
            Matrix.setLookAtM(view, 0, 0f, 0f, 1000f, 0f, 0f, 0f, 0f, 1f, 0f)
        }

        override fun onDrawFrame(gl: GL10?) {
            if (failed) return
            try { drawFrame(gl) } catch (error: Throwable) { failSafely(error) }
        }

        private fun drawFrame(gl: GL10?) {
            val now = System.nanoTime()
            val dt = ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            lastFrameNanos = now
            val time = (now - startNanos) / 1_000_000_000f

            var sum = 0f
            var peak = 0f
            synchronized(sampleLock) {
                for (sample in levels) {
                    sum += sample
                    peak = max(peak, sample)
                }
            }
            val average = sum / LEVEL_SAMPLES
            // Input levels are calibrated to 0..1 dB intensity by EngineRecognizer.
            // Keep a fast attack and a slower release so syllables visibly drive the
            // sphere without making the surface jitter between 100 ms audio chunks.
            val target = if (active) max(average * 3.2f, peak * 1.35f).coerceIn(0f, 1f) else 0f
            val attack = 1f - exp(-13f * dt)
            val release = 1f - exp(-3.2f * dt)
            currentAmplitude += (target - currentAmplitude) * if (target > currentAmplitude) attack else release
            peakAmplitude += (((if (active) peak else 0f) - peakAmplitude) *
                (1f - exp(-5.5f * dt)))

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glUniform1f(uTime, time)
            GLES20.glUniform1f(uAudio, currentAmplitude)

            Matrix.setIdentityM(model, 0)
            Matrix.rotateM(model, 0, (time * 5.73f) % 360f, 0f, 1f, 0f)
            Matrix.rotateM(model, 0, sin(time * 0.5f) * 11.46f, 1f, 0f, 0f)
            Matrix.rotateM(model, 0, cos(time * 0.3f) * 5.73f, 0f, 0f, 1f)
            Matrix.multiplyMM(viewModel, 0, view, 0, model, 0)
            Matrix.multiplyMM(mvp, 0, projection, 0, viewModel, 0)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)

            val vb = vertexBuffer ?: return
            val ib = indexBuffer ?: return
            vb.position(0)
            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, STRIDE_BYTES, vb)
            vb.position(3)
            GLES20.glEnableVertexAttribArray(aNormal)
            GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, STRIDE_BYTES, vb)
            ib.position(0)
            GLES20.glLineWidth(1f)
            GLES20.glDrawElements(GLES20.GL_LINES, indexCount, GLES20.GL_UNSIGNED_SHORT, ib)
            GLES20.glDisableVertexAttribArray(aPosition)
            GLES20.glDisableVertexAttribArray(aNormal)
        }

        private fun createSphereWireframe() {
            val vertices = FloatArray((RINGS + 1) * (SEGMENTS + 1) * 6)
            var v = 0
            for (lat in 0..RINGS) {
                val theta = PI * lat / RINGS
                val sinTheta = sin(theta)
                val cosTheta = cos(theta)
                for (lon in 0..SEGMENTS) {
                    val phi = 2.0 * PI * lon / SEGMENTS
                    val nx = (sinTheta * cos(phi)).toFloat()
                    val ny = cosTheta.toFloat()
                    val nz = (sinTheta * sin(phi)).toFloat()
                    vertices[v++] = nx * SPHERE_RADIUS
                    vertices[v++] = ny * SPHERE_RADIUS
                    vertices[v++] = nz * SPHERE_RADIUS
                    vertices[v++] = nx
                    vertices[v++] = ny
                    vertices[v++] = nz
                }
            }

            val indices = ShortArray((RINGS * SEGMENTS * 4 + SEGMENTS * 2) * 2)
            var k = 0
            for (lat in 1 until RINGS) {
                for (lon in 0 until SEGMENTS) {
                    val a = lat * (SEGMENTS + 1) + lon
                    val b = a + 1
                    indices[k++] = a.toShort()
                    indices[k++] = b.toShort()
                }
            }
            for (lon in 0 until SEGMENTS) {
                for (lat in 0 until RINGS) {
                    val a = lat * (SEGMENTS + 1) + lon
                    val b = (lat + 1) * (SEGMENTS + 1) + lon
                    indices[k++] = a.toShort()
                    indices[k++] = b.toShort()
                }
            }
            val vertexBytes = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder())
            vertexBuffer = vertexBytes.asFloatBuffer().apply { put(vertices); position(0) }
            val indexBytes = ByteBuffer.allocateDirect(k * 2).order(ByteOrder.nativeOrder())
            indexBuffer = indexBytes.asShortBuffer().apply { put(indices, 0, k); position(0) }
            indexCount = k
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw IllegalStateException("Voice shader compile failed: $log")
            }
            return shader
        }

        private fun createProgram(vertex: String, fragment: String): Int {
            val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertex)
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment)
            val linked = GLES20.glCreateProgram()
            GLES20.glAttachShader(linked, vs)
            GLES20.glAttachShader(linked, fs)
            GLES20.glLinkProgram(linked)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            val status = IntArray(1)
            GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(linked)
                GLES20.glDeleteProgram(linked)
                throw IllegalStateException("Voice shader link failed: $log")
            }
            return linked
        }

        companion object {
            private const val LEVEL_SAMPLES = 48
            private const val RINGS = 128
            private const val SEGMENTS = 128
            private const val SPHERE_RADIUS = 120f
            private const val STRIDE_BYTES = 6 * 4

            private val VERTEX_SHADER = """
                uniform mat4 uMvpMatrix;
                uniform mat4 uModelMatrix;
                uniform float uTime;
                uniform float uAudioAmplitude;
                attribute vec3 aPosition;
                attribute vec3 aNormal;
                varying vec3 vPosition;
                varying vec3 vNormal;
                void main() {
                    vec3 pos = aPosition;
                    float noise = sin(pos.x * 0.02 + uTime * 2.0)
                                * cos(pos.y * 0.02 + uTime * 1.5)
                                * sin(pos.z * 0.02 + uTime * 2.5);
                    float pulse = 1.0 + uAudioAmplitude * 0.18
                                + sin(uTime * 5.0) * uAudioAmplitude * 0.035;
                    float distortion = noise * (7.0 + uAudioAmplitude * 42.0);
                    float ripple = sin(length(pos.xy) * 0.045 - uTime * 3.0)
                                 * uAudioAmplitude * 7.0;
                    pos += aNormal * (distortion + ripple);
                    pos *= pulse;
                    vPosition = pos;
                    vNormal = normalize(mat3(uModelMatrix) * aNormal);
                    gl_Position = uMvpMatrix * vec4(pos, 1.0);
                }
            """

            private val FRAGMENT_SHADER = """
                precision mediump float;
                uniform float uTime;
                varying vec3 vPosition;
                varying vec3 vNormal;
                void main() {
                    float y = (vPosition.y + 150.0) / 300.0;
                    float x = (vPosition.x + 150.0) / 300.0;
                    vec3 colorTop = vec3(0.05, 1.0, 1.0);
                    vec3 colorLeft = vec3(0.02, 0.24, 1.0);
                    vec3 colorRight = vec3(1.0, 0.025, 0.32);
                    vec3 colorBottom = vec3(1.0, 0.72, 0.12);
                    vec3 mix1 = mix(colorLeft, colorRight, x);
                    vec3 mix2 = mix(colorTop, colorBottom, 1.0 - y);
                    vec3 finalColor = mix(mix1, mix2,
                        0.5 + 0.5 * sin(vPosition.z * 0.01 + uTime));
                    float fresnel = dot(normalize(vNormal), vec3(0.0, 0.0, 1.0));
                    fresnel = pow(1.0 - abs(fresnel), 2.0);
                    float audioGlow = 1.0 + uAudioAmplitude * 1.65;
                    float alpha = mix(0.20, 0.96, fresnel) * (0.88 + uAudioAmplitude * 0.35);
                    finalColor = min(finalColor * audioGlow + fresnel * vec3(0.12, 0.30, 0.52), vec3(1.0));
                    // A sharper neon rim makes the fluid contour readable over the keyboard.
                    float rim = pow(fresnel, 2.4);
                    finalColor += rim * vec3(0.25, 0.65, 1.0) * (0.8 + uAudioAmplitude);
                    gl_FragColor = vec4(finalColor, min(alpha, 1.0));
                }
            """
        }
    }
}
