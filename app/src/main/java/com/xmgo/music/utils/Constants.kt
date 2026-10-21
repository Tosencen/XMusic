package com.xmgo.music.utils

object XmgoTheme {
    const val SYSTEM = "system"
    const val DARK = "dark"
    const val LIGHT = "light"
    const val AMOLED = "amoled"

    fun isDark(theme: String, systemInDark: Boolean): Boolean = when (theme) {
        DARK -> true
        LIGHT -> false
        AMOLED -> true
        else -> systemInDark
    }

    fun isAmoled(theme: String): Boolean = theme == AMOLED
}

object XmgoPaletteStyle {
    /** 莫奈取色：seed 来自系统动态取色（壁纸/主题），随主题明暗动态生成 */
    const val MONET = "monet"
    const val EXPRESSIVE = "expressive"
    const val FIDELITY = "fidelity"
    const val TONAL_SPOT = "tonal_spot"
    const val NEUTRAL = "neutral"
    const val VIBRANT = "vibrant"
    const val MONOCHROME = "monochrome"
    const val FRUIT_SALAD = "fruit_salad"

    val ALL: List<String> = listOf(
        MONET,
        EXPRESSIVE,
        FIDELITY,
        TONAL_SPOT,
        NEUTRAL,
        VIBRANT,
        MONOCHROME,
        FRUIT_SALAD
    )
}
