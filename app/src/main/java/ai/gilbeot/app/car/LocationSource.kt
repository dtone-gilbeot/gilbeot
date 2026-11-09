package ai.gilbeot.app.car

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log

/** GPS와 fused 위치를 받고, 사용할 수 없으면 서울 좌표를 제공한다. */
class LocationSource(private val context: Context) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val activeProviders = mutableSetOf<String>()
    var onLocation: ((latitude: Double, longitude: Double, provider: String) -> Unit)? = null

    private val listener = object : LocationListener {
        /** 새 위치를 화면과 로그에 전달한다. */
        override fun onLocationChanged(location: Location) {
            log("location provider=${location.provider} lat=${location.latitude} lng=${location.longitude} speed=${location.speed}")
            onLocation?.invoke(location.latitude, location.longitude, location.provider ?: "unknown")
        }

        /** 제공자 상태 변경을 기록한다. */
        @Deprecated("플랫폼 호환 콜백")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            log("location status provider=$provider status=$status")
        }

        /** 제공자가 켜진 사실을 기록한다. */
        override fun onProviderEnabled(provider: String) { log("location provider enabled=$provider") }

        /** 제공자가 꺼진 사실을 기록한다. */
        override fun onProviderDisabled(provider: String) { log("location provider disabled=$provider") }
    }

    /** 위치 권한이 있을 때 gps/fused 갱신을 등록한다. */
    fun start() {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            log("location permission denied; fallback Seoul")
            emitFallback()
            return
        }
        if (activeProviders.isNotEmpty()) return
        val enabled = try {
            manager.getProviders(true)
        } catch (error: Exception) {
            log("location providers failed=$error")
            emptyList()
        }
        val candidates = listOf(LocationManager.GPS_PROVIDER, FUSED_PROVIDER).filter { it in enabled }
        log("location enabledProviders=$enabled selected=$candidates")
        candidates.forEach { provider ->
            try {
                manager.getLastKnownLocation(provider)?.let(listener::onLocationChanged)
                manager.requestLocationUpdates(provider, 500L, 0f, listener)
                activeProviders += provider
                log("location listener registered provider=$provider")
            } catch (error: Exception) {
                log("location provider=$provider failed=$error")
            }
        }
        if (activeProviders.isEmpty()) emitFallback()
    }

    /** 서울 기본 좌표를 한 번 전달한다. */
    private fun emitFallback() {
        log("location fallback lat=$SEOUL_LAT lng=$SEOUL_LNG")
        onLocation?.invoke(SEOUL_LAT, SEOUL_LNG, "fallback")
    }

    /** 위치 갱신을 해제한다. */
    fun stop() {
        try {
            manager.removeUpdates(listener)
            activeProviders.clear()
            log("location source stopped")
        } catch (error: Exception) {
            log("location stop failed=$error")
        }
    }

    /** GILBEOT 태그로 정보 로그를 남긴다. */
    private fun log(message: String) = Log.i(TAG, message)

    private companion object {
        const val TAG = "GILBEOT"
        const val FUSED_PROVIDER = "fused"
        const val SEOUL_LAT = 37.5665
        const val SEOUL_LNG = 126.9780
    }
}
