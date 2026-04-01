package com.cpm.cleave.ui.features.joingroup

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

class JoinGroupQrAnalyzer(
    private val barcodeScanner: BarcodeScanner,
    private val onCodeDetected: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val hasDispatchedResult = AtomicBoolean(false)

    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        if (hasDispatchedResult.get()) {
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        barcodeScanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                val rawValue = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                if (!rawValue.isNullOrBlank() && hasDispatchedResult.compareAndSet(false, true)) {
                    onCodeDetected(rawValue)
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }
}
