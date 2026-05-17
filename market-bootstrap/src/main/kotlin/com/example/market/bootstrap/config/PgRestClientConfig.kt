package com.example.market.bootstrap.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
@ConditionalOnProperty(name = ["market.pg.enabled"], havingValue = "true")
open class PgRestClientConfig {

    @Bean
    open fun pgRestClient(@Value("\${market.pg.base-url}") baseUrl: String): RestClient {
        val factory = JdkClientHttpRequestFactory()
        factory.setReadTimeout(Duration.ofSeconds(5))
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build()
    }
}
