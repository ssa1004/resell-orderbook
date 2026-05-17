package com.example.market.adapter.out.persistence.jpa.entity

import com.example.market.domain.catalog.ProductCategory
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "products",
    indexes = [
        Index(name = "ix_product_brand", columnList = "brand"),
        Index(name = "ix_product_style_code", columnList = "style_code"),
    ],
)
class ProductJpaEntity {

    @Id
    var id: UUID? = null

    @Column(nullable = false, length = 80)
    var brand: String? = null

    @Column(name = "model_name", nullable = false, length = 200)
    var modelName: String? = null

    @Column(name = "style_code", length = 50)
    var styleCode: String? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var category: ProductCategory? = null

    @Column(name = "release_date")
    var releaseDate: Instant? = null

    @Column(name = "image_url", length = 500)
    var imageUrl: String? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null
}
