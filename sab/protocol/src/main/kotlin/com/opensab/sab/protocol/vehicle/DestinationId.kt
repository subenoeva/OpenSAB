package com.opensab.sab.protocol.vehicle

import com.opensab.logging.Log

/**
 * Destination market reported by `DestinationId` (`55f7910b-8cda-458b-9b54-8a7da8cd79d4`): a
 * catalogue of 35 markets, not a set of continents.
 *
 * [regionCode] is the suffix Honda prints on the model plate (`ED` for Europe, `G` for Germany…).
 */
enum class DestinationId(val code: Int, val regionCode: String) {
    DEFAULT(0, "DEFAULT"),
    EU(1, "ED"),
    GERMANY(2, "G"),
    FRANCE(3, "F"),
    AMERICA(4, "A"),
    CALIFORNIA(5, "AC"),
    JAPAN(6, "J"),
    AUSTRALIA(7, "U"),
    BRAZIL(8, "BR"),
    MEXICO(9, "MX"),
    SOUTH_KOREA(10, "KO"),
    CANADA(11, "CM"),
    SWITZERLAND(12, "SW"),
    THAILAND(13, "TH"),
    CHINA(14, "CH"),
    INDONESIA(15, "IN"),
    INDIA(16, "ID"),
    VIETNAM(17, "V"),
    EU_TYPE2(18, "ED2"),
    FRANCE_TYPE2(19, "F2"),
    GERMANY_TYPE2(20, "G2"),
    JAPAN_TYPE2(21, "J2"),
    LATIN_AMERICA(22, "3LS"),
    CAMBODIA(23, "CA"),
    MALAYSIA(24, "MA"),
    MYANMAR(25, "MM"),
    SINGAPORE(26, "SI"),
    RUSSIA(27, "RU"),
    TAIWAN(28, "FO"),
    HONG_KONG(29, "HK"),
    GULF_COUNTRIES(30, "GS"),
    PHILIPPINES(31, "PH"),
    LAOS(32, "LO"),
    TURKEY(33, "TU"),
    PERU(34, "PE"),

    /** The wire sentinel is the byte -1; the parser widens it to an unsigned int. */
    UNKNOWN(0xFF, "UNKNOWN");

    companion object {
        fun fromCode(code: Int): DestinationId = entries.find { it.code == code }
            ?: UNKNOWN.also { Log.w("Unknown Destination ID with value: %d", code) }
    }
}
