package com.seenama.tv

import okhttp3.Interceptor

object DevHooks {
    fun demoInterceptor(): Interceptor? = null
    fun rawLogger(): Interceptor? = null
    fun faultInterceptor(isOffline: () -> Boolean): Interceptor? = null
}
