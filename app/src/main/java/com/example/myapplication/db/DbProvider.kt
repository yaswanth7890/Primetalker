package com.example.myapplication.db

import android.content.Context

object DbProvider {

    lateinit var db: AppDatabase
        private set

    fun init(context: Context) {
        db = AppDatabase.getInstance(context)
    }
}
