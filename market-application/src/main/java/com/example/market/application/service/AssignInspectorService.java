package com.example.market.application.service;

import com.example.market.application.command.AssignInspectorCommand;
import com.example.market.application.exception.InspectionRequestNotFoundException;
import com.example.market.application.port.in.AssignInspectorUseCase;
import com.example.market.application.port.out.InspectionRequestRepository;
import com.example.market.domain.inspection.InspectionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AssignInspectorService implements AssignInspectorUseCase {

    private final InspectionRequestRepository inspections;

    @Override
    @Transactional
    public void assign(AssignInspectorCommand cmd) {
        InspectionRequest request = inspections.findById(cmd.requestId())
                .orElseThrow(() -> new InspectionRequestNotFoundException(cmd.requestId()));
        request.assignInspector(cmd.inspectorId());
        inspections.save(request);
        log.info("inspector assigned request={} inspector={}", request.id(), cmd.inspectorId());
    }
}
