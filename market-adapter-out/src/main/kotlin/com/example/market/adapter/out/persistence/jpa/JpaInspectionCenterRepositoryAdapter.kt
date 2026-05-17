package com.example.market.adapter.out.persistence.jpa

import com.example.market.adapter.out.persistence.jpa.mapper.InspectionCenterJpaMapper
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataInspectionCenterRepository
import com.example.market.application.port.out.InspectionCenterRepository
import com.example.market.domain.inspection.scheduling.InspectionCenter
import com.example.market.domain.inspection.scheduling.InspectionCenterId
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
class JpaInspectionCenterRepositoryAdapter(
    private val jpa: SpringDataInspectionCenterRepository,
) : InspectionCenterRepository {

    override fun save(center: InspectionCenter) {
        jpa.save(InspectionCenterJpaMapper.toEntity(center))
    }

    override fun findById(id: InspectionCenterId): Optional<InspectionCenter> =
        jpa.findById(id.value).map(InspectionCenterJpaMapper::toDomain)

    override fun findAll(): List<InspectionCenter> =
        jpa.findAll().map(InspectionCenterJpaMapper::toDomain)
}
