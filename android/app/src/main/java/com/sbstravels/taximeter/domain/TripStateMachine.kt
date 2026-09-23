package com.sbstravels.taximeter.domain

object TripStateMachine {
    fun canTransition(from: TripState, to: TripState): Boolean = when (from) {
        TripState.ASSIGNED -> to in setOf(TripState.ACCEPTED, TripState.CANCELLED)
        TripState.ACCEPTED -> to in setOf(TripState.STARTED, TripState.CANCELLED)
        TripState.STARTED -> to in setOf(TripState.RUNNING, TripState.CANCELLED)
        TripState.RUNNING -> to in setOf(TripState.WAITING, TripState.COMPLETED, TripState.CANCELLED)
        TripState.WAITING -> to in setOf(TripState.RUNNING, TripState.COMPLETED, TripState.CANCELLED)
        TripState.COMPLETED, TripState.CANCELLED -> false
    }
}
