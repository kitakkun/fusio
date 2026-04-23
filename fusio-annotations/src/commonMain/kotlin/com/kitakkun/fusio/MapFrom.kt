package com.kitakkun.fusio

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class MapFrom(public val source: KClass<*>)
