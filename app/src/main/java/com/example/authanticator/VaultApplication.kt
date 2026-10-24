package com.example.authanticator

import android.app.Application
import androidx.room.Room
import com.example.authanticator.data.LocalVaultRepository
import com.example.authanticator.data.MetadataDatabase
import com.example.authanticator.security.DeviceKey
import com.example.authanticator.settings.PreferenceStore
import java.io.File

class VaultApplication : Application() {
    val container by lazy { AppContainer(this) }
}

class AppContainer(application: Application) {
    private val database = Room.databaseBuilder(application, MetadataDatabase::class.java,
        File(application.noBackupFilesDir, "metadata.db").absolutePath)
        .addMigrations(MetadataDatabase.MIGRATION_1_2).build()
    val repository = LocalVaultRepository(application, database.accounts())
    val preferences = PreferenceStore(application)
    val deviceKey = DeviceKey(application)
}