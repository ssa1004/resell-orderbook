package com.example.market.domain.catalog;

import java.time.Instant;
import java.util.Objects;

/**
 * 상품 마스터. 한정판 신발/시계/의류의 *모델* 정보.
 *
 * <p>예: Product = "Air Jordan 1 Retro High OG Chicago" (브랜드 = Nike, 카테고리 = SNEAKERS)<br>
 * Sku = (이 상품의) 270mm / Black</p>
 *
 * <p>Product 자체는 거래 단위가 아니다. 거래는 Sku 단위로 이뤄짐.</p>
 */
public class Product {

    private final ProductId id;
    private final String brand;
    private final String modelName;
    private final String styleCode;          // 예: 555088-101
    private final ProductCategory category;
    private final Instant releaseDate;       // 한정판은 출시일이 의미 있음
    private final String imageUrl;
    private final Instant createdAt;

    private Product(ProductId id, String brand, String modelName, String styleCode,
                    ProductCategory category, Instant releaseDate, String imageUrl,
                    Instant createdAt) {
        this.id = id;
        this.brand = brand;
        this.modelName = modelName;
        this.styleCode = styleCode;
        this.category = category;
        this.releaseDate = releaseDate;
        this.imageUrl = imageUrl;
        this.createdAt = createdAt;
    }

    public static Product create(String brand, String modelName, String styleCode,
                                 ProductCategory category, Instant releaseDate,
                                 String imageUrl, Instant now) {
        Objects.requireNonNull(brand);
        if (brand.isBlank()) throw new IllegalArgumentException("brand must not be blank");
        Objects.requireNonNull(modelName);
        if (modelName.isBlank()) throw new IllegalArgumentException("modelName must not be blank");
        Objects.requireNonNull(category);
        return new Product(ProductId.newId(), brand, modelName, styleCode, category,
                releaseDate, imageUrl, now);
    }

    public static Product restore(ProductId id, String brand, String modelName, String styleCode,
                                  ProductCategory category, Instant releaseDate,
                                  String imageUrl, Instant createdAt) {
        return new Product(id, brand, modelName, styleCode, category, releaseDate, imageUrl, createdAt);
    }

    public ProductId id() { return id; }
    public String brand() { return brand; }
    public String modelName() { return modelName; }
    public String styleCode() { return styleCode; }
    public ProductCategory category() { return category; }
    public Instant releaseDate() { return releaseDate; }
    public String imageUrl() { return imageUrl; }
    public Instant createdAt() { return createdAt; }
}
