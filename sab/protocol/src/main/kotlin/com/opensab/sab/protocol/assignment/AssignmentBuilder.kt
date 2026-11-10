package com.opensab.sab.protocol.assignment

object AssignmentBuilder {

    /**
     * Constructs the standard assignment sequence:
     * - 2 HID Function records (VOLUME_UP, VOLUME_DOWN - 8 bytes)
     * - 8 Standard Function records (PREVIOUS, NEXT, BACK, SELECT, VOLUME_MUTE, MENU, MODE_SAB, MODE_HU - 5 bytes)
     * - 17 Color records (the palette slots; PURE_WHITE and PURE_BLACK are fixed cluster colours
     *   and are NOT registered)
     * Total: 27 records.
     *
     * The END_ASSIGNMENT sentinel is deliberately absent: it is a control write the manager sends
     * on its own after the batch has been answered, never a record of the batch.
     */
    fun buildStandardAssignmentSequence(colorRegistrationNeeded: Boolean = true): List<AssignmentRecord> {
        val records = mutableListOf<AssignmentRecord>()
        var reqCounter = 1

        records.add(
            AssignmentRecord.FunctionHid(
                requestId = RequestId16.fromInt(reqCounter++),
                functionId = FunctionId.VOLUME_UP,
                usagePage = AssignmentRecord.HID_PAGE_KEYBOARD,
                usage = AssignmentRecord.HID_USAGE_VOLUME_UP
            )
        )
        records.add(
            AssignmentRecord.FunctionHid(
                requestId = RequestId16.fromInt(reqCounter++),
                functionId = FunctionId.VOLUME_DOWN,
                usagePage = AssignmentRecord.HID_PAGE_KEYBOARD,
                usage = AssignmentRecord.HID_USAGE_VOLUME_DOWN
            )
        )

        val standardFunctions = listOf(
            FunctionId.PREVIOUS,
            FunctionId.NEXT,
            FunctionId.BACK,
            FunctionId.SELECT,
            FunctionId.VOLUME_MUTE,
            FunctionId.MENU,
            FunctionId.MODE_SAB,
            FunctionId.MODE_HU
        )
        standardFunctions.forEach { func ->
            records.add(
                AssignmentRecord.Function(
                    requestId = RequestId16.fromInt(reqCounter++),
                    functionId = func,
                    mode = AssignmentRecord.MODE_DEFAULT
                )
            )
        }

        if (colorRegistrationNeeded) {
            ColorId.entries
                .filter { it != ColorId.PURE_WHITE && it != ColorId.PURE_BLACK }
                .forEach { color ->
                    records.add(
                        AssignmentRecord.Color(
                            requestId = RequestId16.fromInt(reqCounter++),
                            colorId = color.id,
                            r = color.defaultRgb.first,
                            g = color.defaultRgb.second,
                            b = color.defaultRgb.third
                        )
                    )
                }
        }

        return records
    }
}

