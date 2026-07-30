package com.appodeal.rnappodeal.callbacks

import com.appodeal.ads.NativeAd
import com.appodeal.ads.NativeCallbacks
import com.appodeal.rnappodeal.RNAEventDispatcher
import com.appodeal.rnappodeal.constants.NativeEvents
import com.facebook.react.bridge.Arguments

/**
 * Callback handler for Appodeal native ad events.
 *
 * This class implements the NativeCallbacks interface and handles native ad lifecycle events
 * including loading, showing, clicking, and expiration. It forwards events to both:
 *
 * 1. **Event Dispatcher**: For advanced event management with priority handling
 * 2. **Event Handler Manager**: For Fabric/New Architecture event dispatching
 *
 * @param eventDispatcher The RNAEventDispatcher instance used for advanced event management
 * @param handlers The event handler manager that forwards events to registered native views
 */
internal class RNAppodealNativeCallbacks(
    private val eventDispatcher: RNAEventDispatcher,
    private val handlers: RNAppodealEventHandler = RNAppodealEventHandlerManager
) : NativeCallbacks {

    override fun onNativeLoaded() {
        eventDispatcher.dispatchEvent(NativeEvents.ON_NATIVE_LOADED, null)
        handlers.handleEvent(NativeEvents.ON_NATIVE_LOADED, null)
    }

    override fun onNativeFailedToLoad() {
        eventDispatcher.dispatchEvent(NativeEvents.ON_NATIVE_FAILED_TO_LOAD, null)
        handlers.handleEvent(NativeEvents.ON_NATIVE_FAILED_TO_LOAD, null)
    }

    override fun onNativeShown(nativeAd: NativeAd) {
        val params = createNativeAdParams(nativeAd)
        eventDispatcher.dispatchEvent(NativeEvents.ON_NATIVE_SHOWN, params)
        handlers.handleEvent(NativeEvents.ON_NATIVE_SHOWN, params)
    }

    override fun onNativeShowFailed(nativeAd: NativeAd) {
        val params = createNativeAdParams(nativeAd)
        eventDispatcher.dispatchEvent(NativeEvents.ON_NATIVE_SHOW_FAILED, params)
        handlers.handleEvent(NativeEvents.ON_NATIVE_SHOW_FAILED, params)
    }

    override fun onNativeClicked(nativeAd: NativeAd) {
        val params = createNativeAdParams(nativeAd)
        eventDispatcher.dispatchEvent(NativeEvents.ON_NATIVE_CLICKED, params)
        handlers.handleEvent(NativeEvents.ON_NATIVE_CLICKED, params)
    }

    override fun onNativeExpired() {
        eventDispatcher.dispatchEvent(NativeEvents.ON_NATIVE_EXPIRED, null)
        handlers.handleEvent(NativeEvents.ON_NATIVE_EXPIRED, null)
    }

    private fun createNativeAdParams(nativeAd: NativeAd) = Arguments.createMap().apply {
        putString("title", nativeAd.title ?: "")
        putString("description", nativeAd.description ?: "")
        putString("callToAction", nativeAd.callToAction ?: "")
        putDouble("rating", nativeAd.rating.toDouble())
        putBoolean("containsVideo", nativeAd.containsVideo())
        putDouble("predictedEcpm", nativeAd.predictedEcpm)
    }
}
