package com.example.helloword.model

import com.google.gson.annotations.SerializedName

data class FavoriteRequest(@SerializedName("movie_id") val movieId: Int, val word: String)
data class FavoriteWord(
    val id: Long, val word: String, val meaning: String?,
    @SerializedName("sentence_text") val sentenceText: String?,
    @SerializedName("source_movie_id") val sourceMovieId: Int?,
    @SerializedName("source_movie_name") val sourceMovieName: String?,
    @SerializedName("start_time") val startTime: Double?,
    @SerializedName("end_time") val endTime: Double?,
    @SerializedName("created_at") val createdAt: String
)
data class FavoriteResponse(val code: Int, val data: FavoriteWord?)
data class FavoriteStatus(val word: String, @SerializedName("is_favorite") val isFavorite: Boolean)
data class FavoriteStatusResponse(val code: Int, val data: FavoriteStatus?)
data class FavoritePage(val items: List<FavoriteWord>, @SerializedName("next_before_id") val nextBeforeId: Long?)
data class FavoritesResponse(val code: Int, val data: FavoritePage?)
