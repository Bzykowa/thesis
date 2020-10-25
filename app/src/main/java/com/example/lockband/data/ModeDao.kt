package com.example.lockband.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface ModeDao {
    @Insert
    fun insertAll(modes: List<Mode>)

    @Delete
    fun delete(mode: Mode)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(mode: Mode): Long

    @Update(onConflict = OnConflictStrategy.REPLACE)
    fun update(mode: Mode)

    @Query("SELECT * FROM modes")
    fun getModes() : LiveData<List<Mode>>

    @Query("SELECT * FROM modes WHERE name = :name")
    fun getMode(name : String) : LiveData<Mode>
}