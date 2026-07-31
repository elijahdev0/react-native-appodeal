package com.appodeal.rnappodeal

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import com.appodeal.ads.NativeAd
import com.appodeal.ads.nativead.NativeAdView
import com.appodeal.ads.nativead.NativeAdViewAppWall
import com.appodeal.ads.nativead.NativeAdViewContentStream
import com.appodeal.ads.nativead.NativeAdViewNewsFeed
import com.appodeal.rnappodeal.callbacks.RNAppodealEventHandler
import com.appodeal.rnappodeal.constants.NativeEvents
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.events.Event
import com.facebook.react.uimanager.events.EventDispatcher
import com.facebook.react.views.view.ReactViewGroup
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Hosts an Appodeal native-ad template inside React Native.
 *
 * ReactViewGroup does not lay out Android children unless we measure them
 * (same pattern as banner/MREC). Templates also need an Activity context so
 * [NativeAd.canShow] / [NativeAdView.registerView] succeed.
 */
class RCTAppodealNativeView(context: Context) : ReactViewGroup(context), RNAppodealEventHandler {

    private val surfaceId: Int by lazy { UIManagerHelper.getSurfaceId(reactContext) }
    private val uiHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    var adId: String? = null
        set(value) {
            if (field == value) return
            unregisterCurrent()
            field = value
            scheduleBind(0)
        }

    var placement: String = "default"
        set(value) {
            if (field == value) return
            unregisterCurrent()
            field = value
            scheduleBind(0)
        }

    var adTemplate: String = "contentStream"
        set(value) {
            if (field == value) return
            field = value
            recreateNativeAdView()
            scheduleBind(0)
        }

    private var nativeAdView: NativeAdView? = null
    private var boundAdId: String? = null
    private var bindRunnable: Runnable? = null
    private var createdWithActivity: Boolean = false
    private var bindAttempts: Int = 0

