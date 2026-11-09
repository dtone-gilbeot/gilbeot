package ai.gilbeot.app.ui

import ai.gilbeot.app.R
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch

/**
 * 정차 중 두 운전 약속을 켜고 끄는 설정 창이다.
 * 별도 Activity가 아니라 메인 화면 위 다이얼로그다: 11/09 실측에서 주행 최적화가 아닌 Activity를
 * 한 번 띄우면 주행 중 길벗 화면 전체가 "주행 중에는 사용할 수 없습니다"로 가려졌다.
 */
class PromiseSettingsDialog(private val activity: Activity) {
    private var dialog: Dialog? = null

    /** 저장된 값을 스위치에 반영해 창을 열고, 바꾸는 즉시 저장한다. */
    fun show() {
        if (dialog?.isShowing == true) return
        val preferences = activity.getSharedPreferences(PromisePreferences.FILE_NAME, Context.MODE_PRIVATE)
        val created = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
        created.setContentView(R.layout.activity_promise_settings)
        created.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val sunset = created.findViewById<Switch>(R.id.sunsetPromiseSwitch)
        val rest = created.findViewById<Switch>(R.id.breakPromiseSwitch)
        sunset.isChecked = preferences.getBoolean(PromisePreferences.SUNSET_ENABLED, true)
        rest.isChecked = preferences.getBoolean(PromisePreferences.BREAK_ENABLED, true)
        sunset.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean(PromisePreferences.SUNSET_ENABLED, checked).apply()
        }
        rest.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean(PromisePreferences.BREAK_ENABLED, checked).apply()
        }
        created.findViewById<Button>(R.id.settingsDoneButton).setOnClickListener { dismiss() }
        dialog = created
        created.show()
    }

    /** 차가 움직이기 시작하거나 완료를 누르면 창을 닫는다. */
    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }
}
