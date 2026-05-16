package com.example.market.application.service

import com.example.market.application.command.AssignInspectorCommand
import com.example.market.application.exception.InspectionRequestNotFoundException
import com.example.market.application.port.`in`.AssignInspectorUseCase
import com.example.market.application.port.out.InspectionRequestRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class AssignInspectorService(
    private val inspections: InspectionRequestRepository,
) : AssignInspectorUseCase {

    @Transactional
    override fun assign(command: AssignInspectorCommand) {
        val request = inspections.findById(command.requestId)
            .orElseThrow { InspectionRequestNotFoundException(command.requestId) }
        request.assignInspector(command.inspectorId)
        inspections.save(request)
        log.info("inspector assigned request={} inspector={}", request.id, command.inspectorId)
    }

    companion object {
        private val log = LoggerFactory.getLogger(AssignInspectorService::class.java)
    }
}
