package com.example.market.application.service;

import com.example.market.application.command.RegisterProductCommand;
import com.example.market.application.port.in.RegisterProductUseCase;
import com.example.market.application.port.out.ProductRepository;
import com.example.market.domain.catalog.Product;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@RequiredArgsConstructor
@Slf4j
public class RegisterProductService implements RegisterProductUseCase {

    private final ProductRepository products;
    private final Clock clock;

    @Override
    @Transactional
    public Product register(RegisterProductCommand cmd) {
        Product product = Product.create(
                cmd.brand(), cmd.modelName(), cmd.styleCode(),
                cmd.category(), cmd.releaseDate(), cmd.imageUrl(), clock.instant());
        products.save(product);
        log.info("product registered id={} brand={} model={}",
                product.id(), product.brand(), product.modelName());
        return product;
    }
}
