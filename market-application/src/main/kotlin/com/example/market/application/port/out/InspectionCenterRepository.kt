package com.example.market.application.port.out

import com.example.market.domain.inspection.scheduling.InspectionCenter
import com.example.market.domain.inspection.scheduling.InspectionCenterId
import java.util.Optional

interface InspectionCenterRepository {

    fun save(center: InspectionCenter)

    fun findById(id: InspectionCenterId): Optional<InspectionCenter>

    fun findAll(): List<InspectionCenter>
}
