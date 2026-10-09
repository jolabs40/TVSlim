package net.jolabs40.tvslim.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.jolabs40.tvslim.catalog.CatalogueRepository
import javax.inject.Singleton

/** The shared core does not use Hilt, so each app provides its objects. */
@Module
@InstallIn(SingletonComponent::class)
object NoyauModule {

    @Provides
    @Singleton
    fun catalogueRepository(@ApplicationContext contexte: Context): CatalogueRepository =
        CatalogueRepository(contexte)
}
