package com.kitakkun.fusio

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class MapTo(public val target: KClass<*>)
