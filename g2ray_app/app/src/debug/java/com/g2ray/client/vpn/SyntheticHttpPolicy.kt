package com.g2ray.client.vpn

import okhttp3.OkHttpClient

/** Strict measurements do not follow redirects or hide connection failures by replaying. */
internal fun OkHttpClient.Builder.strictSyntheticRequests(): OkHttpClient.Builder =
    followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
