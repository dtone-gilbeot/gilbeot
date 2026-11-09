package ai.gilbeot.app.car

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlin.math.abs

/** AAOS 차량 속도를 m/s와 km/h로 전달한다. */
class SpeedSource(private val context: Context) {
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    var onSpeed: ((mps: Float, kmh: Float) -> Unit)? = null

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        private var lastLogged = Float.NaN

        /** 속도 변경을 읽어 단위를 함께 기록한다. */
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val mps = value.value as? Float ?: return
            val kmh = mps * 3.6f
            if (lastLogged.isNaN() || abs(mps - lastLogged) >= 0.1f) {
                lastLogged = mps
                log("speed m/s=$mps km/h=$kmh")
            }
            onSpeed?.invoke(mps, kmh)
        }

        /** 속성 읽기 오류를 기록한다. */
        override fun onErrorEvent(propId: Int, zone: Int) { log("speed onError prop=$propId zone=$zone") }
    }

    /** 권한이 있을 때만 속도 콜백을 등록한다. */
    fun start() {
        if (context.checkSelfPermission(Car.PERMISSION_SPEED) != PackageManager.PERMISSION_GRANTED) {
            log("speed permission denied; idle")
            return
        }
        if (propertyManager != null) return
        try {
            car = Car.createCar(context)
            propertyManager = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            val registered = propertyManager?.registerCallback(
                callback,
                VehiclePropertyIds.PERF_VEHICLE_SPEED,
                CarPropertyManager.SENSOR_RATE_NORMAL,
            )
            log("speed callback registered=$registered")
        } catch (error: Exception) {
            log("speed start failed=$error")
        }
    }

    /** 속도 콜백과 차량 연결을 해제한다. */
    fun stop() {
        try {
            propertyManager?.unregisterCallback(callback)
            car?.disconnect()
            log("speed source stopped")
        } catch (error: Exception) {
            log("speed stop failed=$error")
        } finally {
            propertyManager = null
            car = null
        }
    }

    /** GILBEOT 태그로 정보 로그를 남긴다. */
    private fun log(message: String) = Log.i(TAG, message)

    private companion object {
        const val TAG = "GILBEOT"
    }
}
