package cn.musicpilgrimage.app.ui.room

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.os.Handler
import android.os.HandlerThread
import android.opengl.EGL14.*
import android.opengl.EGLDisplay
import android.opengl.EGLContext
import android.opengl.EGLSurface
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.musicpilgrimage.app.data.Place
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

internal data class GlobeCamera(val longitude:Float=35f,val latitude:Float=24f,val zoom:Float=1f) {
    fun bounded()=GlobeCamera(if(longitude.isFinite())((longitude+180f)%360f+360f)%360f-180f else 35f,
        if(latitude.isFinite())latitude.coerceIn(-80f,80f)else 24f,if(zoom.isFinite())zoom.coerceIn(.8f,2.4f)else 1f)
}
internal data class GlobePoint(val x:Float,val y:Float,val depth:Float)
/** This exact rotation is also used by the sphere shader, including dateline wrapping. */
internal fun globeProject(latitude:Double,longitude:Double,camera:GlobeCamera):GlobePoint {
    val lat=Math.toRadians(latitude);val lon=Math.toRadians(longitude-camera.longitude)
    val tilt=Math.toRadians(camera.latitude.toDouble())
    val x=cos(lat)*sin(lon);val y=sin(lat);val z=cos(lat)*cos(lon)
    return GlobePoint(x.toFloat(),(cos(tilt)*y-sin(tilt)*z).toFloat(),(sin(tilt)*y+cos(tilt)*z).toFloat())
}

@Composable internal fun RoomInteractiveGlobe(places:List<Place>,camera:GlobeCamera,selected:String,onCamera:(GlobeCamera)->Unit,
    onPlaces:(List<Place>)->Unit,modifier:Modifier=Modifier) {
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val latestCamera by rememberUpdatedState(onCamera)
    val value by rememberUpdatedState(camera)
    val density=LocalDensity.current
    BoxWithConstraints(modifier.clipToBounds().testTag("world-globe").semantics{
        contentDescription="音乐地点地球，可拖动旋转、双指缩放，或使用缩放按钮"
        customActions=listOf(CustomAccessibilityAction("向东旋转"){latestCamera(value.copy(longitude=value.longitude+30f).bounded());true},
            CustomAccessibilityAction("向西旋转"){latestCamera(value.copy(longitude=value.longitude-30f).bounded());true})
    }
        .pointerInput(Unit){detectTransformGestures{_,pan,zoom,_->
            val radius=min(size.width,size.height)*.43f*value.zoom
            latestCamera(value.copy(longitude=value.longitude-pan.x/radius*75f,latitude=value.latitude+pan.y/radius*75f,zoom=value.zoom*zoom).bounded())
        }}) {
        val width=with(density){maxWidth.toPx()};val height=with(density){maxHeight.toPx()}
        val radius=min(width,height)*.43f*camera.zoom
        var surface by remember{mutableStateOf<GlobeSurface?>(null)}
        AndroidView(factory={GlobeSurface(it).also{view->surface=view}},update={it.scene(camera)},onRelease={it.release()},modifier=Modifier.fillMaxSize())
        DisposableEffect(surface,lifecycle){
            val observer=LifecycleEventObserver{_,event->when(event){Lifecycle.Event.ON_RESUME->surface?.onResume();Lifecycle.Event.ON_PAUSE->surface?.onPause();else->Unit}}
            lifecycle.addObserver(observer)
            if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))surface?.onResume()
            onDispose{lifecycle.removeObserver(observer);surface?.onPause()}
        }
        val target=with(density){48.dp.toPx()}
        fun hitX(point:GlobePoint)=(width/2+point.x*radius).coerceIn(target/2,width-target/2)
        fun hitY(point:GlobePoint)=(height/2-point.y*radius).coerceIn(target/2,height-target/2)
        val projected=places.map{it to globeProject(it.latitude,it.longitude,camera)}.filter{(_,p)->p.depth>.08f&&width/2+p.x*radius in 0f..width&&height/2-p.y*radius in 0f..height}
        // Nearby real places share one hit target at global scale; zoom or choose the list to separate them.
        val groups=mutableListOf<MutableList<Pair<Place,GlobePoint>>>()
        projected.sortedWith(compareByDescending<Pair<Place,GlobePoint>>{it.first.id==selected}.thenByDescending{it.second.depth}).forEach{mark->
            val group=groups.find{g->val p=g.first().second;abs(hitX(p)-hitX(mark.second))<target&&abs(hitY(p)-hitY(mark.second))<target}
            if(group==null)groups.add(mutableListOf(mark))else group.add(mark)
        }
        groups.sortedBy{g->g.any{it.first.id==selected}}.forEach{group->
            val mark=group.find{it.first.id==selected}?:group.first();val point=mark.second
            val x=width/2+point.x*radius;val y=height/2-point.y*radius
            val centreX=hitX(point);val centreY=hitY(point)
            if(x in 0f..width&&y in 0f..height)Box(Modifier.offset{IntOffset((centreX-target/2).roundToInt(),(centreY-target/2).roundToInt())}.size(48.dp)
                .testTag("globe-pin-${mark.first.id}").semantics{contentDescription=if(group.size==1)mark.first.name else "${mark.first.city}附近${group.size}个音乐地点"}
                .clickable(role=Role.Button){onPlaces(group.map{it.first})},contentAlignment=Alignment.Center) {
                val pinOffset=Modifier.offset{IntOffset((x-centreX).roundToInt(),(y-centreY).roundToInt())}
                Icon(Icons.Outlined.LocationOn,null,pinOffset.size(if(mark.first.id==selected)26.dp else 22.dp),tint=Color(0xFFF4E6C5))
                Icon(Icons.Outlined.LocationOn,null,pinOffset.size(if(mark.first.id==selected)21.dp else 17.dp),tint=SwissRed)
            }
        }
    }
}

