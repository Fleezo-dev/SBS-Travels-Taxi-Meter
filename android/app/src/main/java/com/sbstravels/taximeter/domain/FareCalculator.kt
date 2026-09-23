package com.sbstravels.taximeter.domain

import kotlin.math.max

object FareCalculator {
    fun meterFare(distanceKm: Double, waitingMinutes: Double, extras: Double, r: MeterRules): MeterSnapshot {
        val d = max(0.0, distanceKm)
        val w = max(0.0, waitingMinutes)
        val e = max(0.0, extras)
        val distanceFare = d * r.perKm
        val waitingFare = w * r.waitingPerMinute
        return MeterSnapshot(d, w, r.baseFare, distanceFare, waitingFare, e, r.baseFare + distanceFare + waitingFare + e)
    }

    fun hourlyFare(hours: Int, distanceKm: Double, extras: Double, r: MeterRules): Double {
        val h = max(0, hours)
        val d = max(0.0, distanceKm)
        val freeKm = h * r.freeKmPerHour
        return h * r.hourlyRate + max(0.0, d - freeKm) * r.excessKmRate + max(0.0, extras)
    }
}
