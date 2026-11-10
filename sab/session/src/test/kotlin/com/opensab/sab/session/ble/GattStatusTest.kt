package com.opensab.sab.session.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class GattStatusTest {

    @Test
    fun describesAnAttErrorCodeWithItsSpecName() {
        assertEquals(
            "5 (GATT_INSUFFICIENT_AUTHENTICATION)",
            GattStatus.describe(5, GattStatus.Domain.ATT)
        )
    }

    @Test
    fun describesTheSameNumberDifferentlyInEachDomain() {
        // 0x08 is INSUFFICIENT_AUTHORIZATION coming back from an ATT operation, but the HCI
        // connection-timeout reason when it arrives through onConnectionStateChange.
        assertEquals(
            "8 (GATT_INSUFFICIENT_AUTHORIZATION)",
            GattStatus.describe(8, GattStatus.Domain.ATT)
        )
        assertEquals(
            "8 (GATT_CONN_TIMEOUT)",
            GattStatus.describe(8, GattStatus.Domain.LINK)
        )
    }

    @Test
    fun describesTheCodesThatEndedTheGarageSession() {
        assertEquals("137 (GATT_AUTH_FAIL)", GattStatus.describe(137, GattStatus.Domain.ATT))
        assertEquals("6 (HCI_ERR_KEY_MISSING)", GattStatus.describe(6, GattStatus.Domain.LINK))
        assertEquals(
            "19 (GATT_CONN_TERMINATE_PEER_USER)",
            GattStatus.describe(19, GattStatus.Domain.LINK)
        )
    }

    @Test
    fun describesSuccessInBothDomains() {
        assertEquals("0 (GATT_SUCCESS)", GattStatus.describe(0, GattStatus.Domain.ATT))
        assertEquals("0 (GATT_SUCCESS)", GattStatus.describe(0, GattStatus.Domain.LINK))
    }

    @Test
    fun keepsAnUnknownCodeReadableInsteadOfDroppingIt() {
        assertEquals("200 (unknown)", GattStatus.describe(200, GattStatus.Domain.ATT))
    }
}
