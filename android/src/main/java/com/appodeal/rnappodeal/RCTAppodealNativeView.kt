package com.appodeal.rnappodeal

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.appodeal.ads.NativeAd
import com.appodeal.ads.nativead.NativeAdView
import com.appodeal.ads.nativead.NativeIconView
import com.appodeal.ads.nativead.NativeMediaView
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
import kotlin.math.roundToInt

/**
 * Hosts an Appodeal native ad using a **custom** [NativeAdView] layout.
 *
 * Stock templates (ContentStream/etc.) register successfully inside RN but stay
 * blank / "not visible globally" because ReactViewGroup + template GONE/VISIBLE
 * + recreate races destroy the view right after bind. Custom asset binding
 * (title/CTA/media) matches Appodeal's documented custom-layout path and paints
 * reliably in RN.
 */
class RCTAppodealNativeView(context: Context) : ReactViewGroup(context), RNAppodealEventHandler {

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

    /** Kept for API compat; custom layout ignores template style. */
    @Suppress("UNUSED_PARAMETER")
    var adTemplate: String = "contentStream"

    private var nativeAdView: NativeAdView? = null
    private var titleView: TextView? = null
    private var descriptionView: TextView? = null
    private var ctaView: Button? = null
    private var boundAdId: String? = null
    private var bindRunnable: Runnable? = null
    private var bindGeneration: Int = 0
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
        ensureNativeAdView()
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
        bindGeneration++
        unbindQuietly()
        removeAllViews()
        nativeAdView = null
        titleView = null
        descriptionView = null
        ctaView = null
        val self = this
        liveViews.removeAll { it.get() == null || it.get() === self }
    }

    fun onActivityReady() {
        if (boundAdId == null && !adId.isNullOrEmpty()) {
            scheduleBind(0)
        }
    }

    private fun hostActivityContext(): Context {
        return RNAppodealActivityHolder.get()
            ?: reactContext.currentActivity
            ?: context
    }

    private fun ensureNativeAdView() {
        if (nativeAdView != null) return
        val view = buildCustomNativeAdView(hostActivityContext())
        nativeAdView = view
        addView(
            view,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        post(measureAndLayout)
    }

    private fun buildCustomNativeAdView(ctx: Context): NativeAdView {
        val adView = NativeAdView(ctx)
        adView.setBackgroundColor(Color.WHITE)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dpPx(ctx, 8), dpPx(ctx, 8), dpPx(ctx, 8), dpPx(ctx, 8))
        }

        val attribution = TextView(ctx).apply {
            text = "Ad"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#FF9800"))
            setPadding(dpPx(ctx, 6), dpPx(ctx, 2), dpPx(ctx, 6), dpPx(ctx, 2))
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val icon = NativeIconView(ctx)
        val iconLp = LinearLayout.LayoutParams(dpPx(ctx, 40), dpPx(ctx, 40)).apply {
            rightMargin = dpPx(ctx, 8)
        }

        val title = TextView(ctx).apply {
            setTextColor(Color.BLACK)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
        }
        titleView = title

        header.addView(icon, iconLp)
        header.addView(
            title,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val description = TextView(ctx).apply {
            setTextColor(Color.DKGRAY)
            textSize = 12f
            maxLines = 3
        }
        descriptionView = description

        val media = NativeMediaView(ctx).apply {
            setBackgroundColor(Color.parseColor("#EEEEEE"))
        }

        val cta = Button(ctx).apply {
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1A73E8"))
        }
        ctaView = cta

        root.addView(
            attribution,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpPx(ctx, 6) }
        )
        root.addView(
            description,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpPx(ctx, 4) }
        )
        root.addView(
            media,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { topMargin = dpPx(ctx, 6) }
        )
        root.addView(
            cta,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpPx(ctx, 6) }
        )

        adView.addView(
            root,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        adView.setTitleView(title)
        adView.setDescriptionView(description)
        adView.setCallToActionView(cta)
        adView.setIconView(icon)
        adView.setMediaView(media)
        adView.setAdAttributionView(attribution)

        return adView
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

        if (measuredWidth <= 0 || measuredHeight <= 0) {
            if (!retry(gen, id, "view has zero size")) {
                dispatchFailed(id, "view has zero size")
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

        ensureNativeAdView()
        val view = nativeAdView ?: run {
            dispatchFailed(id, "native ad view missing")
            return
        }

        // Populate assets BEFORE registerView (Appodeal custom-layout contract).
        titleView?.text = ad.title.orEmpty().ifEmpty { "Ad" }
        descriptionView?.text = ad.description.orEmpty()
        ctaView?.text = ad.callToAction.orEmpty().ifEmpty { "Learn more" }

        post(measureAndLayout)

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

        // Do NOT destroy/recreate here — that was wiping a successful register.
        try {
            view.unregisterView()
        } catch (_: Exception) {
        }

        val registered = try {
            view.registerView(ad, placement)
        } catch (e: Exception) {
            Log.e(TAG, "registerView threw", e)
            false
        }

        Log.d(
            TAG,
            "registerView id=$id result=$registered size=${measuredWidth}x${measuredHeight} title=${ad.title}"
        )

        if (gen != bindGeneration) return

        if (registered) {
            boundAdId = id
            bindAttempts = 0
            visibility = VISIBLE
            view.visibility = VISIBLE
            post(measureAndLayout)
            // Second layout pass after SDK mutates media children.
            uiHandler.postDelayed({
                if (gen == bindGeneration) post(measureAndLayout)
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

    private fun unbindQuietly() {
        try {
            nativeAdView?.unregisterView()
        } catch (_: Exception) {
        }
        try {
            nativeAdView?.destroy()
        } catch (_: Exception) {
        }
        boundAdId = null
        bindAttempts = 0
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
                try {
                    nativeAdView?.unregisterView()
                } catch (_: Exception) {
                }
                boundAdId = null
                dispatchFabricEvent(getId(), NativeEvents.ON_AD_EXPIRED, withAdId(params))
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

    private val reactContext: ReactContext
        get() = context as ReactContext

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
                        view.nativeAdView?.unregisterView()
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

private fun dpPx(ctx: Context, value: Int): Int =
    TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        ctx.resources.displayMetrics
    ).roundToInt()
