package com.kitakkun.fusio.compiler

import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

object FusioClassIds {
    val FUSIO_PACKAGE: FqName = FqName("com.kitakkun.fusio")

    val MAP_TO = ClassId(FUSIO_PACKAGE, Name.identifier("MapTo"))
    val MAP_FROM = ClassId(FUSIO_PACKAGE, Name.identifier("MapFrom"))
    val PRESENTER_SCOPE = ClassId(FUSIO_PACKAGE, Name.identifier("PresenterScope"))
    val PRESENTATION = ClassId(FUSIO_PACKAGE, Name.identifier("Presentation"))

    val FUSE = CallableId(FUSIO_PACKAGE, Name.identifier("fuse"))
    val BUILD_PRESENTER = CallableId(FUSIO_PACKAGE, Name.identifier("buildPresenter"))
    val EMIT_EFFECT = CallableId(PRESENTER_SCOPE, Name.identifier("emitEffect"))
    val MAP_EVENTS = CallableId(FUSIO_PACKAGE, Name.identifier("mapEvents"))
    val FORWARD_EFFECTS = CallableId(FUSIO_PACKAGE, Name.identifier("forwardEffects"))
}
