package com.shilapi.xcertplay.compat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * 网络连接兼容性处理。
 *
 * API 21+ 提供 [ConnectivityManager.NetworkCallback]，API 17 只能通过
 * [ConnectivityManager.CONNECTIVITY_ACTION] 广播监听网络变化。
 *
 * @author TRAE AI
 * @since 2026-10-08
 */
object NetworkCompat {

    /**
     * 网络状态回调接口。
     */
    interface NetworkCallback {
        fun onAvailable()
        fun onLost()
    }

    /**
     * 注册网络状态监听。
     *
     * @return 注册凭据，用于 [unregisterNetworkCallback]
     */
    fun registerNetworkCallback(
        context: Context,
        callback: NetworkCallback
    ): Any {
        return if (ApiLevel.isAtLeast(ApiLevel.LOLLIPOP)) {
            // API 21+：使用 NetworkCallback
            registerNetworkCallbackApi21(context, callback)
        } else {
            // API 17-20：使用 BroadcastReceiver
            registerNetworkCallbackApi17(context, callback)
        }
    }

    /**
     * API 21+ 的网络监听实现。
     */
    private fun registerNetworkCallbackApi21(
        context: Context,
        callback: NetworkCallback
    ): Any {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                callback.onAvailable()
            }

            override fun onLost(network: Network) {
                callback.onLost()
            }
        }

        cm.registerNetworkCallback(request, networkCallback)
        return networkCallback
    }

    /**
     * API 17-20 的网络监听实现。
     */
    @Suppress("DEPRECATION")
    private fun registerNetworkCallbackApi17(
        context: Context,
        callback: NetworkCallback
    ): Any {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ConnectivityManager.CONNECTIVITY_ACTION) {
                    val noConnectivity = intent.getBooleanExtra(
                        ConnectivityManager.EXTRA_NO_CONNECTIVITY, false
                    )
                    if (noConnectivity) {
                        callback.onLost()
                    } else {
                        callback.onAvailable()
                    }
                }
            }
        }

        val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        context.registerReceiver(receiver, filter)
        return receiver
    }

    /**
     * 注销网络监听。
     */
    fun unregisterNetworkCallback(context: Context, registration: Any) {
        when (registration) {
            is ConnectivityManager.NetworkCallback -> {
                if (ApiLevel.isAtLeast(ApiLevel.LOLLIPOP)) {
                    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    cm.unregisterNetworkCallback(registration)
                }
            }
            is BroadcastReceiver -> {
                try {
                    context.unregisterReceiver(registration)
                } catch (_: Exception) {
                    // 未注册时忽略
                }
            }
        }
    }

    /**
     * 检查是否有网络连接。
     */
    @Suppress("DEPRECATION")
    fun isConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false

        return if (ApiLevel.isAtLeast(ApiLevel.M)) {
            // API 23+：使用 NetworkCapabilities
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            // API 17-22：使用旧版 API
            val networkInfo = cm.activeNetworkInfo ?: return false
            networkInfo.isConnected
        }
    }
}
