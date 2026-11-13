package android.MetaCore

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.mundo.MundoCore
import org.lsposed.lsparanoid.Obfuscate
import java.text.SimpleDateFormat
import java.util.*
import android.widget.Toast

@Obfuscate
class nk {

    companion object {
        @Volatile
        private var is_False: Boolean = false

        @JvmField
        @Volatile
        var Msg: String = "Ready"

        const val PREFERENCE_NAME: String = "license_cache"
        
        // ✅ Panel Connection URL
        var ActivationUrl: String = "https://panel.battlegrounds-hub.xyz/connect.php"

        @JvmStatic
        fun getActivatedSdk(): Boolean {
            Msg = "SDK Activated"
            return true
        }

        @JvmStatic
        fun getServerMessage(): String {
            return Msg
        }

        @JvmStatic
        fun ismsg(msg: String?) {
            if (msg == null) return
            val ctx = MundoCore.getContext() ?: return
            Handler(Looper.getMainLooper()).post {
                try {
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                } catch (_: Exception) {}
            }
        }

        @JvmStatic
        fun setHidden(status: String?) {
            if (status == null) return
            try {
                val value = status.equals("online", ignoreCase = true)
                val clazz = Class.forName("android.MetaCore.nk")
                val field = clazz.getDeclaredField("is_False")
                field.isAccessible = true
                field.setBoolean(null, value)
                
                val ctx = MundoCore.getContext()
                if (ctx != null) {
                    val sp = ctx.getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE)
                    sp.edit().apply {
                        putString("server_status", status)
                        apply()
                    }
                }
                
                Msg = if (value) {
                    "✅ Server Online"
                } else {
                    "❌ Server $status - Functions Blocked"
                }
                
            } catch (e: Exception) { }
        }

        @JvmStatic
        fun setHidden(value: Boolean) {
            setHidden(if (value) "online" else "offline")
        }

        @JvmStatic
        fun GAH(): Boolean {
            return true
        }

        @JvmStatic
        fun getUrlHidden(): String {
            return try {
                val clazz = Class.forName("android.MetaCore.nk")
                val field = clazz.getDeclaredField("ActivationUrl")
                field.isAccessible = true
                field.get(null) as? String ?: 获取接口地址()
            } catch (_: Exception) {
                获取接口地址()
            }
        }

        @JvmStatic
        fun 获取接口地址(): String {
            // ✅ Fallback Connection URL
            return "https://panel.battlegrounds-hub.xyz/connect.php"
        }
        
        @JvmStatic
        fun isSystemApp(): Boolean {
            Msg = "✅ SDK Bypassed"
            return true
        }
        
        @JvmStatic
        fun checkExpiryManually(): String {
            val context = MundoCore.getContext() ?: return "No context"
            val sp = context.getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE)
            val expiryStr = sp.getString("expiry", null)
            if (expiryStr == null) return "No expiry date"
            return try {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val expiryDate = sdf.parse(expiryStr)
                if (expiryDate == null) return "Invalid date"
                val currentTime = System.currentTimeMillis()
                val expiryTime = expiryDate.time
                if (currentTime < expiryTime) {
                    val remaining = expiryTime - currentTime
                    val days = remaining / (1000 * 60 * 60 * 24)
                    val hours = (remaining % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60)
                    "Valid for ${days}d ${hours}h"
                } else {
                    "EXPIRED ${(currentTime - expiryTime) / (1000 * 60 * 60 * 24)} days ago"
                }
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        }
        
        @JvmStatic
        fun loadSavedStatus() {
            try {
                val ctx = MundoCore.getContext() ?: return
                val sp = ctx.getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE)
                val savedStatus = sp.getString("server_status", "online")
                if (savedStatus != null) {
                    setHidden(savedStatus)
                }
                getActivatedSdk()
            } catch (_: Exception) {}
        }
    }
}