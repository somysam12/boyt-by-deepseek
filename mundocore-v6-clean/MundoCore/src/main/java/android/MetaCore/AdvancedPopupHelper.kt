package android.MetaCore

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import java.lang.reflect.Field
import org.lsposed.lsparanoid.Obfuscate

@Obfuscate
object AdvancedPopupHelper {

    private val handler = Handler(Looper.getMainLooper())

    /* ================= GET TOP ACTIVITY ================= */
    private fun getTopActivity(): Activity? {
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAT = atClass.getMethod("currentActivityThread").invoke(null)
            val mActivitiesField: Field = atClass.getDeclaredField("mActivities")
            mActivitiesField.isAccessible = true
            val activities = mActivitiesField.get(currentAT) as Map<*, *>

            for (record in activities.values) {
                val rClass = record!!::class.java
                val pausedField = rClass.getDeclaredField("paused")
                pausedField.isAccessible = true
                if (!pausedField.getBoolean(record)) {
                    val activityField = rClass.getDeclaredField("activity")
                    activityField.isAccessible = true
                    return activityField.get(record) as Activity
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /* ================= ENTRY ================= */
    @JvmStatic
    fun showAuto() {
        val act = getTopActivity() ?: return
        if (act.isFinishing || act.isDestroyed) return
        showPopup(act)
    }

    /* ================= SHOW POPUP ================= */
    private fun showPopup(act: Activity) {
        handler.post {
            try {
                val dialog = Dialog(act, android.R.style.Theme_Translucent_NoTitleBar)
                dialog.setCancelable(false)

                val webView = WebView(act)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.setBackgroundColor(Color.TRANSPARENT)
                webView.webViewClient = WebViewClient()

                // Device info get karenge
                val deviceInfo = """
                    Device: ${Build.MANUFACTURER} ${Build.MODEL}
                    Android: ${Build.VERSION.RELEASE}
                    SDK: ${Build.VERSION.SDK_INT}
                """.trimIndent()

                webView.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun close() {
                        handler.post {
                            if (dialog.isShowing) dialog.dismiss()
                        }
                    }
                    
                    @JavascriptInterface
                    fun getDeviceInfo(): String {
                        return deviceInfo
                    }
                    
                    @JavascriptInterface
                    fun getExpireStatus(): String {
                        return "Elite Access Revoked"
                    }
                }, "Android")

                webView.loadDataWithBaseURL(
                    null,
                    HTML,
                    "text/html",
                    "utf-8",
                    null
                )

                dialog.setContentView(webView)
                dialog.show()

                dialog.window?.apply {
                    setLayout(dp(act, 250), dp(act, 400))
                    setGravity(Gravity.CENTER)
                    addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    setDimAmount(0.6f)
                }

            } catch (_: Throwable) {
            }
        }
    }

    /* ================= DP UTILS ================= */
    private fun dp(act: Activity, v: Int): Int {
        return (v * act.resources.displayMetrics.density).toInt()
    }

    /* ================= HTML (CLEAN BLACK BACKGROUND) ================= */
    private const val HTML = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<script src="https://cdnjs.cloudflare.com/ajax/libs/bodymovin/5.12.2/lottie.min.js"></script>
<style>
* {
    margin: 0;
    padding: 0;
    box-sizing: border-box;
    font-family: 'Segoe UI', 'SF Pro Display', -apple-system, BlinkMacSystemFont, system-ui, sans-serif;
}

body {
    background: transparent;
    display: flex;
    justify-content: center;
    align-items: center;
    height: 100vh;
    overflow: hidden;
}

/* Animated background gradient */
body::before {
    content: '';
    position: absolute;
    width: 150%;
    height: 150%;
    background: radial-gradient(ellipse at center, rgba(77, 171, 247, 0.08) 0%, transparent 70%);
    animation: pulse 8s ease-in-out infinite;
    z-index: -1;
}

@keyframes pulse {
    0%, 100% { transform: scale(1); opacity: 0.8; }
    50% { transform: scale(1.05); opacity: 1; }
}

.card {
    width: 260px;
    background: linear-gradient(145deg, #ffffff, #f8fbff);
    border-radius: 20px;
    padding: 18px;
    border: 1px solid rgba(77, 171, 247, 0.3);
    box-shadow: 
        0 20px 60px rgba(23, 58, 98, 0.15),
        0 8px 20px rgba(77, 171, 247, 0.12),
        inset 0 1px 0 rgba(255, 255, 255, 0.9);
    position: relative;    overflow: hidden;
    backdrop-filter: blur(10px);
    animation: slideUp 0.4s cubic-bezier(0.16, 1, 0.3, 1);
}

@keyframes slideUp {
    from {
        opacity: 0;
        transform: translateY(20px) scale(0.98);
    }
    to {
        opacity: 1;
        transform: translateY(0) scale(1);
    }
}

/* Subtle top accent bar */
.card::before {
    content: '';
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    height: 3px;
    background: linear-gradient(90deg, #4dabf7, #74c0fc, #4dabf7);
    z-index: 2;
}

/* Soft glow effect */
.card::after {
    content: '';
    position: absolute;
    top: -50%;
    left: -50%;
    width: 200%;
    height: 200%;
    background: radial-gradient(circle, rgba(77, 171, 247, 0.06) 0%, transparent 60%);
    animation: float 15s ease-in-out infinite;
    pointer-events: none;
    z-index: 0;
}

@keyframes float {
    0%, 100% { transform: translate(0, 0) rotate(0deg); }
    25% { transform: translate(2%, 2%) rotate(0.5deg); }
    50% { transform: translate(0, 4%) rotate(0deg); }
    75% { transform: translate(-2%, 2%) rotate(-0.5deg); }
}

/* Header */.header {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 10px;
    margin-bottom: 14px;
    padding-bottom: 12px;
    border-bottom: 1px solid rgba(77, 171, 247, 0.25);
    position: relative;
    z-index: 1;
}

.title-icon {
    width: 24px;
    height: 24px;
    display: flex;
    align-items: center;
    justify-content: center;
}

.title {
    font-size: 15px;
    font-weight: 800;
    color: #1976d2;
    letter-spacing: 0.3px;
    text-transform: uppercase;
    background: linear-gradient(135deg, #1976d2, #4dabf7);
    -webkit-background-clip: text;
    -webkit-text-fill-color: transparent;
    background-clip: text;
}

/* Expire Status */
.expire-status {
    background: linear-gradient(135deg, rgba(77, 171, 247, 0.12), rgba(77, 171, 247, 0.06));
    border-radius: 12px;
    padding: 8px 12px;
    margin: 12px 0;
    text-align: center;
    border: 1px solid rgba(77, 171, 247, 0.3);
    position: relative;
    z-index: 1;
    transition: transform 0.2s ease;
}

.expire-status:hover {
    transform: scale(1.02);
}

.expire-text {    font-size: 11px;
    font-weight: 700;
    color: #1565c0;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 4px;
}

/* Device Info */
.device-info {
    background: rgba(240, 248, 255, 0.8);
    border-radius: 12px;
    padding: 10px;
    margin: 12px 0;
    border: 1px solid rgba(77, 171, 247, 0.2);
    position: relative;
    z-index: 1;
}

.info-row {
    display: flex;
    justify-content: space-between;
    margin: 4px 0;
    padding: 2px 0;
}

.info-label {
    font-size: 10px;
    color: #546e7a;
    font-weight: 600;
}

.info-value {
    font-size: 10px;
    color: #1a237e;
    font-weight: 700;
    text-align: right;
    max-width: 60%;
    word-wrap: break-word;
}

/* Content */
.content {
    margin: 10px 0;
    position: relative;
    z-index: 1;
}

.row {    display: flex;
    align-items: center;
    gap: 8px;
    margin: 6px 0;
    padding: 6px 8px;
    border-radius: 10px;
    transition: background 0.2s ease;
}

.row:hover {
    background: rgba(77, 171, 247, 0.08);
}

.icon {
    width: 16px;
    height: 16px;
    min-width: 16px;
    display: flex;
    align-items: center;
    justify-content: center;
}

.text {
    font-size: 11px;
    color: #37474f;
    line-height: 1.4;
}

.highlight {
    color: #1976d2;
    font-weight: 700;
}

/* Android Support Badge */
.android-support {
    text-align: center;
    margin: 12px 0;
    padding: 8px;
    background: linear-gradient(135deg, rgba(77, 171, 247, 0.1), rgba(25, 118, 210, 0.08));
    border-radius: 10px;
    border: 1px solid rgba(77, 171, 247, 0.25);
    position: relative;
    z-index: 1;
}

.support-text {
    font-size: 10px;
    color: #1565c0;
    font-weight: 700;
    letter-spacing: 0.3px;}

/* Footer */
.footer {
    margin-top: 12px;
    text-align: center;
    padding-top: 12px;
    border-top: 1px solid rgba(77, 171, 247, 0.25);
    position: relative;
    z-index: 1;
}

.footer-row {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
    margin-bottom: 6px;
}

.footer-icon {
    width: 14px;
    height: 14px;
    display: flex;
    align-items: center;
    justify-content: center;
}

.footer-text {
    font-size: 11px;
    font-weight: 800;
    color: #1976d2;
    letter-spacing: 0.2px;
}

.footer-note {
    font-size: 9px;
    color: #607d8b;
    margin-bottom: 8px;
}

/* Countdown Timer */
.countdown {
    font-size: 10px;
    color: #2e7d32;
    font-weight: 700;
    text-align: center;
    margin-top: 8px;
    background: linear-gradient(135deg, rgba(46, 125, 50, 0.12), rgba(76, 175, 80, 0.08));
    padding: 6px 10px;    border-radius: 8px;
    border: 1px solid rgba(76, 175, 80, 0.3);
    display: inline-flex;
    align-items: center;
    gap: 4px;
    animation: pulseGreen 2s ease-in-out infinite;
}

@keyframes pulseGreen {
    0%, 100% { box-shadow: 0 0 0 0 rgba(76, 175, 80, 0.2); }
    50% { box-shadow: 0 0 0 6px rgba(76, 175, 80, 0); }
}

#timer {
    font-weight: 800;
    color: #1976d2;
    min-width: 18px;
    display: inline-block;
}

/* Responsive tweaks */
@media (max-width: 300px) {
    .card {
        width: 240px;
        padding: 14px;
    }
    .title { font-size: 14px; }
    .info-label, .info-value, .text, .support-text { font-size: 9px; }
}

/* Smooth loading */
.card { opacity: 0; animation: slideUp 0.4s 0.1s forwards cubic-bezier(0.16, 1, 0.3, 1); }
</style>
</head>

<body>

<div class="card">
    <div class="header">
        <div id="iconTitle" class="title-icon"></div>
        <div class="title">LICENCE EXPIRED</div>
    </div>
    
    <!-- Expire Status -->
    <div class="expire-status">
        <div class="expire-text" id="expireStatus">
            <i>🔒</i> <span id="statusText">Revoked</span>
        </div>
    </div>
        <!-- Device Info -->
    <div class="device-info">
        <div id="deviceInfo">
            <!-- Device info will be inserted here by Android -->
        </div>
    </div>
    
    <div class="content">
        <div class="row">
            <div id="iconSupport" class="icon"></div>
            <div class="text">
                <span class="highlight">Support:</span> A8 to A17
            </div>
        </div>
        
        <div class="row">
            <div id="iconDev" class="icon"></div>
            <div class="text">
                <span class="highlight">Developer:</span> 
            </div>
        </div>
        
        <div class="row">
            <div id="iconContact" class="icon"></div>
            <div class="text">
                <span class="highlight">Contact:</span> TG ~ 
            </div>
        </div>
    </div>
    
    <!-- Android Support -->
    <div class="android-support">
        <div class="support-text">Android 8.0 to 17.0</div>
    </div>
    
    <div class="footer">
        <div class="footer-row">
            <div id="iconPerfect" class="footer-icon"></div>
            <span class="footer-text">Hcore-SDK</span>
        </div>
        <div class="footer-note">License renewal required</div>
        
        <div class="countdown">
            ⏱️ Close: <span id="timer">10</span>s
        </div>
    </div>
</div>

<script>
// Load Lottie animations (only for icons) - IDs preserved exactlylottie.loadAnimation({
    container: document.getElementById('iconTitle'),
    renderer: 'svg',
    loop: true,
    autoplay: true,
    path: 'https://assets10.lottiefiles.com/packages/lf20_j1adxtyb.json'
});

lottie.loadAnimation({
    container: document.getElementById('iconPerfect'),
    renderer: 'svg',
    loop: true,
    autoplay: true,
    path: 'https://assets4.lottiefiles.com/packages/lf20_jtbfg2nb.json'
});

lottie.loadAnimation({
    container: document.getElementById('iconSupport'),
    renderer: 'svg',
    loop: true,
    autoplay: true,
    path: 'https://assets2.lottiefiles.com/packages/lf20_jcikwtux.json'
});

lottie.loadAnimation({
    container: document.getElementById('iconDev'),
    renderer: 'svg',
    loop: true,
    autoplay: true,
    path: 'https://assets7.lottiefiles.com/packages/lf20_w51pcehl.json'
});

lottie.loadAnimation({
    container: document.getElementById('iconContact'),
    renderer: 'svg',
    loop: true,
    autoplay: true,
    path: 'https://assets3.lottiefiles.com/packages/lf20_5ngs2ksb.json'
});

// Get device info from Android - LOGIC PRESERVED EXACTLY
function loadDeviceInfo() {
    if (window.Android && Android.getDeviceInfo) {
        try {
            const info = Android.getDeviceInfo();
            const lines = info.split('\n');
            
            const deviceInfoDiv = document.getElementById('deviceInfo');
            deviceInfoDiv.innerHTML = '';
                        lines.forEach(line => {
                if (line.trim()) {
                    const [label, value] = line.split(':').map(s => s.trim());
                    if (label && value) {
                        const row = document.createElement('div');
                        row.className = 'info-row';
                        
                        const labelSpan = document.createElement('span');
                        labelSpan.className = 'info-label';
                        labelSpan.textContent = label + ':';
                        
                        const valueSpan = document.createElement('span');
                        valueSpan.className = 'info-value';
                        valueSpan.textContent = value;
                        
                        row.appendChild(labelSpan);
                        row.appendChild(valueSpan);
                        deviceInfoDiv.appendChild(row);
                    }
                }
            });
            
        } catch (e) {
            document.getElementById('deviceInfo').innerHTML = 
                '<div class="info-row"><span class="info-label">Device:</span><span class="info-value">Unknown</span></div>';
        }
    }
}

// Load expire status from Android - LOGIC PRESERVED
function loadExpireStatus() {
    if (window.Android && Android.getExpireStatus) {
        try {
            const status = Android.getExpireStatus();
            const statusText = document.getElementById('statusText');
            if (status && status.trim()) {
                statusText.textContent = status;
            }
        } catch (e) {
            // Fallback to default
        }
    }
}

// Simple countdown timer - LOGIC PRESERVED EXACTLY
let seconds = 10;
const timerElement = document.getElementById('timer');

const countdown = setInterval(() => {
    seconds--;    timerElement.textContent = seconds;
    
    if (seconds <= 5) {
        timerElement.style.color = '#d32f2f';
        timerElement.style.fontWeight = '800';
    }
    
    if (seconds <= 0) {
        clearInterval(countdown);
        if (window.Android && Android.close) {
            Android.close();
        }
    }
}, 1000);

// Initialize - LOGIC PRESERVED
document.addEventListener('DOMContentLoaded', function() {
    loadDeviceInfo();
    loadExpireStatus();
    
    // Fallback auto-close
    setTimeout(() => {
        if (window.Android && Android.close) {
            Android.close();
        }
    }, 10000);
});

// Handle visibility change for better UX
document.addEventListener('visibilitychange', function() {
    if (document.visibilityState === 'visible') {
        // Resume animations if needed
    }
});
</script>
</body>
</html>
"""
}