/** TextureView participates in Compose's alpha composition; SurfaceView left an opaque hole. */
private class GlobeSurface(context:Context):TextureView(context),TextureView.SurfaceTextureListener {
    private val globeRenderer=GlobeRenderer(context.applicationContext)
    private val thread=HandlerThread("music-earth").apply{start()}
    private val handler=Handler(thread.looper)
    private var display:EGLDisplay=EGL_NO_DISPLAY
    private var eglContext:EGLContext=EGL_NO_CONTEXT
    private var eglSurface:EGLSurface=EGL_NO_SURFACE
    private var config:android.opengl.EGLConfig?=null
    private val pending=java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var active=true
    @Volatile private var released=false
    init {
        isOpaque=false
        surfaceTextureListener=this
    }
    fun scene(camera:GlobeCamera){globeRenderer.camera=camera;requestRender()}
    fun onPause(){active=false}
    fun onResume(){active=true;requestRender()}
    fun release(){released=true;surfaceTextureListener=null;handler.post{destroy();thread.quitSafely()}}
    private fun requestRender(){
        if(released||!pending.compareAndSet(false,true))return
        handler.post{pending.set(false);if(active&&display!=EGL_NO_DISPLAY){globeRenderer.onDrawFrame(null);eglSwapBuffers(display,eglSurface)}}
    }
    override fun onSurfaceTextureAvailable(surface:SurfaceTexture,width:Int,height:Int) {
        handler.post {
            if(released)return@post
            display=eglGetDisplay(EGL_DEFAULT_DISPLAY);check(eglInitialize(display,IntArray(2),0,IntArray(2),1))
            val configs=arrayOfNulls<android.opengl.EGLConfig>(1);val count=IntArray(1)
            check(eglChooseConfig(display,intArrayOf(EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,EGL_NONE),0,configs,0,1,count,0)&&count[0]>0)
            config=configs[0]
            surface.setDefaultBufferSize(width,height)
            eglContext=eglCreateContext(display,config,EGL_NO_CONTEXT,intArrayOf(EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE),0)
            eglSurface=eglCreateWindowSurface(display,config,surface,intArrayOf(EGL_NONE),0)
            check(eglMakeCurrent(display,eglSurface,eglSurface,eglContext))
            globeRenderer.onSurfaceCreated(null,null);globeRenderer.onSurfaceChanged(null,width,height)
            globeRenderer.onDrawFrame(null);eglSwapBuffers(display,eglSurface)
        }
    }
    override fun onSurfaceTextureSizeChanged(surface:SurfaceTexture,width:Int,height:Int){handler.post{if(display!=EGL_NO_DISPLAY){
        // The previous EGL window can retain its old buffer dimensions after a summary opens.
        // Recreate just the window surface, retaining the context/texture and true sphere aspect.
        eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT)
        eglDestroySurface(display,eglSurface);surface.setDefaultBufferSize(width,height)
        eglSurface=eglCreateWindowSurface(display,config,surface,intArrayOf(EGL_NONE),0)
        check(eglMakeCurrent(display,eglSurface,eglSurface,eglContext))
        globeRenderer.onSurfaceChanged(null,width,height);globeRenderer.onDrawFrame(null);eglSwapBuffers(display,eglSurface)
    }}}
    override fun onSurfaceTextureDestroyed(surface:SurfaceTexture):Boolean {handler.post{destroy();surface.release()};return false}
    override fun onSurfaceTextureUpdated(surface:SurfaceTexture)=Unit
    private fun destroy(){
        if(display!=EGL_NO_DISPLAY){
            eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT)
            eglDestroySurface(display,eglSurface);eglDestroyContext(display,eglContext);eglTerminate(display)
            display=EGL_NO_DISPLAY;eglContext=EGL_NO_CONTEXT;eglSurface=EGL_NO_SURFACE
        }
    }
}

