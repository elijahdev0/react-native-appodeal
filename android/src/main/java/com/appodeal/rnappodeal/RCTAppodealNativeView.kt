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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.appodeal.ads.NativeAd
import com.appodeal.ads.nativead.NativeAdView
import com.appodeal.ads.nativead.NativeAdViewAppWall
import com.appodeal.ads.nativead.NativeAdViewContentStream
import com.appodeal.ads.nativead.NativeAdViewNewsFeed
import com.appodeal.ads.nativead.NativeIconView
import com.appodeal.ads.nativead.NativeMediaView
import com.appodeal.ads.nativead.Position
import com.appodeal.rnappodeal.callbacks.RNAppodealEventHandler
import com.appodeal.rnappodeal.constants.NativeEvents
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.events.Event
import com.facebook.react.views.view.ReactViewGroup
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * RN host for Appodeal native ads.
 *
 * Templates are final (cannot subclass). Host mirrors the working banner bridge:
 * [ReactViewGroup] + VISIBLE template child + manual [measureAndLayout].
 *
 * Without measureAndLayout, registerView succeeds but the child stays 0×0
 * (`childSize=0x0`) and Appodeal logs "ad not visible globally".
 */
class RCTAppodealNativeView(context: Context) : ReactViewGroup(context), RNAppodealEventHandler {

    private val reactContext: ReactContext = context as ReactContext
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

    var adTemplate: String = "contentStream"
        set(value) {
            val normalized = value.ifBlank { "contentStream" }
            if (field == normalized) return
            field = normalized
            tearDownAdView()
            boundAdId = null
            bindGeneration++
            scheduleBind(BIND_DELAY_MS)
        }

    private var adView: NativeAdView? = null
    private var titleView: TextView? = null
    private var descriptionView: TextView? = null
    private var ctaView: TextView? = null
    private var boundAdId: String? = null
    private var bindRunnable: Runnable? = null
    private var bindGeneration: Int = 0
    private var bindAttempts: Int = 0

