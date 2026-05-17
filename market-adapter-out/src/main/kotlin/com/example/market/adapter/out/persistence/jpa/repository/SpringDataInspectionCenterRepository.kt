package com.example.market.adapter.out.persistence.jpa.repository

import com.example.market.adapter.out.persistence.jpa.entity.InspectionCenterJpaEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SpringDataInspectionCenterRepository : JpaRepository<InspectionCenterJpaEntity, UUID>
