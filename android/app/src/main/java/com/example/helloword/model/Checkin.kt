package com.example.helloword.model

import com.google.gson.annotations.SerializedName

data class CheckinDay(
    val date: String,
    val weekday: Int,
    val checked: Boolean,
    @SerializedName("is_today") val isToday: Boolean
)

data class CheckinData(
    @SerializedName("user_id") val userId: Int,
    val today: String,
    val days: List<CheckinDay>,
    @SerializedName("week_count") val weekCount: Int,
    @SerializedName("streak_days") val streakDays: Int,
    @SerializedName("next_checkin_after_seconds") val nextCheckinAfterSeconds: Long
)

data class CheckinResponse(val code: Int, val data: CheckinData?)
