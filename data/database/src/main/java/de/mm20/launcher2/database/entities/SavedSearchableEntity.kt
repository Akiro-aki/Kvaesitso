package de.mm20.launcher2.database.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "Searchable")
data class SavedSearchableEntity(
        @PrimaryKey val key: String,
        val type: String,
        @ColumnInfo(name = "searchable") val serializedSearchable: String,
        @ColumnInfo(defaultValue = "0") val launchCount: Int,
        @ColumnInfo(defaultValue = "0") val pinPosition: Int,
        @ColumnInfo(name="hidden", defaultValue = "0") val visibility: Int,
        @ColumnInfo(defaultValue = "0.0") val weight: Double,
        /**
         * Unix timestamp (ms) of the last launch, 0 if unknown
         */
        @ColumnInfo(defaultValue = "0") val lastLaunch: Long = 0L,
)

/**
 * Launch statistics of a searchable, used to sort the app list.
 */
data class SearchableUsageEntity(
        val key: String,
        val launchCount: Int,
        val weight: Double,
        val lastLaunch: Long,
)

data class SavedSearchableUpdatePinEntity(
        val key: String,
        val type: String,
        @ColumnInfo(name = "searchable") val serializedSearchable: String,
        val pinPosition: Int? = null,
)

data class SavedSearchableUpdateContentEntity(
        val key: String,
        val type: String,
        @ColumnInfo(name = "searchable") val serializedSearchable: String,
)