    private val measureAndLayout = Runnable {
        val w = measuredWidth
        val h = measuredHeight
        if (w <= 0 || h <= 0) return@Runnable
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
            )
            child.layout(0, 0, child.measuredWidth, child.measuredHeight)
        }
    }

    init {
        liveViews.add(WeakReference(this))
        recreateNativeAdView()
    }

    override fun requestLayout() {
        super.requestLayout()
        post(measureAndLayout)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        post(measureAndLayout)
        if (right - left > 0 && bottom - top > 0 && boundAdId == null && !adId.isNullOrEmpty()) {
            scheduleBind(0)
        }
    }

    fun cleanup() {
        bindRunnable?.let { uiHandler.removeCallbacks(it) }
        bindRunnable = null
        unregisterCurrent()
        nativeAdView?.let { view ->
            try {
                view.destroy()
            } catch (_: Exception) {
            }
        }
        removeAllViews()
        nativeAdView = null
        val self = this
        liveViews.removeAll { it.get() == null || it.get() === self }
    }

    /** Called when Activity becomes available (module onHostResume). */
    fun onActivityReady() {
        if (boundAdId == null && !adId.isNullOrEmpty()) {
            scheduleBind(0)
        }
    }

    private fun hostContext(): Context {
        return RNAppodealActivityHolder.get()
            ?: reactContext.currentActivity
            ?: context
    }

    private fun unregisterCurrent() {
        if (boundAdId != null || nativeAdView != null) {
            try {
                nativeAdView?.unregisterView()
            } catch (_: Exception) {
            }
        }
        boundAdId = null
        bindAttempts = 0
    }

    private fun recreateNativeAdView() {
        unregisterCurrent()
        nativeAdView?.let { view ->
            try {
                view.destroy()
            } catch (_: Exception) {
            }
        }
        removeAllViews()

        val activity = RNAppodealActivityHolder.get() ?: reactContext.currentActivity
        createdWithActivity = activity != null
        val view = createNativeAdView(activity ?: context)
        nativeAdView = view
        view.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        view.descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        addView(view)
        post(measureAndLayout)
    }

    private fun createNativeAdView(ctx: Context): NativeAdView {
        return when (adTemplate) {
            "newsFeed" -> NativeAdViewNewsFeed(ctx)
            "appWall" -> NativeAdViewAppWall(ctx)
            else -> NativeAdViewContentStream(ctx)
        }
    }

    private fun scheduleBind(delayMs: Long) {
        bindRunnable?.let { uiHandler.removeCallbacks(it) }
        val runnable = Runnable { bindAd() }.also { bindRunnable = it }
        uiHandler.postDelayed(runnable, delayMs.coerceAtLeast(0L))
    }

    private fun bindAd() {
        val id = adId
        if (id.isNullOrEmpty()) return
        if (boundAdId == id) return

        val ad: NativeAd = RNAppodealNativeAdStore.get(id) ?: run {
            Log.w(TAG, "bindAd: no NativeAd in store for id=$id")
            // Ad may arrive slightly later; retry a few times before failing.
            if (retryOrFail(id, "native ad not in store")) return
            return
        }

        val activity = RNAppodealActivityHolder.get() ?: reactContext.currentActivity
        if (nativeAdView == null || (activity != null && !createdWithActivity)) {
            recreateNativeAdView()
        }
        val view = nativeAdView ?: run {
            dispatchFailed(id, "native ad view missing")
            return
        }

        if (measuredWidth <= 0 || measuredHeight <= 0) {
            Log.d(TAG, "bindAd: waiting for size id=$id attempt=$bindAttempts")
            retryOrFail(id, "view has zero size")
            return
        }

        if (activity == null) {
            Log.d(TAG, "bindAd: waiting for Activity id=$id attempt=$bindAttempts")
            retryOrFail(id, "activity unavailable")
            return
        }

        post(measureAndLayout)

        val canShow = try {
            ad.canShow(activity, placement)
        } catch (e: Exception) {
            Log.e(TAG, "canShow threw", e)
            false
        }
        if (!canShow) {
            Log.w(TAG, "bindAd: canShow=false id=$id placement=$placement attempt=$bindAttempts")
            retryOrFail(id, "canShow=false for placement=$placement")
            return
        }

        // Ensure clean registration if something was partially bound.
        if (boundAdId != null && boundAdId != id) {
            try {
                view.unregisterView()
            } catch (_: Exception) {
            }
            boundAdId = null
        }

        val registered = try {
            view.registerView(ad, placement)
        } catch (e: Exception) {
            Log.e(TAG, "registerView threw", e)
            false
        }

        Log.d(
            TAG,
            "registerView id=$id placement=$placement result=$registered size=${measuredWidth}x${measuredHeight}"
        )

        if (registered) {
            boundAdId = id
            bindAttempts = 0
            visibility = VISIBLE
            view.visibility = VISIBLE
            try {
                (parent as? ViewGroup)?.bringChildToFront(this)
            } catch (_: Exception) {
            }
            view.bringToFront()
            post(measureAndLayout)
            dispatchLoaded(id)
        } else {
            retryOrFail(id, "registerView returned false")
        }
    }

    /** @return true if a retry was scheduled */
    private fun retryOrFail(id: String, reason: String): Boolean {
        bindAttempts += 1
        if (bindAttempts <= MAX_BIND_ATTEMPTS) {
            val delay = BIND_BASE_DELAY_MS * bindAttempts
            scheduleBind(delay)
            return true
        }
        dispatchFailed(id, reason)
        return false
    }

    private fun dispatchLoaded(id: String) {
        val params = Arguments.createMap().apply { putString("adId", id) }
        dispatchFabricEvent(getId(), NativeEvents.ON_AD_LOADED, params)
    }

    private fun dispatchFailed(id: String, message: String) {
        Log.w(TAG, "bind failed id=$id message=$message")
        val params = Arguments.createMap().apply {
            putString("adId", id)
            putString("message", message)
        }
        dispatchFabricEvent(getId(), NativeEvents.ON_AD_FAILED_TO_LOAD, params)
    }

    override fun handleEvent(event: String, params: WritableMap?) {
        // Global SDK callbacks fan out to every view — only forward if this
        // view is bound, and attach adId when missing.
        when (event) {
            NativeEvents.ON_NATIVE_SHOWN -> {
                if (boundAdId == null) return
                dispatchFabricEvent(getId(), NativeEvents.ON_AD_SHOWN, withAdId(params))
            }
            NativeEvents.ON_NATIVE_CLICKED -> {
                if (boundAdId == null) return
                dispatchFabricEvent(getId(), NativeEvents.ON_AD_CLICKED, withAdId(params))
            }
            NativeEvents.ON_NATIVE_EXPIRED -> {
                if (boundAdId == null) return
                unregisterCurrent()
                visibility = GONE
                dispatchFabricEvent(getId(), NativeEvents.ON_AD_EXPIRED, withAdId(params))
            }
            // Cache-level load events are not per-view bind results — ignore here.
            else -> Unit
        }
    }

    private fun withAdId(params: WritableMap?): WritableMap {
        val copy = Arguments.createMap()
        if (params != null) copy.merge(params)
        if (!copy.hasKey("adId")) {
            copy.putString("adId", boundAdId ?: adId)
        }
        return copy
    }

    private fun dispatchFabricEvent(
        viewId: Int,
        eventName: String,
        params: WritableMap?
    ) {
        val dispatcher: EventDispatcher? =
            UIManagerHelper.getEventDispatcherForReactTag(reactContext, viewId)
        dispatcher?.dispatchEvent(OnViewEvent(surfaceId, viewId, eventName, params))
    }

    private class OnViewEvent(
        surfaceId: Int,
        viewId: Int,
        private val eventNameParam: String,
        private val payload: WritableMap?
    ) : Event<OnViewEvent>(surfaceId, viewId) {
        override fun getEventName(): String = eventNameParam
        override fun getEventData(): WritableMap? {
            if (payload == null) return null
            val copy = Arguments.createMap()
            copy.merge(payload)
            return copy
        }
    }

    private val reactContext: ReactContext
        get() = context as ReactContext

    companion object {
        private const val TAG = "RNAppodealNative"
        private const val MAX_BIND_ATTEMPTS = 8
        private const val BIND_BASE_DELAY_MS = 250L

        private val liveViews = CopyOnWriteArrayList<WeakReference<RCTAppodealNativeView>>()

        fun notifyActivityReady() {
            pruneLiveViews()
            for (ref in liveViews) {
                ref.get()?.onActivityReady()
            }
        }

        fun unbindAdId(adId: String) {
            pruneLiveViews()
            for (ref in liveViews) {
                val view = ref.get() ?: continue
                if (view.adId == adId || view.boundAdId == adId) {
                    view.unregisterCurrent()
                    view.visibility = GONE
                }
            }
        }

        private fun pruneLiveViews() {
            val it = liveViews.iterator()
            while (it.hasNext()) {
                if (it.next().get() == null) it.remove()
            }
        }
    }
}
