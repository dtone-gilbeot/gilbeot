package ai.gilbeot.app.ui

import android.Manifest
import android.app.Activity
import android.car.Car
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log

/**
 * 앱 아이콘으로 실행하면 처음 열리는 화면이다. 권한만 묻고, 주행 화면(MainActivity)을 새 task로 연 뒤 사라진다.
 * 이유(11/09 실측): 시스템 권한 창이 주행 화면과 같은 task에 들어가면, 창이 닫힌 뒤에도
 * 주행을 시작하면 길벗 화면 전체가 "주행 중에는 사용할 수 없습니다"로 가려졌다.
 */
class LaunchActivity : Activity() {

    /** 빠진 권한이 있으면 요청하고, 없으면 바로 주행 화면으로 넘어간다. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val missing = REQUIRED.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            openDriveScreen()
        } else {
            Log.i(TAG, "launch requestPermissions permissions=$missing")
            requestPermissions(missing.toTypedArray(), REQUEST_CODE)
        }
    }

    /** 허용·거부와 상관없이 주행 화면을 연다. 거부하면 차속 없이 위치는 서울 기본값으로 동작한다. */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.i(TAG, "launch permission result " + permissions.indices.joinToString { "${permissions[it]}=${grantResults.getOrNull(it)}" })
        openDriveScreen()
    }

    /** 받은 extra(목적지 등)를 그대로 넘겨 주행 화면을 새 task로 열고 이 task는 지운다. */
    private fun openDriveScreen() {
        val drive = Intent(this, MainActivity::class.java)
        intent?.extras?.let(drive::putExtras)
        drive.putExtra(MainActivity.EXTRA_FROM_LAUNCH, true)
        drive.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(drive)
        finishAndRemoveTask()
    }

    private companion object {
        const val TAG = "GILBEOT"
        const val REQUEST_CODE = 101
        val REQUIRED = listOf(Car.PERMISSION_SPEED, Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
