package com.opensab.sab.protocol.ble

import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

object SabGattUuids {
    // Security Service
    val SERVICE_SECURITY: UUID = UUID.fromString("592c1017-9c7b-4a35-aba6-f268592fb8fc")
    val CHAR_SECURITY_STATUS: UUID = UUID.fromString("a65b7152-589e-4933-a426-8f729bfad439")
    val CHAR_SECURITY_CONTROL_POINT: UUID = UUID.fromString("4fa28b27-6938-48f8-980e-7c7c9acc6c1e")
    val CHAR_SECURITY_DATA_SOURCE: UUID = UUID.fromString("ebb3656f-03a5-44ce-a22c-70cfccf5e247")

    // Assignment Service
    val SERVICE_ASSIGNMENT: UUID = UUID.fromString("f0746e98-61d6-4570-be7e-7635c65c21cb")
    val CHAR_ASSIGNMENT_CONTROL: UUID = UUID.fromString("4f5fbe6b-474b-4820-833f-95bfbf525d9f")

    // Data Communication Service
    val SERVICE_DATA_COMM: UUID = UUID.fromString("8536e103-6771-4d4b-9702-287cb5e1340f")
    val CHAR_DATA_COMM_CONTROL_POINT: UUID = UUID.fromString("d2e23443-f1ad-4293-a70b-a9fd163f7b00")
    val CHAR_DATA_COMM_COMMAND: UUID = CHAR_DATA_COMM_CONTROL_POINT
    val CHAR_FUNCTION_NOTIFICATION: UUID = UUID.fromString("6c060578-d1d9-460a-b86f-eb97f01b2227")

    // Vehicle Information Service
    val SERVICE_VEHICLE_INFO: UUID = UUID.fromString("4d87b1ea-528b-4798-b002-e2e1442a2e86")
    val CHAR_VEHICLE_TYPE: UUID = UUID.fromString("5738696d-45b3-475f-81d8-cc685d56ec18")
    val CHAR_DISPLAY_CAPABILITY: UUID = UUID.fromString("0ad39e0b-b78d-4252-9917-1aa31a915da7")
    val CHAR_SWITCH_TYPE: UUID = UUID.fromString("ddd14e97-636d-4338-a798-94cf7ce13667")
    val CHAR_CATEGORY_ID: UUID = UUID.fromString("3f033888-0211-472e-9405-6525d4e4ddc7")
    val CHAR_SERIES_CODE: UUID = UUID.fromString("5074e0e8-006f-41ac-aa0f-de6e4649a830")
    val CHAR_DESTINATION_ID: UUID = UUID.fromString("55f7910b-8cda-458b-9b54-8a7da8cd79d4")

    // DFU Service
    val SERVICE_DFU: UUID = UUID.fromString("0ea1bd92-5c48-41ad-9b8f-70da1bd08f35")
    val CHAR_DFU_FIRMWARE_VERSION: UUID = UUID.fromString("32c7720d-440a-47a8-a866-a8b6bea9eeb6")
    val CHAR_DFU_CONTROL_POINT: UUID = UUID.fromString("13561ecc-f5ed-4109-95c9-2040122b5810")
    val CHAR_DFU_PACKET: UUID = UUID.fromString("ec2f22ba-a682-42af-b439-68fa72177719")

    // Current Time Service (CTS)
    val SERVICE_CTS: UUID = UUID.fromString("00001805-0000-1000-8000-00805f9b34fb")
    val CHAR_CTS_CURRENT_TIME: UUID = UUID.fromString("00002a2b-0000-1000-8000-00805f9b34fb")
    val CHAR_CTS_LOCAL_TIME_INFO: UUID = UUID.fromString("00002a0f-0000-1000-8000-00805f9b34fb")
    val DESCRIPTOR_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val TARGET_MTU: Int = 247
    val OPERATION_TIMEOUT: Duration = 5.seconds
}
