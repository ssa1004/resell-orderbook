package com.example.market.application.service

import com.example.market.application.command.RegisterProductCommand
import com.example.market.application.port.`in`.RegisterProductUseCase
import com.example.market.application.port.out.ProductRepository
import com.example.market.domain.catalog.Product
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class RegisterProductService(
    private val products: ProductRepository,
    private val clock: Clock,
) : RegisterProductUseCase {

    @Transactional
    override fun register(command: RegisterProductCommand): Product {
        val product = Product.create(
            command.brand, command.modelName, command.styleCode,
            command.category, command.releaseDate, command.imageUrl, clock.instant(),
        )
        products.save(product)
        log.info(
            "product registered id={} brand={} model={}",
            product.id, product.brand, product.modelName,
        )
        return product
    }

    companion object {
        private val log = LoggerFactory.getLogger(RegisterProductService::class.java)
    }
}