/** Actual textured sphere geometry; no screen-space rotation of a pre-rendered Earth. */
private class GlobeRenderer(private val context:Context):GLSurfaceView.Renderer {
    @Volatile var camera=GlobeCamera()
    private var program=0;private var texture=0;private var width=1;private var height=1
    private val mesh=buildMesh()
    override fun onSurfaceCreated(gl:GL10?,config:EGLConfig?) {
        fun shader(type:Int,source:String):Int=glCreateShader(type).also{id->
            glShaderSource(id,source);glCompileShader(id);val ok=IntArray(1);glGetShaderiv(id,GL_COMPILE_STATUS,ok,0);check(ok[0]!=0){glGetShaderInfoLog(id)}
        }
        program=glCreateProgram()
        val vertex=shader(GL_VERTEX_SHADER,"""
            attribute vec3 aPoint; attribute vec2 aUv; uniform vec4 uTurn; uniform vec2 uScale;
            varying vec2 vUv; varying vec3 vNormal;
            void main(){
              float x=uTurn.x*aPoint.x-uTurn.y*aPoint.z;
              float z=uTurn.y*aPoint.x+uTurn.x*aPoint.z;
              vec3 p=vec3(x,uTurn.z*aPoint.y-uTurn.w*z,uTurn.w*aPoint.y+uTurn.z*z);
              vNormal=p;vUv=aUv;gl_Position=vec4(p.xy*uScale,-p.z*0.5,1.0);
            }
        """.trimIndent())
        val fragment=shader(GL_FRAGMENT_SHADER,"""
            precision mediump float; uniform sampler2D uEarth; varying vec2 vUv; varying vec3 vNormal;
            void main(){
              if(vNormal.z<0.0)discard;
              vec3 n=normalize(vNormal);vec3 color=texture2D(uEarth,vUv).rgb;
              float gray=dot(color,vec3(0.2126,0.7152,0.0722));color=mix(vec3(gray),color,0.74);
              float light=0.69+0.34*max(dot(n,normalize(vec3(-0.4,0.55,1.0))),0.0);
              color=pow(color,vec3(0.88))*light;
              float rim=pow(1.0-n.z,4.0);color=mix(color,vec3(0.35,0.51,0.61),rim*0.16);
              gl_FragColor=vec4(color,1.0);
            }
        """.trimIndent())
        glAttachShader(program,vertex);glAttachShader(program,fragment);glLinkProgram(program)
        val ok=IntArray(1);glGetProgramiv(program,GL_LINK_STATUS,ok,0);check(ok[0]!=0){glGetProgramInfoLog(program)}
        glDeleteShader(vertex);glDeleteShader(fragment)
        val ids=IntArray(1);glGenTextures(1,ids,0);texture=ids[0];glBindTexture(GL_TEXTURE_2D,texture)
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE)
        val limit=IntArray(1);glGetIntegerv(GL_MAX_TEXTURE_SIZE,limit,0)
        val options=BitmapFactory.Options().apply{inSampleSize=if(limit[0]>=4096)2 else 4}
        context.assets.open("design/music-space/earth-global.jpg").use{input->
            val bitmap=checkNotNull(BitmapFactory.decodeStream(input,null,options));GLUtils.texImage2D(GL_TEXTURE_2D,0,bitmap,0);bitmap.recycle()
        }
        glClearColor(0f,0f,0f,0f)
    }
    override fun onSurfaceChanged(gl:GL10?,w:Int,h:Int){width=w.coerceAtLeast(1);height=h.coerceAtLeast(1);glViewport(0,0,w,h)}
    override fun onDrawFrame(gl:GL10?) {
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT);glUseProgram(program)
        val view=camera;val lon=Math.toRadians(view.longitude.toDouble());val lat=Math.toRadians(view.latitude.toDouble())
        glUniform4f(glGetUniformLocation(program,"uTurn"),cos(lon).toFloat(),sin(lon).toFloat(),cos(lat).toFloat(),sin(lat).toFloat())
        val radius=min(width,height)*.43f*view.zoom
        glUniform2f(glGetUniformLocation(program,"uScale"),radius*2/width,radius*2/height)
        glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,texture);glUniform1i(glGetUniformLocation(program,"uEarth"),0)
        val point=glGetAttribLocation(program,"aPoint");val uv=glGetAttribLocation(program,"aUv")
        mesh.position(0);glVertexAttribPointer(point,3,GL_FLOAT,false,20,mesh);glEnableVertexAttribArray(point)
        mesh.position(3);glVertexAttribPointer(uv,2,GL_FLOAT,false,20,mesh);glEnableVertexAttribArray(uv)
        glDrawArrays(GL_TRIANGLES,0,mesh.capacity()/5)
    }
    private fun buildMesh():java.nio.FloatBuffer {
        val bands=64;val sectors=128;val values=FloatArray(bands*sectors*6*5);var at=0
        fun point(row:Int,col:Int){
            val lat=PI/2-PI*row/bands;val lon=-PI+2*PI*col/sectors
            values[at++]=(cos(lat)*sin(lon)).toFloat();values[at++]=sin(lat).toFloat();values[at++]=(cos(lat)*cos(lon)).toFloat()
            values[at++]=col.toFloat()/sectors;values[at++]=row.toFloat()/bands
        }
        for(row in 0 until bands)for(col in 0 until sectors){point(row,col);point(row+1,col);point(row,col+1);point(row,col+1);point(row+1,col);point(row+1,col+1)}
        return ByteBuffer.allocateDirect(values.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply{put(values);position(0)}
    }
}
