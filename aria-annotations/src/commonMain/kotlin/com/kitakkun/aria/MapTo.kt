package com.kitakkun.aria

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class MapTo(val target: KClass<*>)
