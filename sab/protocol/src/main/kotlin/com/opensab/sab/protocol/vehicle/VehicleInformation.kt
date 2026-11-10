package com.opensab.sab.protocol.vehicle

/** The six vehicle identity attributes read from VehicleInformationService. */
data class VehicleInformation(
    val vehicleType: Int,
    val displayCapability: ByteArray,
    val switchType: SwitchType,
    val categoryId: Int,
    val seriesCode: String,
    val destinationId: DestinationId
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VehicleInformation
        if (vehicleType != other.vehicleType) return false
        if (!displayCapability.contentEquals(other.displayCapability)) return false
        if (switchType != other.switchType) return false
        if (categoryId != other.categoryId) return false
        if (seriesCode != other.seriesCode) return false
        if (destinationId != other.destinationId) return false
        return true
    }

    override fun hashCode(): Int {
        var result = vehicleType
        result = 31 * result + displayCapability.contentHashCode()
        result = 31 * result + switchType.hashCode()
        result = 31 * result + categoryId
        result = 31 * result + seriesCode.hashCode()
        result = 31 * result + destinationId.hashCode()
        return result
    }
}
