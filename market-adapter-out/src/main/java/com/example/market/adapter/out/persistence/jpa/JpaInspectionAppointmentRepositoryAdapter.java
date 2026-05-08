package com.example.market.adapter.out.persistence.jpa;

import com.example.market.adapter.out.persistence.jpa.mapper.InspectionAppointmentJpaMapper;
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataInspectionAppointmentRepository;
import com.example.market.application.port.out.InspectionAppointmentRepository;
import com.example.market.domain.inspection.scheduling.AppointmentId;
import com.example.market.domain.inspection.scheduling.InspectionAppointment;
import com.example.market.domain.inspection.scheduling.InspectionCenterId;
import com.example.market.domain.trading.TradeId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaInspectionAppointmentRepositoryAdapter implements InspectionAppointmentRepository {

    private final SpringDataInspectionAppointmentRepository jpa;

    @Override
    public void save(InspectionAppointment appointment) {
        jpa.save(InspectionAppointmentJpaMapper.toEntity(appointment));
    }

    @Override
    public Optional<InspectionAppointment> findById(AppointmentId id) {
        return jpa.findById(id.value()).map(InspectionAppointmentJpaMapper::toDomain);
    }

    @Override
    public long countActive(InspectionCenterId centerId, Instant slotStart) {
        return jpa.countActiveBookings(centerId.value(), slotStart);
    }

    @Override
    public Map<Instant, Long> countActiveInRange(InspectionCenterId centerId, Instant from, Instant to) {
        Map<Instant, Long> result = new LinkedHashMap<>();
        for (Object[] row : jpa.countActiveBookingsInRange(centerId.value(), from, to)) {
            Instant slotStart = (Instant) row[0];
            Long count = (Long) row[1];
            result.put(slotStart, count);
        }
        return result;
    }

    @Override
    public List<InspectionAppointment> findActiveByTrade(TradeId tradeId) {
        return jpa.findActiveByTrade(tradeId.value()).stream()
                .map(InspectionAppointmentJpaMapper::toDomain)
                .toList();
    }
}
