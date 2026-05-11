package com.example.displayapp.domain.model

data class VehicleData(
    val speed: Int = 0,
    val batteryPercent: Int = 0,
    val voltage: Float = 0f,
    val current: Float = 0f,
    val temperature: Int = 0,
    val odometer: Float = 0f,
    val vehicleMode: VehicleMode = VehicleMode.PARK,
    val leftIndicator: Boolean = false,
    val rightIndicator: Boolean = false,
    val headlamp: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

enum class VehicleMode { PARK, ECO, NORMAL, SPORT }
