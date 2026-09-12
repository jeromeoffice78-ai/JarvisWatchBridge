package com.jarvis.watchbridge.ui

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class NativeAvatar3DView(context: Context) : GLSurfaceView(context) {
    private val avatarRenderer = AvatarRenderer()
    private var lastX = 0f

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(avatarRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun setCharacter(character: Int, speaking: Boolean) {
        avatarRenderer.character = character.coerceIn(0, 7)
        avatarRenderer.speaking = speaking
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_MOVE) {
            avatarRenderer.userYaw += (event.x - lastX) * .012f
        }
        lastX = event.x
        return true
    }
}

private class AvatarRenderer : GLSurfaceView.Renderer {
    @Volatile var character = 0
    @Volatile var speaking = false
    @Volatile var userYaw = 0f
    private var program = 0
    private var positionHandle = 0
    private var matrixHandle = 0
    private var colorHandle = 0
    private lateinit var sphere: java.nio.FloatBuffer
    private var vertexCount = 0
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val mv = FloatArray(16)
    private val mvp = FloatArray(16)
    private val start = System.nanoTime()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(.025f, .055f, .09f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        program = makeProgram(
            "attribute vec3 aPosition; uniform mat4 uMvp; varying float light; void main(){vec3 n=normalize(aPosition);light=.4+.6*max(0.0,dot(n,normalize(vec3(-.3,.8,-.6))));gl_Position=uMvp*vec4(aPosition,1.0);}",
            "precision mediump float; uniform vec4 uColor; varying float light; void main(){gl_FragColor=vec4(uColor.rgb*light,uColor.a);}"
        )
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        matrixHandle = GLES20.glGetUniformLocation(program, "uMvp")
        colorHandle = GLES20.glGetUniformLocation(program, "uColor")
        buildSphere()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        Matrix.perspectiveM(projection, 0, 42f, width.toFloat() / height.coerceAtLeast(1), .1f, 30f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)
        val t = (System.nanoTime() - start) / 1_000_000_000f
        val yaw = userYaw + sin(t * .7f) * .12f
        Matrix.setLookAtM(view, 0, 0f, .25f, 7.2f, 0f, .25f, 0f, 0f, 1f, 0f)
        val skin = palette(character, false)
        val suit = palette(character, true)
        part(0f, .25f, 0f, 1.15f, 1.25f, .48f, yaw, suit)
        part(0f, 1.72f, 0f, .66f, .78f, .62f, yaw, skin)
        part(0f, 2.25f, .03f, .7f, .32f, .64f, yaw, if (character == 0) floatArrayOf(.05f,.45f,.7f,1f) else floatArrayOf(.08f,.05f,.04f,1f))
        part(-.73f, .2f, 0f, .25f, 1.18f, .25f, yaw + .08f, suit)
        part(.73f, .2f, 0f, .25f, 1.18f, .25f, yaw - .08f, suit)
        part(-.36f, -1.15f, 0f, .31f, 1.35f, .34f, yaw, suit)
        part(.36f, -1.15f, 0f, .31f, 1.35f, .34f, yaw, suit)
        val jawOpen = if (speaking) (.06f + .08f * (sin(t * 16f) * .5f + .5f)) else .025f
        part(0f, 1.48f - jawOpen, .56f, .28f, jawOpen, .08f, yaw, floatArrayOf(.16f,.02f,.04f,1f))
        val eye = if (character == 0) floatArrayOf(.1f,.85f,1f,1f) else floatArrayOf(.9f,.95f,1f,1f)
        part(-.23f, 1.86f, .57f, .09f, .045f, .035f, yaw, eye)
        part(.23f, 1.86f, .57f, .09f, .045f, .035f, yaw, eye)
    }

    private fun part(x:Float,y:Float,z:Float,sx:Float,sy:Float,sz:Float,yaw:Float,color:FloatArray){
        Matrix.setIdentityM(model,0)
        Matrix.translateM(model,0,x,y,z)
        Matrix.rotateM(model,0,yaw*57.2958f,0f,1f,0f)
        Matrix.scaleM(model,0,sx,sy,sz)
        Matrix.multiplyMM(mv,0,view,0,model,0)
        Matrix.multiplyMM(mvp,0,projection,0,mv,0)
        GLES20.glUniformMatrix4fv(matrixHandle,1,false,mvp,0)
        GLES20.glUniform4fv(colorHandle,1,color,0)
        sphere.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle,3,GLES20.GL_FLOAT,false,12,sphere)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,vertexCount)
    }

    private fun palette(k:Int,suit:Boolean):FloatArray{
        if(suit) return arrayOf(.08f,.09f,.13f,.12f,.08f,.16f,.13f,.10f,.08f,.07f,.12f,.16f,.18f,.06f,.12f,.10f,.11f,.13f,.07f,.07f,.08f,.05f,.14f,.18f).let{a->floatArrayOf(a[k*3],a[k*3+1],a[k*3+2],1f)}
        val a=arrayOf(.10f,.25f,.34f,.42f,.23f,.16f,.34f,.18f,.13f,.68f,.39f,.26f,.78f,.57f,.40f,.55f,.33f,.23f,.76f,.58f,.43f,.72f,.43f,.30f)
        return floatArrayOf(a[k*3],a[k*3+1],a[k*3+2],1f)
    }

    private fun buildSphere(){
        val v=ArrayList<Float>();val rings=18;val slices=24
        fun add(lat:Double,lon:Double){v+= (sin(lat)*cos(lon)).toFloat();v+=cos(lat).toFloat();v+=(sin(lat)*sin(lon)).toFloat()}
        for(i in 0 until rings)for(j in 0 until slices){val a=PI*i/rings;val b=PI*(i+1)/rings;val c=2*PI*j/slices;val d=2*PI*(j+1)/slices;add(a,c);add(b,c);add(b,d);add(a,c);add(b,d);add(a,d)}
        vertexCount=v.size/3;sphere=ByteBuffer.allocateDirect(v.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer();v.forEach{sphere.put(it)};sphere.position(0)
    }

    private fun makeProgram(v:String,f:String):Int{fun shader(type:Int,s:String):Int{val id=GLES20.glCreateShader(type);GLES20.glShaderSource(id,s);GLES20.glCompileShader(id);return id};val p=GLES20.glCreateProgram();GLES20.glAttachShader(p,shader(GLES20.GL_VERTEX_SHADER,v));GLES20.glAttachShader(p,shader(GLES20.GL_FRAGMENT_SHADER,f));GLES20.glLinkProgram(p);return p}
}
