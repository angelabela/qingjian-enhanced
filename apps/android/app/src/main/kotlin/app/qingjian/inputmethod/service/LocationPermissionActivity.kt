package app.qingjian.inputmethod.service

import android.Manifest
import android.app.Activity
import android.os.Bundle

/** 由输入法以独立 Activity 申请定位权限，避免直接在 InputMethodService 中弹权限框。 */
class LocationPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_LOCATION,
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) finish()
    }

    companion object {
        private const val REQUEST_LOCATION = 7302
    }
}
