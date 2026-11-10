package com.opensab.sab.protocol.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.UUID

class SabGattUuidsTest {

    @Test
    fun allServiceUuidsShouldBeValid128BitUuidsAndDistinct() {
        val services = listOf(
            SabGattUuids.SERVICE_SECURITY,
            SabGattUuids.SERVICE_ASSIGNMENT,
            SabGattUuids.SERVICE_DATA_COMM,
            SabGattUuids.SERVICE_VEHICLE_INFO,
            SabGattUuids.SERVICE_DFU
        )

        assertEquals(5, services.distinct().size)
        services.forEach { uuid ->
            assertNotNull(uuid)
            assertEquals(36, uuid.toString().length)
        }
    }

    @Test
    fun allCharacteristicUuidsShouldMatchCanonicalValuesFromSpecification() {
        // Security
        assertEquals(UUID.fromString("592c1017-9c7b-4a35-aba6-f268592fb8fc"), SabGattUuids.SERVICE_SECURITY)
        assertEquals(UUID.fromString("a65b7152-589e-4933-a426-8f729bfad439"), SabGattUuids.CHAR_SECURITY_STATUS)
        assertEquals(UUID.fromString("4fa28b27-6938-48f8-980e-7c7c9acc6c1e"), SabGattUuids.CHAR_SECURITY_CONTROL_POINT)
        assertEquals(UUID.fromString("ebb3656f-03a5-44ce-a22c-70cfccf5e247"), SabGattUuids.CHAR_SECURITY_DATA_SOURCE)

        // Assignment
        assertEquals(UUID.fromString("f0746e98-61d6-4570-be7e-7635c65c21cb"), SabGattUuids.SERVICE_ASSIGNMENT)
        assertEquals(UUID.fromString("4f5fbe6b-474b-4820-833f-95bfbf525d9f"), SabGattUuids.CHAR_ASSIGNMENT_CONTROL)

        // Data Comm
        assertEquals(UUID.fromString("8536e103-6771-4d4b-9702-287cb5e1340f"), SabGattUuids.SERVICE_DATA_COMM)
        assertEquals(UUID.fromString("d2e23443-f1ad-4293-a70b-a9fd163f7b00"), SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT)
        assertEquals(UUID.fromString("6c060578-d1d9-460a-b86f-eb97f01b2227"), SabGattUuids.CHAR_FUNCTION_NOTIFICATION)

        // Vehicle Info
        assertEquals(UUID.fromString("4d87b1ea-528b-4798-b002-e2e1442a2e86"), SabGattUuids.SERVICE_VEHICLE_INFO)
        assertEquals(UUID.fromString("5738696d-45b3-475f-81d8-cc685d56ec18"), SabGattUuids.CHAR_VEHICLE_TYPE)
        assertEquals(UUID.fromString("0ad39e0b-b78d-4252-9917-1aa31a915da7"), SabGattUuids.CHAR_DISPLAY_CAPABILITY)
        assertEquals(UUID.fromString("ddd14e97-636d-4338-a798-94cf7ce13667"), SabGattUuids.CHAR_SWITCH_TYPE)
        assertEquals(UUID.fromString("3f033888-0211-472e-9405-6525d4e4ddc7"), SabGattUuids.CHAR_CATEGORY_ID)
        assertEquals(UUID.fromString("5074e0e8-006f-41ac-aa0f-de6e4649a830"), SabGattUuids.CHAR_SERIES_CODE)
        assertEquals(UUID.fromString("55f7910b-8cda-458b-9b54-8a7da8cd79d4"), SabGattUuids.CHAR_DESTINATION_ID)

        // DFU
        assertEquals(UUID.fromString("0ea1bd92-5c48-41ad-9b8f-70da1bd08f35"), SabGattUuids.SERVICE_DFU)
        assertEquals(UUID.fromString("32c7720d-440a-47a8-a866-a8b6bea9eeb6"), SabGattUuids.CHAR_DFU_FIRMWARE_VERSION)
        assertEquals(UUID.fromString("13561ecc-f5ed-4109-95c9-2040122b5810"), SabGattUuids.CHAR_DFU_CONTROL_POINT)
        assertEquals(UUID.fromString("ec2f22ba-a682-42af-b439-68fa72177719"), SabGattUuids.CHAR_DFU_PACKET)
    }
}
