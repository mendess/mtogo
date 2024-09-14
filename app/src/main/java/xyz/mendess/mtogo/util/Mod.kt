package xyz.mendess.mtogo.util

fun Boolean.toInt(): Int = if (this) 1 else 0

fun <T> identity(t: T): T = t