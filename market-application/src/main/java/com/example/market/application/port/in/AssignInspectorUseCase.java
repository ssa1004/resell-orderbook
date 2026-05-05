package com.example.market.application.port.in;

import com.example.market.application.command.AssignInspectorCommand;

public interface AssignInspectorUseCase {
    void assign(AssignInspectorCommand command);
}
