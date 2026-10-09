package net.jolabs40.tvslim.remote.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.jolabs40.tvslim.catalog.CatalogRepository
import net.jolabs40.tvslim.remote.adb.AdbKeyStore
import javax.inject.Singleton

/** The shared core does not use Hilt, so the app provides its objects here. */
@Module
@InstallIn(SingletonComponent::class)
object CoreModule {

    @Provides
    @Singleton
    fun catalogRepository(@ApplicationContext context: Context): CatalogRepository =
        CatalogRepository(context)

    @Provides
    @Singleton
    fun keyStore(@ApplicationContext context: Context): AdbKeyStore = AdbKeyStore(context)
}
