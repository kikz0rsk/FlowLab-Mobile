package com.danizek.dp

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.util.Log
import androidx.core.app.ActivityCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine


class MainActivity : FlutterActivity() {
    companion object {
        const val REQUEST_CODE = 500
    }

    private val vpnApi = VpnApiMessenger(this)
    private val REQUEST_EXTERNAL_STORAGE: Int = 1
    private var PERMISSIONS_STORAGE: Array<String> = arrayOf(
        android.Manifest.permission.READ_EXTERNAL_STORAGE,
        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        VpnApi.setUp(flutterEngine.dartExecutor.binaryMessenger, vpnApi)
        try {
            Class.forName("dalvik.system.CloseGuard")
                .getMethod("setEnabled", Boolean::class.javaPrimitiveType)
                .invoke(null, true)
        } catch (_: ReflectiveOperationException) {
        }
        verifyStoragePermissions()
        val mediaStorageDir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

        // Create the storage directory if it does not exist
        if (!mediaStorageDir.exists()) {
            if (!mediaStorageDir.mkdirs()) {
                Log.w("error", "failed to create directory")
            } else {
                Log.w("success", "successfully created directory")
            }
        }

//        val filePath = mediaStorageDir.resolve("text.txt")
//        val file = File(filePath.toString())
//        val outputStr = file.outputStream()
//        val writer = outputStr.writer()
//        writer.write("tst")
//        writer.close()
    }

    fun verifyStoragePermissions() {

        // Check if we have write permission
        val permission =
            ActivityCompat.checkSelfPermission(activity, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)

        if (permission != PackageManager.PERMISSION_GRANTED) {
            // We don't have permission so prompt the user
            ActivityCompat.requestPermissions(
                activity,
                PERMISSIONS_STORAGE,
                REQUEST_EXTERNAL_STORAGE
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            vpnApi.canStartVpn = true
            vpnApi.startVpn()
        } else if (requestCode == 900 && resultCode == Activity.RESULT_OK) {
            vpnApi.canOpenTree = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        vpnApi.unbind()
    }
}
