package com.example.market.adapter.web

import com.example.market.adapter.web.dto.ProductResponse
import com.example.market.adapter.web.dto.RegisterProductRequest
import com.example.market.application.exception.ProductNotFoundException
import com.example.market.application.port.`in`.RegisterProductUseCase
import com.example.market.application.port.out.ProductRepository
import com.example.market.domain.catalog.ProductId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "catalog", description = "상품 마스터")
class CatalogController(
    private val registerProduct: RegisterProductUseCase,
    private val products: ProductRepository,
) {

    @PostMapping
    @Operation(summary = "상품 등록 (운영자)")
    fun register(@Valid @RequestBody req: RegisterProductRequest): ResponseEntity<ProductResponse> {
        val product = registerProduct.register(req.toCommand())
        return ResponseEntity.created(URI.create("/api/v1/products/${product.id()}"))
            .body(ProductResponse.from(product))
    }

    @GetMapping("/{id}")
    @Operation(summary = "상품 조회")
    fun get(@PathVariable id: String): ProductResponse {
        val product = products.findById(ProductId.of(id))
            .orElseThrow { ProductNotFoundException(ProductId.of(id)) }
        return ProductResponse.from(product)
    }
}
