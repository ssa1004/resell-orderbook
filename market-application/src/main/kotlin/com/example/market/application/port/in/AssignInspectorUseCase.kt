package com.example.market.application.port.`in`

import com.example.market.application.command.AssignInspectorCommand

interface AssignInspectorUseCase {
    fun assign(command: AssignInspectorCommand)
}
