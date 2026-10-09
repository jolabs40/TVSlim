package net.jolabs40.tvslim.remote.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.jolabs40.tvslim.catalog.CatalogueRepository
import net.jolabs40.tvslim.remote.adb.DepotCles
import javax.inject.Singleton

/** The shared core does not use Hilt, so the app provides its objects here. */
@Module
@InstallIn(SingletonComponent::class)
object NoyauModule {

    @Provides
    @Singleton
    fun catalogueRepository(@ApplicationContext contexte: Context): CatalogueRepository =
        CatalogueRepository(contexte)

    @Provides
    @Singleton
    fun depotCles(@ApplicationContext contexte: Context): DepotCles = DepotCles(contexte)
}
