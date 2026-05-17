package com.example.market.bootstrap.config

import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@Configuration
@EntityScan(
    basePackages = [
        "com.example.market.adapter.out.persistence.jpa.entity",
        "com.example.market.adapter.out.persistence.outbox",
        "com.example.market.adapter.out.persistence.compensation",
    ],
)
@EnableJpaRepositories(
    basePackages = [
        "com.example.market.adapter.out.persistence.jpa.repository",
        "com.example.market.adapter.out.persistence.outbox",
        "com.example.market.adapter.out.persistence.compensation",
    ],
)
open class PersistenceConfig
