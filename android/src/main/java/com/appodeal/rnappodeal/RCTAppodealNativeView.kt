package com.appodeal.rnappodeal

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import com.appodeal.ads.NativeAd
import com.appodeal.ads.nativead.NativeAdViewContentStream
import com.appodeal.ads.nativead.Position
import com.appodeal.rnappodeal.callbacks.RNAppodealEventHandler
import com.appodeal.rnappodeal.constants.NativeEvents
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.events.Event
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * React Native host for Appodeal native ads.
 *
 * Matches the official Appodeal Android demo
 * (`appodeal/appodeal-android-sdk` → `NativeListAdapter` / `NativeActivity`):
 * the list item **is** a [NativeAdViewContentStream], then `registerView(nativeAd)`.
 *
 * Previous approach nested a GONE-by-default [com.appodeal.ads.nativead.NativeAdView]
 * inside [com.facebook.react.views.view.ReactViewGroup]. `registerView` returned true
 * but the child stayed absent from the UI hierarchy (uiautomator: empty host), while
 * banners (VISIBLE [com.appodeal.ads.BannerView] child) rendered fine.
 */
class RCTAppodealNativeView(
    private val reactContext: ReactContext
) : NativeAdViewContentStream(reactContext), RNAppodealEventHandler {

    private val surfaceId: Int by lazy { UIManagerHelper.getSurfaceId(reactContext) }
    private val uiHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    var adId: String? = null
        set(value) {
            if (field == value) return
            field = value
            boundAdId = null
            bindGeneration++
            scheduleBind(BIND_DELAY_MS)
        }

    var placement: String = "default"
        set(value) {
            if (field == value) return
            field = value
            if (boundAdId != null) {
                boundAdId = null
                bindGeneration++
                scheduleBind(BIND_DELAY_MS)
            }
        }

    /** Kept for API compat; ContentStream template is fixed (matches Android demo default). */
    @Suppress("UNUSED_PARAMETER")
    var adTemplate: String = "contentStream"

    private var boundAdId: String? = null
    private var bindRunnable: Runnable? = null
    private var bindGeneration: Int = 0
    private var bindAttempts: Int = 0

    init {
        liveViews.add(WeakReference(this))
        // Demo XML starts templates as gone; RN must keep a real laid-out host so
        // Appodeal's viewability check can pass. Force visible like BannerView hosts.
        visibility = VISIBLE
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        setBackgroundColor(Color.WHITE)
        setAdChoicesPosition(Position.END_TOP)
        contentDescription = "appodeal-native-ad"
    }

    fun cleanup() {
        bindRunnable?.let { uiHandler.removeCallbacks(it) }
        bindRunnable = null
        bindGeneration++
        try {
            unregisterView()
        } catch (_: Exception) {
        }
        try {
            destroy()
        } catch (_: Exception) {
        }
        boundAdId = null
        bindAttempts = 0
        val self = this
        liveViews.removeAll { it.get() == null || it.get() === self }
    }

    fun onActivityReady() {
        if (boundAdId == null && !adId.isNullOrEmpty()) {
            scheduleBind(0)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (right - left > 0 && bottom - top > 0 && boundAdId == null && !adId.isNullOrEmpty()) {
            scheduleBind(0)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (boundAdId == null && !adId.isNullOrEmpty()) {
            scheduleBind(0)
        }
    }

    private fun scheduleBind(delayMs: Long) {
        bindRunnable?.let { uiHandler.removeCallbacks(it) }
        val gen = bindGeneration
        val runnable = Runnable {
            if (gen != bindGeneration) return@Runnable
            bindAd(gen)
        }.also { bindRunnable = it }
        uiHandler.postDelayed(runnable, delayMs.coerceAtLeast(0L))
    }

    private fun bindAd(gen: Int) {
        if (gen != bindGeneration) return
        val id = adId
        if (id.isNullOrEmpty()) return
        if (boundAdId == id) return

        val ad: NativeAd = RNAppodealNativeAdStore.get(id) ?: run {
            if (!retry(gen, id, "native ad not in store")) {
                dispatchFailed(id, "native ad not in store")
            }
            return
        }

        if (width <= 0 || height <= 0) {
            if (!retry(gen, id, "view has zero size")) {
                dispatchFailed(id, "view has zero size")
            }
            return
        }

        if (!isAttachedToWindow) {
            if (!retry(gen, id, "not attached to window")) {
                dispatchFailed(id, "not attached to window")
            }
            return
        }

        val activity = RNAppodealActivityHolder.get() ?: reactContext.currentActivity
        if (activity == null) {
            if (!retry(gen, id, "activity unavailable")) {
                dispatchFailed(id, "activity unavailable")
            }
            return
        }

        val canShow = try {
            ad.canShow(activity, placement)
        } catch (e: Exception) {
            Log.e(TAG, "canShow threw", e)
            false
        }
        if (!canShow) {
            if (!retry(gen, id, "canShow=false")) {
                dispatchFailed(id, "canShow=false for placement=$placement")
            }
            return
        }

        // Same sequence as NativeActivity.showNative / NativeListAdapter.bind:
        // configure → registerView(nativeAd). Do not destroy/recreate between binds.
        try {
            unregisterView()
        } catch (_: Exception) {
        }

        visibility = VISIBLE

        val registered = try {
            registerView(ad, placement)
        } catch (e: Exception) {
            Log.e(TAG, "registerView threw", e)
            false
        }

        // SDK flips GONE→VISIBLE on success; keep forcing VISIBLE for RN hosts.
        visibility = VISIBLE
        bringToFront()
        (parent as? ViewGroup)?.bringChildToFront(this)
        requestLayout()
        invalidate()

        Log.d(
            TAG,
            "registerView id=$id result=$registered size=${width}x${height} " +
                "vis=$visibility attached=$isAttachedToWindow title=${ad.title}"
        )

        if (gen != bindGeneration) return

        if (registered) {
            boundAdId = id
            bindAttempts = 0
            uiHandler.postDelayed({
                if (gen != bindGeneration) return@postDelayed
                visibility = VISIBLE
                requestLayout()
                Log.d(
                    TAG,
                    "post-bind childCount=$childCount size=${width}x${height} vis=$visibility"
                )
            }, 100L)
            dispatchLoaded(id)
        } else if (!retry(gen, id, "registerView returned false")) {
            dispatchFailed(id, "registerView returned false")
        }
    }

    private fun retry(gen: Int, id: String, reason: String): Boolean {
        if (gen != bindGeneration) return true
        bindAttempts += 1
        if (bindAttempts > MAX_BIND_ATTEMPTS) return false
        Log.d(TAG, "retry #$bindAttempts id=$id reason=$reason")
        scheduleBind(BIND_DELAY_MS * bindAttempts)
        return true
    }

    private fun dispatchLoaded(adIdValue: String) {
        val params = Arguments.createMap().apply { putString("adId", adIdValue) }
        dispatchFabricEvent(id, NativeEvents.ON_AD_LOADED, params)
    }

    private fun dispatchFailed(adIdValue: String, message: String) {
        Log.w(TAG, "bind failed id=$adIdValue message=$message")
        val params = Arguments.createMap().apply {
            putString("adId", adIdValue)
            putString("message", message)
        }
        dispatchFabricEvent(id, NativeEvents.ON_AD_FAILED_TO_LOAD, params)
    }

    override fun handleEvent(event: String, params: WritableMap?) {
        when (event) {
            NativeEvents.ON_NATIVE_SHOWN -> {
                if (boundAdId == null) return
                dispatchFabricEvent(id, NativeEvents.ON_AD_SHOWN, withAdId(params))
            }
            NativeEvents.ON_NATIVE_CLICKED -> {
                if (boundAdId == null) return
                dispatchFabricEvent(id, NativeEvents.ON_AD_CLICKED, withAdId(params))
            }
            NativeEvents.ON_NATIVE_EXPIRED -> {
                if (boundAdId == null) return
                try {
                    unregisterView()
                } catch (_: Exception) {
                }
                boundAdId = null
                dispatchFabricEvent(id, NativeEvents.ON_AD_EXPIRED, withAdId(params))
            }
            else -> Unit
        }
    }

    private fun withAdId(params: WritableMap?): WritableMap {
        val copy = Arguments.createMap()
        if (params != null) copy.merge(params)
        if (!copy.hasKey("adId")) copy.putString("adId", boundAdId ?: adId)
        return copy
    }

    private fun dispatchFabricEvent(viewId: Int, eventName: String, params: WritableMap?) {
        val dispatcher =
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

    companion object {
        private const val TAG = "RNAppodealNative"
        private const val MAX_BIND_ATTEMPTS = 6
        private const val BIND_DELAY_MS = 200L

        private val liveViews = CopyOnWriteArrayList<WeakReference<RCTAppodealNativeView>>()

        fun notifyActivityReady() {
            prune()
            for (ref in liveViews) ref.get()?.onActivityReady()
        }

        fun unbindAdId(adId: String) {
            prune()
            for (ref in liveViews) {
                val view = ref.get() ?: continue
                if (view.adId == adId || view.boundAdId == adId) {
                    view.bindGeneration++
                    try {
                        view.unregisterView()
                    } catch (_: Exception) {
                    }
                    view.boundAdId = null
                }
            }
        }

        private fun prune() {
            liveViews.removeAll { it.get() == null }
        }
    }
}
