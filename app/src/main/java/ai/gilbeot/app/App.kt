package ai.gilbeot.app

import ai.gilbeot.app.car.LocationSource
import ai.gilbeot.app.car.NaviSource
import ai.gilbeot.app.car.Speaker
import ai.gilbeot.app.car.SpeedSource
import ai.gilbeot.app.car.VehicleSignals
import android.app.Application

/** M0 차량 SDK 래퍼를 라이브러리 없이 조립한다. */
class App : Application() {
    lateinit var naviSource: NaviSource
        private set
    lateinit var speedSource: SpeedSource
        private set
    lateinit var vehicleSignals: VehicleSignals
        private set
    lateinit var locationSource: LocationSource
        private set
    lateinit var speaker: Speaker
        private set

    /** 앱 프로세스에서 사용할 래퍼 객체를 만든다. */
    override fun onCreate() {
        super.onCreate()
        naviSource = NaviSource(this)
        speedSource = SpeedSource(this)
        vehicleSignals = VehicleSignals(this)
        locationSource = LocationSource(this)
        speaker = Speaker(this)
    }
}

