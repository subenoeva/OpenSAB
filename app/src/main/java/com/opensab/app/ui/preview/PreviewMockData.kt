package com.opensab.app.ui.preview

import com.opensab.domain.music.MediaAppInfo
import com.opensab.sab.protocol.vehicle.DestinationId
import com.opensab.sab.protocol.vehicle.DeviceInformation
import com.opensab.sab.protocol.vehicle.SwitchType
import com.opensab.sab.protocol.vehicle.VehicleInformation
import com.opensab.sab.session.ble.ScannedDevice
import com.opensab.sab.session.crypto.SecuritySession
import com.opensab.sab.session.vehicle.BodyStyle
import com.opensab.sab.session.vehicle.ClusterDisplay
import com.opensab.sab.session.vehicle.KnownVehicle

object PreviewMockData {
    val sampleSession1 = SecuritySession(
        macAddress = "02:00:00:11:22:33",
        aesKey = ByteArray(16),
        isValidated = true
    )

    val sampleSession2 = SecuritySession(
        macAddress = "02:00:00:44:55:66",
        aesKey = ByteArray(16),
        isValidated = true
    )

    val sampleSession3 = SecuritySession(
        macAddress = "02:00:00:77:88:99",
        aesKey = ByteArray(16),
        isValidated = true
    )

    val sampleVehicle1 = KnownVehicle(
        macAddress = "02:00:00:11:22:33",
        displayName = "Honda Forza 750",
        vehicleInfo = VehicleInformation(
            vehicleType = 0x01,
            displayCapability = byteArrayOf(0x01, 0x02),
            switchType = SwitchType.TYPE_B,
            categoryId = 0x03,
            seriesCode = "RH10",
            destinationId = DestinationId.EU
        ),
        deviceInfo = DeviceInformation(
            manufacturerName = "Honda",
            modelNumber = "BTU-V1",
            serialNumber = "HN-00912",
            hardwareRevision = "1.0",
            firmwareRevision = "1.4.2",
            softwareRevision = "1.0.0"
        ),
        lastConnectedTimestamp = 1_724_000_000_000L,
        bodyStyle = BodyStyle.SCOOTER,
        clusterDisplay = ClusterDisplay.COLOR
    )

    val sampleVehicle2 = KnownVehicle(
        macAddress = "02:00:00:44:55:66",
        displayName = "Honda X-ADV",
        vehicleInfo = VehicleInformation(
            vehicleType = 0x01,
            displayCapability = byteArrayOf(0x01, 0x02),
            switchType = SwitchType.TYPE_A,
            categoryId = 0x03,
            seriesCode = "RH11",
            destinationId = DestinationId.EU
        ),
        deviceInfo = DeviceInformation(
            manufacturerName = "Honda",
            modelNumber = "BTU-V2",
            serialNumber = "HN-00845",
            hardwareRevision = "1.1",
            firmwareRevision = "2.0.1",
            softwareRevision = "1.0.0"
        ),
        lastConnectedTimestamp = 1_723_000_000_000L,
        bodyStyle = BodyStyle.ADVENTURE,
        clusterDisplay = ClusterDisplay.COLOR
    )

    val sampleVehicle3 = KnownVehicle(
        macAddress = "02:00:00:77:88:99",
        displayName = "Honda ADV 350",
        vehicleInfo = VehicleInformation(
            vehicleType = 0x02,
            displayCapability = byteArrayOf(0x02),
            switchType = SwitchType.TYPE_B,
            categoryId = 0x01,
            seriesCode = "NF13",
            destinationId = DestinationId.EU
        ),
        deviceInfo = DeviceInformation(
            manufacturerName = "Honda",
            modelNumber = "BTU-LCD",
            serialNumber = "HN-00331",
            hardwareRevision = "1.0",
            firmwareRevision = "1.1.0",
            softwareRevision = "1.0.0"
        ),
        lastConnectedTimestamp = 1_722_000_000_000L,
        bodyStyle = BodyStyle.SCOOTER,
        clusterDisplay = ClusterDisplay.SEGMENT
    )

    val sampleMediaApps = listOf(
        MediaAppInfo("com.spotify.music", "Spotify"),
        MediaAppInfo("com.google.android.apps.youtube.music", "YouTube Music"),
        MediaAppInfo("com.amazon.mp3", "Amazon Music")
    )

    val sampleScannedDevices = listOf(
        ScannedDevice("Honda Forza 750", "02:00:00:11:22:33", -56, isSabCompatible = true),
        ScannedDevice("Honda X-ADV", "02:00:00:44:55:66", -74, isSabCompatible = true),
        ScannedDevice("ADV 350", "02:00:00:77:88:99", -86, isSabCompatible = true)
    )
}
