package com.github.kitakkun.aria

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class MapFrom(val source: KClass<*>)
