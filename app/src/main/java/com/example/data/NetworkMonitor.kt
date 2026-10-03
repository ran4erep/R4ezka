package com.example.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

object NetworkMonitor {
    private val _isOnline = MutableStateFlow(true)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private var isInitialized = false
    private var connectivityManager: ConnectivityManager? = null

    fun checkCurrentConnectivity(): Boolean {
        val cm = connectivityManager ?: return true
        return try {
            val activeNetwork = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        connectivityManager = cm
        if (cm == null) {
            _isOnline.value = true
            return
        }

        _isOnline.value = checkCurrentConnectivity()

        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _isOnline.value = true
                    SafeDns.clearCache()
                }

                override fun onLost(network: Network) {
                    _isOnline.value = checkCurrentConnectivity()
                    SafeDns.clearCache()
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    _isOnline.value = hasInternet
                    SafeDns.clearCache()
                }
            })
        } catch (_: Exception) {
            // Резервный режим если системный callback ограничен
            _isOnline.value = checkCurrentConnectivity()
        }
    }

    /**
     * Позволяет отметить невозможность сетевого взаимодействия при исключениях,
     * только если само устройство действительно потеряло подключение к сети.
     * Ошибка отдельного заблокированного зеркала НЕ переводит всё приложение в оффлайн.
     */
    fun handleNetworkException(throwable: Throwable) {
        if (throwable is UnknownHostException ||
            throwable is ConnectException ||
            throwable is SocketTimeoutException ||
            (throwable is IOException && throwable.message?.contains("DNS", ignoreCase = true) == true)
        ) {
            if (!checkCurrentConnectivity()) {
                _isOnline.value = false
            }
        }
    }

    fun markNetworkFailed() {
        _isOnline.value = false
    }

    fun markNetworkSucceeded() {
        _isOnline.value = true
    }
}
