package com.example.market.application.port.in;

import com.example.market.application.command.RegisterProductCommand;
import com.example.market.domain.catalog.Product;

public interface RegisterProductUseCase {
    Product register(RegisterProductCommand command);
}
