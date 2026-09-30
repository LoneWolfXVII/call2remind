package app.call2remind.ui.di

import app.call2remind.ui.system.AndroidDeviceSetupChecker
import app.call2remind.ui.system.DeviceSetupChecker
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** UI-layer bindings to Android system state. */
@Module
@InstallIn(SingletonComponent::class)
abstract class UiModule {
    @Binds
    abstract fun deviceSetupChecker(impl: AndroidDeviceSetupChecker): DeviceSetupChecker
}
