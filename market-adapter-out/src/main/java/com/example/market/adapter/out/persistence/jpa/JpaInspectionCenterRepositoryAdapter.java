package com.example.market.adapter.out.persistence.jpa;

import com.example.market.adapter.out.persistence.jpa.mapper.InspectionCenterJpaMapper;
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataInspectionCenterRepository;
import com.example.market.application.port.out.InspectionCenterRepository;
import com.example.market.domain.inspection.scheduling.InspectionCenter;
import com.example.market.domain.inspection.scheduling.InspectionCenterId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaInspectionCenterRepositoryAdapter implements InspectionCenterRepository {

    private final SpringDataInspectionCenterRepository jpa;

    @Override
    public void save(InspectionCenter center) {
        jpa.save(InspectionCenterJpaMapper.toEntity(center));
    }

    @Override
    public Optional<InspectionCenter> findById(InspectionCenterId id) {
        return jpa.findById(id.value()).map(InspectionCenterJpaMapper::toDomain);
    }

    @Override
    public List<InspectionCenter> findAll() {
        return jpa.findAll().stream().map(InspectionCenterJpaMapper::toDomain).toList();
    }
}