    /** Same as banner/MREC — RN will not size native children otherwise. */
    private val measureAndLayout = Runnable {
        val w = measuredWidth
        val h = measuredHeight
        if (w <= 0 || h <= 0) return@Runnable
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.visibility = VISIBLE
            child.measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
            )
            child.layout(0, 0, child.measuredWidth, child.measuredHeight)
        }
    }

    init {
        liveViews.add(WeakReference(this))
        visibility = VISIBLE
        setBackgroundColor(Color.TRANSPARENT)
        contentDescription = "appodeal-native-host"
        clipChildren = false
        clipToPadding = false
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (boundAdId == null && !adId.isNullOrEmpty()) {
            scheduleBind(0)
        }
    }

    fun cleanup() {
        bindRunnable?.let { uiHandler.removeCallbacks(it) }
        bindRunnable = null
        bindGeneration++
        tearDownAdView()
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

    private fun tearDownAdView() {
        val view = adView
        adView = null
        if (view != null) {
            try {
                view.unregisterView()
            } catch (_: Exception) {
            }
            try {
                view.destroy()
            } catch (_: Exception) {
            }
            (view.parent as? ViewGroup)?.removeView(view)
        }
        removeAllViews()
        titleView = null
        descriptionView = null
        ctaView = null
    }

    private fun ensureAdView(): NativeAdView {
        adView?.let { existing ->
            existing.visibility = VISIBLE
            post(measureAndLayout)
            return existing
        }

        val view = createTemplate(adTemplate).apply {
            // Templates default to GONE — force VISIBLE like BannerView.
            visibility = VISIBLE
            descendantFocusability = FOCUS_BLOCK_DESCENDANTS
            setAdChoicesPosition(Position.END_TOP)
            contentDescription = "appodeal-native-ad"
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        removeAllViews()
        this.visibility = VISIBLE
        addView(view)
        view.bringToFront()
        try {
            (parent as? ViewGroup)?.bringChildToFront(this)
        } catch (_: Exception) {
        }
        post(measureAndLayout)

        adView = view
        return view
    }

    private fun createTemplate(template: String): NativeAdView {
        return when (template) {
            "newsFeed" -> NativeAdViewNewsFeed(context)
            "appWall" -> NativeAdViewAppWall(context)
            "gridCard" -> createGridCard(context)
            else -> NativeAdViewContentStream(context)
        }
    }

    /**
     * Portrait feed card matching our AdMob native layout:
     * media ~56% top, then icon + title/body, then CTA.
     * Stock contentStream is horizontal and looks broken in a tall grid cell.
     */
    private fun createGridCard(ctx: Context): NativeAdView {
        val adView = NativeAdView(ctx)
        adView.setBackgroundColor(COLOR_SURFACE)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val mediaWrap = FrameLayout(ctx).apply {
            setBackgroundColor(COLOR_MEDIA_BG)
        }
        val media = NativeMediaView(ctx)
        mediaWrap.addView(
            media,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val badge = TextView(ctx).apply {
            text = "Ad"
            setTextColor(Color.WHITE)
            setBackgroundColor(0x8C000000.toInt())
            setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = true
        }
        mediaWrap.addView(
            badge,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.START or Gravity.TOP
                setMargins(dp(ctx, 8), dp(ctx, 8), 0, 0)
            }
        )
        root.addView(
            mediaWrap,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.56f)
        )

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_SURFACE)
            setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10))
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val icon = NativeIconView(ctx)
        header.addView(
            icon,
            LinearLayout.LayoutParams(dp(ctx, 34), dp(ctx, 34)).apply {
                rightMargin = dp(ctx, 8)
            }
        )

        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        val title = TextView(ctx).apply {
            setTextColor(COLOR_TEXT)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            setLineSpacing(0f, 1.15f)
        }
        titleView = title
        val description = TextView(ctx).apply {
            setTextColor(COLOR_TEXT_SECONDARY)
            textSize = 11f
            maxLines = 1
        }
        descriptionView = description
        textCol.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        textCol.addView(
            description,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 2) }
        )
        header.addView(
            textCol,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        panel.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val cta = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(COLOR_CTA)
            gravity = Gravity.CENTER
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(ctx, 12), dp(ctx, 9), dp(ctx, 12), dp(ctx, 9))
            maxLines = 1
        }
        ctaView = cta
        panel.addView(
            cta,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 8) }
        )

        root.addView(
            panel,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.44f)
        )

        adView.addView(
            root,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        adView.setMediaView(media)
        adView.setIconView(icon)
        adView.setTitleView(title)
        adView.setDescriptionView(description)
        adView.setCallToActionView(cta)
        adView.setAdAttributionView(badge)
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

        // Size the child BEFORE registerView so viewability can pass.
        val view = ensureAdView()
        // Custom gridCard needs text filled before registerView (stock templates self-fill).
        titleView?.text = ad.title.orEmpty().ifEmpty { "Ad" }
        descriptionView?.text = ad.description.orEmpty()
        ctaView?.text = ad.callToAction.orEmpty().ifEmpty { "Learn more" }
        view.visibility = VISIBLE
        runMeasureAndLayoutNow()

        if (view.width <= 0 || view.height <= 0) {
            if (!retry(gen, id, "child still 0x0 after layout")) {
                dispatchFailed(id, "child still 0x0 after layout")
            }
            return
        }

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

        view.visibility = VISIBLE
        this.visibility = VISIBLE
        runMeasureAndLayoutNow()

        Log.d(
            TAG,
            "registerView id=$id result=$registered host=${measuredWidth}x${measuredHeight} " +
                "childCount=$childCount childVis=${view.visibility} " +
                "childSize=${view.width}x${view.height} title=${ad.title}"
        )

        if (gen != bindGeneration) return

        if (registered) {
            boundAdId = id
            bindAttempts = 0
            uiHandler.postDelayed({
                if (gen != bindGeneration) return@postDelayed
                view.visibility = VISIBLE
                runMeasureAndLayoutNow()
                Log.d(
                    TAG,
                    "post-bind childCount=$childCount childVis=${view.visibility} " +
                        "childSize=${view.width}x${view.height}"
                )
            }, 100L)
            dispatchLoaded(id)
        } else if (!retry(gen, id, "registerView returned false")) {
            dispatchFailed(id, "registerView returned false")
        }
    }

    private fun runMeasureAndLayoutNow() {
        val w = measuredWidth
        val h = measuredHeight
        if (w <= 0 || h <= 0) return
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.visibility = VISIBLE
            child.measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
            )
            child.layout(0, 0, child.measuredWidth, child.measuredHeight)
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
                    adView?.unregisterView()
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
                        view.adView?.unregisterView()
                    } catch (_: Exception) {
                    }
                    view.boundAdId = null
                }
            }
        }

        private fun prune() {
            liveViews.removeAll { it.get() == null }
        }

        // Match light AdMob card tokens (RN outerClip still applies theme surface/radius).
        private val COLOR_SURFACE: Int = Color.WHITE
        private val COLOR_MEDIA_BG: Int = Color.parseColor("#1A1515")
        private val COLOR_TEXT: Int = Color.parseColor("#2D2424")
        private val COLOR_TEXT_SECONDARY: Int = Color.parseColor("#7F6D5F")
        private val COLOR_CTA: Int = Color.parseColor("#2D2424")
    }
}

private fun dp(ctx: Context, value: Int): Int =
    TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        ctx.resources.displayMetrics
    ).toInt()
