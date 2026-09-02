package net.jolabs40.tvslim.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.jolabs40.tvslim.catalog.CatalogueRepository
import javax.inject.Singleton

/** Le noyau partagé n'utilise pas Hilt : chaque application fournit ses objets elle-même. */
@Module
@InstallIn(SingletonComponent::class)
object NoyauModule {

    @Provides
    @Singleton
    fun catalogueRepository(@ApplicationContext contexte: Context): CatalogueRepository =
        CatalogueRepository(contexte)
}
