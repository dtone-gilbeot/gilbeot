package ai.gilbeot.app.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.content.Context
import android.util.Log

/** Pleos Vehicle의 하향등과 방향지시등 상태를 전달한다. */
class VehicleSignals(context: Context) {
    private val vehicle = Vehicle(context.applicationContext)
    var onLowBeam: ((String) -> Unit)? = null
    var onTurnSignal: ((String) -> Unit)? = null

    private val lightListener = object : LightStateListener {
        /** 하향등 상태 변경을 전달한다. */
        override fun onLightStateUpdated(state: LightState?) {
            val text = state?.toString() ?: "UNKNOWN"
            log("lowBeam state=$text")
            onLowBeam?.invoke(text)
        }

        /** 하향등 리스너 오류를 기록한다. */
        override fun onFailed(e: Exception) { log("lowBeam onFailed=$e") }
    }

    private val turnListener = object : TurnSignalStateListener {
        /** 방향지시등 상태 변경을 전달한다. */
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) {
            val text = state?.toString() ?: "UNKNOWN"
            log("turnSignal state=$text")
            onTurnSignal?.invoke(text)
        }

        /** 방향지시등 리스너 오류를 기록한다. */
        override fun onFailed(e: Exception) { log("turnSignal onFailed=$e") }
    }

    /** Vehicle을 초기화하고 두 리스너를 등록한다. */
    fun start() {
        try {
            vehicle.initialize()
            vehicle.getLight().registerLowBeamLightState(lightListener)
            vehicle.getTurnSignal().registerTurnSignalState(turnListener)
            log("vehicle listeners registered")
        } catch (error: Exception) {
            log("vehicle start failed=$error")
        }
    }

    /** 등록한 차량 신호 리스너와 Vehicle 연결을 해제한다. */
    fun stop() {
        try {
            vehicle.getLight().unregisterLowBeamLightState(lightListener)
            vehicle.getTurnSignal().unregisterTurnSignalState(turnListener)
            vehicle.release()
            log("vehicle listeners released")
        } catch (error: Exception) {
            log("vehicle stop failed=$error")
        }
    }

    /** GILBEOT 태그로 정보 로그를 남긴다. */
    private fun log(message: String) = Log.i(TAG, message)

    private companion object {
        const val TAG = "GILBEOT"
    }
}
