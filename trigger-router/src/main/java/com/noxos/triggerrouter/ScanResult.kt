package com.noxos.triggerrouter

sealed class ScanResult {
    data class Success(val exifData: ExifData, val advisory: FileAdvisory? = null) : ScanResult()
    data class Failure(val reason: String) : ScanResult()
    data class Error(val message: String) : ScanResult()
}
