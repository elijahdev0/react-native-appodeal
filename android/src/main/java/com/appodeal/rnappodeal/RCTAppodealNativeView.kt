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

/**
 * Hosts an Appodeal native-ad template inside React Native.
 *
 * Important: ReactViewGroup does not lay out Android children unless we
 * explicitly measure/layout them (same pattern as banner/MREC). Templates
 * also need an Activity context so [NativeAd.canShow] / [NativeAdView.registerView]
 * succeed — ReactContext alone often makes registerView return false and the
 * template stays GONE.
 */
class RCTAppodealNativeView(context: Context) : ReactViewGroup(context), RNAppodealEventHandler {

    private val surfaceId: Int by lazy { UIManagerHelper.getSurfaceId(reactContext) }
    private val uiHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    var adId: String? = null
        set(value) {
            if (field == value) return
            field = value
            boundAdId = null
            scheduleBind()
        }

    var placement: String = "default"
        set(value) {
            if (field == value) return
            field = value
            boundAdId = null
            scheduleBind()
        }

    var adTemplate: String = "contentStream"
        set(value) {
            if (field == value) return
            field = value
            boundAdId = null
            recreateNativeAdView()
            scheduleBind()
        }

    private var nativeAdView: NativeAdView? = null
    private var boundAdId: String? = null
    private var bindRunnable: Runnable? = null
    /** True when the template was constructed with an Activity (needed for canShow). */
    private var createdWithActivity: Boolean = false

    /**
     * Mirror banner/MREC: RN won't size native children without this.
     */
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
        recreateNativeAdView()
    }

    override fun requestLayout() {
        super.requestLayout()
        post(measureAndLayout)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        post(measureAndLayout)
        if (right - left > 0 && bottom - top > 0) {
            scheduleBind()
        }
    }

    fun cleanup() {
        bindRunnable?.let { uiHandler.removeCallbacks(it) }
        bindRunnable = null
        nativeAdView?.let { view ->
            try {
                view.unregisterView()
            } catch (_: Exception) {
            }
            try {
                view.destroy()
            } catch (_: Exception) {
            }
        }
        removeAllViews()
        nativeAdView = null
        boundAdId = null
    }

    private fun hostContext(): Context {
        return reactContext.currentActivity ?: context
    }

    private fun recreateNativeAdView() {
        nativeAdView?.let { view ->
            try {
                view.unregisterView()
            } catch (_: Exception) {
            }
            try {
                view.destroy()
            } catch (_: Exception) {
            }
        }
        removeAllViews()
        boundAdId = null

        val host = hostContext()
        createdWithActivity = reactContext.currentActivity != null
        val view = createNativeAdView(host)
        nativeAdView = view
        view.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        // Templates start GONE until registerView succeeds.
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

    private fun scheduleBind() {
        bindRunnable?.let { uiHandler.removeCallbacks(it) }
        val runnable = Runnable { bindAd() }.also { bindRunnable = it }
        // Slight delay so RN has committed size (same idea as banner show).
        uiHandler.postDelayed(runnable, 50L)
    }

    private fun bindAd() {
        val id = adId
        if (id.isNullOrEmpty()) return
        if (boundAdId == id) return

        val ad: NativeAd = RNAppodealNativeAdStore.get(id) ?: run {
            Log.w(TAG, "bindAd: no NativeAd in store for id=$id")
            return
        }

        // Prefer Activity context — ReactContext alone often makes canShow/registerView fail.
        if (nativeAdView == null || (reactContext.currentActivity != null && !createdWithActivity)) {
            recreateNativeAdView()
        }
        val view = nativeAdView ?: return

        if (measuredWidth <= 0 || measuredHeight <= 0) {
            Log.d(TAG, "bindAd: waiting for size id=$id")
            return
        }

        post(measureAndLayout)

        val canShow = try {
            ad.canShow(hostContext(), placement)
        } catch (e: Exception) {
            Log.e(TAG, "canShow threw", e)
            false
        }
        if (!canShow) {
            Log.w(TAG, "bindAd: canShow=false id=$id placement=$placement")
            return
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
            visibility = VISIBLE
            view.visibility = VISIBLE
            post(measureAndLayout)
        }
    }

    override fun handleEvent(event: String, params: WritableMap?) {
        when (event) {
            NativeEvents.ON_NATIVE_LOADED -> dispatchFabricEvent(id, NativeEvents.ON_AD_LOADED, params)
            NativeEvents.ON_NATIVE_FAILED_TO_LOAD ->
                dispatchFabricEvent(id, NativeEvents.ON_AD_FAILED_TO_LOAD, params)
            NativeEvents.ON_NATIVE_SHOWN -> dispatchFabricEvent(id, NativeEvents.ON_AD_SHOWN, params)
            NativeEvents.ON_NATIVE_CLICKED -> dispatchFabricEvent(id, NativeEvents.ON_AD_CLICKED, params)
            NativeEvents.ON_NATIVE_EXPIRED -> dispatchFabricEvent(id, NativeEvents.ON_AD_EXPIRED, params)
            else -> Unit
        }
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
    }
}
