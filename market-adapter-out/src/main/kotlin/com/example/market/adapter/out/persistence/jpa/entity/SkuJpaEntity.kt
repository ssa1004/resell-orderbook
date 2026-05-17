package com.example.market.adapter.out.persistence.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.util.UUID

@Entity
@Table(
    name = "skus",
    indexes = [Index(name = "ix_sku_product", columnList = "product_id")],
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_sku_product_size_variant",
            columnNames = ["product_id", "size", "variant"],
        ),
    ],
)
class SkuJpaEntity {

    @Id
    var id: UUID? = null

    @Column(name = "product_id", nullable = false)
    var productId: UUID? = null

    @Column(nullable = false, length = 30)
    var size: String? = null

    @Column(length = 50)
    var variant: String? = null
}
