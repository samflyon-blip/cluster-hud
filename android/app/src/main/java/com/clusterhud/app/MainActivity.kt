package com.clusterhud.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * ClusTer HUD: full-screen speedo. The screen itself is index.html (shared with the web
 * version) shown in a WebView. This activity feeds it GPS fixes and Google Maps turn info.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var pageReady = false
    private var locationOn = false
    private val locationManager by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }

    private val locationListener = LocationListener { loc: Location -> sendFix(loc) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                NavBus.last?.let { pushNav(it) }
            }
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")

        NavBus.listener = { json -> runOnUiThread { onNavChanged(json) } }
        askLocationPermission()
    }

    private var resumed = false
    private var navWasActive = false

    override fun onResume() {
        super.onResume()
        resumed = true
        hideSystemBars()
        if (locationOn) startLocation()
        if (pageReady) web.evaluateJavascript("document.dispatchEvent(new Event('visibilitychange'))", null)
    }

    override fun onStart() {
        super.onStart()
        Hud.visible = true
    }

    override fun onStop() {
        Hud.visible = false
        locationManager.removeUpdates(locationListener)   // stays on while visible in split screen
        super.onStop()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
    }

    override fun onDestroy() {
        NavBus.listener = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---------- GPS ----------
    private fun askLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (locationOn) startLocation()
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        locationOn = true
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 200L, 0f, locationListener, mainLooper)
        } catch (e: Exception) { /* GPS unavailable */ }
    }

    private fun sendFix(l: Location) {
        if (!pageReady) return
        val speed = if (l.hasSpeed()) l.speed.toDouble() else -1.0
        web.evaluateJavascript(
            "window.nativeFix && window.nativeFix(${l.latitude},${l.longitude},$speed,${l.accuracy},${l.time})", null
        )
    }

    // ---------- Google Maps turn card ----------
    private fun pushNav(json: JSONObject) {
        if (!pageReady) return
        web.evaluateJavascript("window.nativeNav && window.nativeNav($json)", null)
    }

    /** Google Maps started navigating while the speedo is on screen: open Maps beside it. */
    private fun onNavChanged(json: JSONObject) {
        pushNav(json)
        val active = json.optBoolean("active")
        if (active && !navWasActive && Hud.visible && !isInMultiWindowMode) openMapsBeside()
        navWasActive = active
    }

    fun openMapsBeside() {
        val i = packageManager.getLaunchIntentForPackage(NavListener.MAPS) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (e: Exception) { /* Maps not installed */ }
    }

    private fun listenerGranted(): Boolean {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationListenerAccessGranted(ComponentName(this, NavListener::class.java))
    }

    private fun navAccessGranted(): Boolean = listenerGranted() && Settings.canDrawOverlays(this)

    inner class Bridge {
        @JavascriptInterface
        fun startLocation() = runOnUiThread { this@MainActivity.startLocation() }

        @JavascriptInterface
        fun navAccess(): Boolean = navAccessGranted()

        @JavascriptInterface
        fun enableNav() = runOnUiThread {
            if (listenerGranted()) {
                // Step 2: allow ClusTer HUD to pop up beside Maps when navigation starts
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return@runOnUiThread
            }
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(this@MainActivity, NavListener::class.java).flattenToString()
                )
            try { startActivity(detail) } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }

        @JavascriptInterface
        fun openMaps() = runOnUiThread { openMapsBeside() }
    }
}
