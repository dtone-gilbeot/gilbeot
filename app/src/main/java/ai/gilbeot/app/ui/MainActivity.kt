package ai.gilbeot.app.ui

import ai.gilbeot.app.App
import ai.gilbeot.app.R
import android.Manifest
import android.app.Activity
import android.car.Car
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView

/** 주행 화면의 수명주기를 맡는다. 권한 요청은 다른 task의 LaunchActivity가 한다. */
class MainActivity : Activity() {
    private lateinit var controller: GuideController
    private lateinit var settingsDialog: PromiseSettingsDialog

    /** M2 화면과 컨트롤러를 만들고 첫 경로를 요청한다. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!intent.getBooleanExtra(EXTRA_FROM_LAUNCH, false) && hasMissingPermissions()) {
            // 직접 실행됐는데 권한이 없으면 권한 창을 이 task에 띄우지 않고 LaunchActivity로 돌린다.
            startActivity(Intent(this, LaunchActivity::class.java).apply { intent.extras?.let(::putExtras); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        val views = findViews()
        settingsDialog = PromiseSettingsDialog(this)
        views.settingsButton.setOnClickListener { settingsDialog.show() }
        controller = GuideController(application as App, views) { stopped ->
            if (!stopped) settingsDialog.dismiss()
        }
        controller.start(intent.getStringExtra(EXTRA_DEST), promiseConfig(intent))
        controller.startGrantedSources()
    }

    /** 같은 프로세스로 온 목적지 요청을 전달한다. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        controller.requestRoute(intent.getStringExtra(EXTRA_DEST), promiseConfig(intent))
    }

    /** 컨트롤러에 필요한 화면 요소를 한 번만 찾는다. */
    private fun findViews() = GuideController.Views(
        status = findViewById(R.id.statusText),
        subtitle = findViewById(R.id.subtitleText),
        voiceOff = findViewById(R.id.voiceOffText),
        turnArrow = findViewById<ImageView>(R.id.turnArrow),
        turnDistance = findViewById(R.id.turnDistance),
        turnDescription = findViewById(R.id.turnDescription),
        turnBefore = findViewById(R.id.turnBeforeText),
        sunsetPromise = findViewById(R.id.sunsetPromiseText),
        breakPromise = findViewById(R.id.breakPromiseText),
        demoScale = findViewById(R.id.demoScaleText),
        restButton = findViewById<Button>(R.id.restButton),
        settingsButton = findViewById<Button>(R.id.settingsButton),
    )

    /** 디버그 인텐트의 시각 주입과 휴식 배속을 약속 설정으로 읽는다. */
    private fun promiseConfig(intent: Intent) = PromiseController.Config(
        injectedNow = intent.getStringExtra(EXTRA_NOW),
        timeScale = intent.getFloatExtra(EXTRA_TIME_SCALE, 1f).toDouble(),
    )

    /** 차속·정밀 위치 권한 중 빠진 것이 있는지 본다. */
    private fun hasMissingPermissions(): Boolean =
        listOf(Car.PERMISSION_SPEED, Manifest.permission.ACCESS_FINE_LOCATION)
            .any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    /** 화면 종료 시 SDK와 주기 작업을 정리한다. */
    override fun onDestroy() {
        if (::controller.isInitialized) {
            settingsDialog.dismiss()
            controller.stop()
        }
        super.onDestroy()
    }

    /** GILBEOT 태그로 권한 결과를 기록한다. */
    private fun log(message: String) = Log.i(TAG, message)

    companion object {
        const val EXTRA_FROM_LAUNCH = "fromLaunch"
        private const val TAG = "GILBEOT"
        private const val EXTRA_DEST = "dest"
        private const val EXTRA_NOW = "now"
        private const val EXTRA_TIME_SCALE = "timeScale"
    }
}
