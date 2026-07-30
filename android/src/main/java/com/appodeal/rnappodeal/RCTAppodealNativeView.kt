package com.appodeal.rnappodeal

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
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

class RCTAppodealNativeView(context: Context) : ReactViewGroup(context), RNAppodealEventHandler {

    private val surfaceId: Int by lazy { UIManagerHelper.getSurfaceId(reactContext) }

    var adId: String? = null
        set(value) {
            field = value
            bindAd()
        }

    var placement: String = "default"
        set(value) {
            field = value
            bindAd()
        }

    var template: String = "contentStream"
        set(value) {
            if (field != value) {
                field = value
                recreateNativeAdView()
            }
        }

    private var nativeAdView: NativeAdView? = null

    init {
        recreateNativeAdView()
    }

    fun cleanup() {
        nativeAdView?.let { view ->
            view.unregisterView()
            view.destroy()
        }
        removeAllViews()
        nativeAdView = null
    }

    private fun recreateNativeAdView() {
        cleanup()
        val view = createNativeAdView()
        nativeAdView = view
        view.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        addView(view)
        bindAd()
    }

    private fun createNativeAdView(): NativeAdView {
        return when (template) {
            "newsFeed" -> NativeAdViewNewsFeed(context)
            "appWall" -> NativeAdViewAppWall(context)
            else -> NativeAdViewContentStream(context)
        }
    }

    private fun bindAd() {
        val id = adId ?: return
        val ad = RNAppodealNativeAdStore.get(id) ?: return
        val view = nativeAdView ?: return
        view.registerView(ad, placement)
    }

    override fun handleEvent(event: String, params: WritableMap?) {
        when (event) {
            NativeEvents.ON_NATIVE_LOADED -> dispatchFabricEvent(id, NativeEvents.ON_AD_LOADED, params)
            NativeEvents.ON_NATIVE_FAILED_TO_LOAD -> dispatchFabricEvent(id, NativeEvents.ON_AD_FAILED_TO_LOAD, params)
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
}
