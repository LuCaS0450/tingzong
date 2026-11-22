package cn.musicpilgrimage.app.ui.room

import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.core.view.descendants

/** Keeps the paused map's buffers when Navigation detaches and reattaches its view. */
internal class WorldMapTexture private constructor(
    private val view: TextureView,
    private val renderer: TextureView.SurfaceTextureListener,
) : TextureView.SurfaceTextureListener, View.OnAttachStateChangeListener {
    private var detachedTexture: SurfaceTexture? = null
    private var width = 0
    private var height = 0
    private val resizeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        view.surfaceTexture?.let { onSurfaceTextureSizeChanged(it, view.width, view.height) }
    }

    init {
        view.surfaceTextureListener = this
        view.addOnAttachStateChangeListener(this)
        view.addOnLayoutChangeListener(resizeListener)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        releaseDetachedTexture()
        this.width = width
        this.height = height
        renderer.onSurfaceTextureAvailable(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (width <= 0 || height <= 0 || (width == this.width && height == this.height)) return
        this.width = width
        this.height = height
        renderer.onSurfaceTextureSizeChanged(surface, width, height)
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        renderer.onSurfaceTextureUpdated(surface)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        detachedTexture = surface
        return false
    }

    override fun onViewAttachedToWindow(view: View) {
        val surface = detachedTexture ?: return
        if (surface.isReleased) {
            releaseDetachedTexture()
            return
        }
        // Attaching a retained texture doesn't send the SDK a second surface-created event.
        this.view.setSurfaceTexture(surface)
        detachedTexture = null
    }

    override fun onViewDetachedFromWindow(view: View) = Unit

    fun releaseDetachedTexture() {
        val surface = detachedTexture ?: return
        detachedTexture = null
        renderer.onSurfaceTextureDestroyed(surface)
        if (!surface.isReleased) surface.release()
    }

    fun close() {
        view.removeOnAttachStateChangeListener(this)
        view.removeOnLayoutChangeListener(resizeListener)
        view.surfaceTextureListener = renderer
        releaseDetachedTexture()
    }

    companion object {
        fun retainIn(mapView: ViewGroup): WorldMapTexture? {
            val texture = mapView.descendants.filterIsInstance<TextureView>().firstOrNull() ?: return null
            val renderer = texture.surfaceTextureListener ?: return null
            return WorldMapTexture(texture, renderer)
        }
    }
}
