package com.sbstravels.taximeter.domain

data class MeterRules(
    val baseFare: Double,
    val perKm: Double,
    val waitingPerMinute: Double,
    val hourlyRate: Double,
    val freeKmPerHour: Double,
    val excessKmRate: Double
)

data class MeterSnapshot(
    val distanceKm: Double = 0.0,
    val waitingMinutes: Double = 0.0,
    val baseFare: Double = 0.0,
    val distanceFare: Double = 0.0,
    val waitingFare: Double = 0.0,
    val extras: Double = 0.0,
    val totalFare: Double = 0.0
)

enum class TripState { ASSIGNED, ACCEPTED, STARTED, RUNNING, WAITING, COMPLETED, CANCELLED }
