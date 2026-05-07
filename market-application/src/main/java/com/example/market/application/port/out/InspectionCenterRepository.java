package com.example.market.application.port.out;

import com.example.market.domain.inspection.scheduling.InspectionCenter;
import com.example.market.domain.inspection.scheduling.InspectionCenterId;

import java.util.List;
import java.util.Optional;

public interface InspectionCenterRepository {

    void save(InspectionCenter center);

    Optional<InspectionCenter> findById(InspectionCenterId id);

    List<InspectionCenter> findAll();
}
