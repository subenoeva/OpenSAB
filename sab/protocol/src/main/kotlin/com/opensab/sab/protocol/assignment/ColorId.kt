package com.opensab.sab.protocol.assignment

/**
 * The 19 canonical colours. 17 are registered as reassignable palette slots; `PURE_WHITE` (0) and
 * `PURE_BLACK` (1) are fixed cluster colours and are not registered — see `AssignmentBuilder`.
 */
enum class ColorId(
    val id: Int,
    val defaultRgb: Triple<Int, Int, Int>
) {
    PURE_WHITE(0, Triple(0xFF, 0xFF, 0xFF)),
    PURE_BLACK(1, Triple(0x00, 0x00, 0x00)),
    NEGATIVE(2, Triple(0xD6, 0x47, 0x47)),
    POSITIVE(3, Triple(0x38, 0xA8, 0x43)),
    GREEN_LIGHT(4, Triple(0x30, 0x9A, 0x87)),
    GREEN(5, Triple(0x24, 0x73, 0x65)),
    GREEN_DARK(6, Triple(0x0B, 0x27, 0x22)),
    PURPLE_LIGHT(7, Triple(0x78, 0x46, 0xA3)),
    PURPLE(8, Triple(0x57, 0x32, 0x7A)),
    PURPLE_DARK(9, Triple(0x27, 0x16, 0x37)),
    BLUE_LIGHT(10, Triple(0x45, 0x7A, 0xB1)),
    BLUE(11, Triple(0x34, 0x5C, 0x86)),
    BLUE_DARK(12, Triple(0x12, 0x1F, 0x2C)),
    ORANGE_LIGHT(13, Triple(0xD8, 0x84, 0x2B)),
    ORANGE(14, Triple(0xAA, 0x69, 0x27)),
    ORANGE_DARK(15, Triple(0x4C, 0x28, 0x0A)),
    GREY_LIGHT(16, Triple(0x71, 0x71, 0x71)),
    GREY(17, Triple(0x4B, 0x4B, 0x4B)),
    GREY_DARK(18, Triple(0x27, 0x27, 0x27))
}

