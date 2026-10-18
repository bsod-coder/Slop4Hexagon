package com.slop4hexagon.domain.engine

import android.content.Context
import android.os.Build
import android.util.Log
import com.slop4hexagon.domain.model.BackendCapabilities
import com.slop4hexagon.domain.model.BackendId
import com.slop4hexagon.domain.model.BackendStatus
import com.slop4hexagon.domain.model.ModelFormat

/**
 * QNN/HTP adapter boundary.
 * Without proprietary QNN SDK (libQnnHtp.so, headers, DLC toolchain), this reports UNSUPPORTED.
 * Never fakes success. JNI boundary isolated in QnnHtpAdapter.
 */
class QnnHtpBackend : InferenceBackend {
    override val id = BackendId.HTP
    override val capabilities = BackendCapabilities(
        backendId = BackendId.HTP,
        supportedFormats = setOf(ModelFormat.DLC),
        supportsImageToImage = false, // depends on compiled graph
        supportsDenoisingStrength = false,
        supportsQuantizedInt8 = true,
        maxSupportedDimension = 512,
        requiresConversion = true,
        notes = "Qualcomm Hexagon HTP via QNN SDK (QAIRT). Requires DLC compiled with qnn-model-lib-generator for SM7325-AF / Hexagon 682. Missing SDK artifacts -> UNSUPPORTED."
    )

    private var status: BackendStatus = BackendStatus.UNSUPPORTED
    private var lastError: String? = null

    override fun getStatus(): BackendStatus = status

    override fun isAvailable(context: Context?): Boolean {
        // Check device target: Snapdragon 778G+ = SM7325-AF, Hexagon 682, HTP v68? We don't claim version without verification.
        // We treat missing runtime as normal, not error.
        val soc = try { if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null } catch (_: Exception) { null }
        val hardware = Build.HARDWARE // often "qcom"
        val hasQcom = hardware.equals("qcom", ignoreCase = true) || soc?.contains("778", ignoreCase = true) == true
        // Without runtime we still report UNSUPPORTED initially; init will probe native
        if (!hasQcom) {
            status = BackendStatus.UNSUPPORTED
            lastError = "Device not identified as Snapdragon 778G+ (HARDWARE=$hardware, SOC_MODEL=$soc). HTP unavailable."
            return false
        }
        return status == BackendStatus.AVAILABLE || tryProbeNative()
    }

    private fun tryProbeNative(): Boolean {
        return try {
            val nativeStatus = QnnHtpAdapter.getHtpStatus()
            when (nativeStatus) {
                QnnHtpAdapter.STATUS_AVAILABLE -> { status = BackendStatus.AVAILABLE; true }
                QnnHtpAdapter.STATUS_INITIALIZED -> { status = BackendStatus.INITIALIZED; true }
                else -> false
            }
        } catch (e: UnsatisfiedLinkError) {
            lastError = "QNN native lib missing: ${e.message}. See docs/HTP_INTEGRATION.md"
            status = BackendStatus.UNSUPPORTED
            false
        } catch (e: Exception) {
            lastError = "HTP probe failed: ${e.message}"
            status = BackendStatus.FAILED
            false
        }
    }

    override fun initialize(context: Context?): BackendStatus {
        // Must have QNN SDK artifacts: libQnnHtp.so, libQnnSystem.so, SDK headers at compile time
        try {
            val rc = QnnHtpAdapter.initQnn()
            status = when (rc) {
                QnnHtpAdapter.INIT_OK -> BackendStatus.INITIALIZED
                QnnHtpAdapter.INIT_MISSING_SDK -> {
                    lastError = "QNN SDK artifacts missing. Provide qnn-htp SDK, set QNN_SDK_ROOT, and rebuild with -DQNN_AVAILABLE=ON"
                    BackendStatus.UNSUPPORTED
                }
                QnnHtpAdapter.INIT_UNSUPPORTED_DEVICE -> {
                    lastError = "Device not supported for HTP. Need SM7325-AF driver with HTP support."
                    BackendStatus.UNSUPPORTED
                }
                else -> {
                    lastError = "QNN init failed code=$rc"
                    BackendStatus.FAILED
                }
            }
        } catch (e: UnsatisfiedLinkError) {
            lastError = "Native lib not loaded: ${e.message}"
            status = BackendStatus.UNSUPPORTED
        } catch (e: Exception) {
            lastError = e.message
            status = BackendStatus.FAILED
            Log.e("QnnHtpBackend", "init exception", e)
        }
        return status
    }

    override fun shutdown() {
        try { QnnHtpAdapter.shutdown() } catch (_: Exception) {}
        status = BackendStatus.UNSUPPORTED
    }

    fun getLastError(): String? = lastError
}

/**
 * Isolated JNI boundary. Buildable without QNN SDK via stub.
 */
object QnnHtpAdapter {
    const val STATUS_AVAILABLE = 1
    const val STATUS_INITIALIZED = 2
    const val STATUS_UNAVAILABLE = 0

    const val INIT_OK = 0
    const val INIT_MISSING_SDK = 1
    const val INIT_UNSUPPORTED_DEVICE = 2
    const val INIT_FAILED = -1

    private var loaded = false

    init {
        try {
            System.loadLibrary("slop4hexagon_jni")
            loaded = true
        } catch (_: UnsatisfiedLinkError) {
            loaded = false
        }
    }

    external fun nativeGetHtpStatus(): Int
    external fun nativeInitQnn(): Int
    external fun nativeShutdown()

    fun getHtpStatus(): Int = if (loaded) nativeGetHtpStatus() else STATUS_UNAVAILABLE
    fun initQnn(): Int = if (loaded) nativeInitQnn() else INIT_MISSING_SDK
    fun shutdown() { if (loaded) nativeShutdown() }
}
