package com.g2ray.client

import android.app.Application
import com.g2ray.client.data.AccountRepository
import com.g2ray.client.data.EncryptedPrefsStore
import com.g2ray.client.network.RouteMeasurementContext

class G2RayApplication : Application() {
    val routeMeasurementContext by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { RouteMeasurementContext(this) }

    val accountRepository: AccountRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AccountRepository(EncryptedPrefsStore(this))
    }
}
