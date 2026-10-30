package com.projectnyanko.companion

import android.app.Application
import androidx.room.Room
import com.projectnyanko.companion.data.catalog.GachaCatalogRepository
import com.projectnyanko.companion.data.catalog.CatScanCatalog
import com.projectnyanko.companion.data.catalog.UnitStatsRepository
import com.projectnyanko.companion.data.local.AppDatabase
import com.projectnyanko.companion.data.local.RoomMigrations
import com.projectnyanko.companion.data.local.SeedTrackerRepository
import com.projectnyanko.companion.data.stage.EnemyStatsRepository
import com.projectnyanko.companion.data.stage.StageCatalogRepository
import com.projectnyanko.companion.data.stage.StageOcrModelStore

class BattleCatsApplication : Application() {
    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "battle-cats-companion.db")
            .addMigrations(*RoomMigrations.all)
            .build()
    }

    val seedTrackerRepository: SeedTrackerRepository by lazy {
        SeedTrackerRepository(database.seedTrackerDao())
    }

    val gachaCatalogRepository: GachaCatalogRepository by lazy {
        GachaCatalogRepository(this)
    }

    val unitStatsRepository: UnitStatsRepository by lazy {
        UnitStatsRepository(this)
    }

    val catScanCatalog: CatScanCatalog by lazy {
        CatScanCatalog(unitStatsRepository)
    }

    val stageCatalogRepository: StageCatalogRepository by lazy {
        StageCatalogRepository(this)
    }

    val enemyStatsRepository: EnemyStatsRepository by lazy {
        EnemyStatsRepository(this)
    }

    val stageOcrModelStore: StageOcrModelStore by lazy {
        StageOcrModelStore(this)
    }
}
