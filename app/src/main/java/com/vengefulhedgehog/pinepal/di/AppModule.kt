package com.vengefulhedgehog.pinepal.di

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import com.vengefulhedgehog.pinepal.common.AppCoroutineDispatchers
import com.vengefulhedgehog.pinepal.common.CoroutineDispatchers
import com.vengefulhedgehog.pinepal.di.annotations.ApplicationScope
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class AppModule {

  @Provides
  @Singleton
  fun provideSharedPrefs(
    @ApplicationContext context: Context,
  ): SharedPreferences = context.getSharedPreferences(
    KEY_SHARED_PREF,
    ComponentActivity.MODE_PRIVATE
  )

  @Provides
  @Singleton
  @ApplicationScope
  fun provideApplicationScope(): CoroutineScope {
    return CoroutineScope(SupervisorJob())
  }

  @Provides
  @Singleton
  fun provideDispatchers(): CoroutineDispatchers {
    return AppCoroutineDispatchers()
  }

  companion object {
    private const val KEY_SHARED_PREF = "pine_pal_shared_prefs"
  }
}
