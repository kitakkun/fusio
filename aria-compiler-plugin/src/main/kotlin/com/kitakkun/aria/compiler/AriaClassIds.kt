package com.kitakkun.aria.compiler

import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

object AriaClassIds {
    private val ARIA_PACKAGE = FqName("com.kitakkun.aria")

    val MAP_TO = ClassId(ARIA_PACKAGE, Name.identifier("MapTo"))
    val MAP_FROM = ClassId(ARIA_PACKAGE, Name.identifier("MapFrom"))
    val PRESENTER_SCOPE = ClassId(ARIA_PACKAGE, Name.identifier("PresenterScope"))
    val ARIA = ClassId(ARIA_PACKAGE, Name.identifier("Aria"))

    val MAPPED_SCOPE = CallableId(ARIA_PACKAGE, Name.identifier("mappedScope"))
    val BUILD_PRESENTER = CallableId(ARIA_PACKAGE, Name.identifier("buildPresenter"))
    val EMIT_EFFECT = CallableId(PRESENTER_SCOPE, Name.identifier("emitEffect"))
    val MAP_EVENTS = CallableId(ARIA_PACKAGE, Name.identifier("mapEvents"))
    val FORWARD_EFFECTS = CallableId(ARIA_PACKAGE, Name.identifier("forwardEffects"))
}
