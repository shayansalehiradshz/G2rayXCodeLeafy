package com.g2ray.client

import android.app.Application
import com.g2ray.client.data.AccountRepository
import com.g2ray.client.data.EncryptedPrefsStore

class G2RayApplication : Application() {
    val accountRepository: AccountRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AccountRepository(EncryptedPrefsStore(this))
    }
}
