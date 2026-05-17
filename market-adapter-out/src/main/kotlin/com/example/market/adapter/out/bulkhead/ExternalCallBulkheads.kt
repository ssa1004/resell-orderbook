package com.example.market.adapter.out.bulkhead

import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry

/**
 * 이름으로 [ExternalCallBulkhead] 를 룩업. 설정에 정의된 instance 마다 1개씩.
 *
 * [of] 호출 시점에 lazy create — properties 가 없는 인스턴스 이름은
 * [IllegalStateException].
 */
class ExternalCallBulkheads(
    private val props: BulkheadProperties,
    private val registry: ThreadPoolBulkheadRegistry,
) {

    private val created: MutableMap<String, ExternalCallBulkhead> = HashMap()

    @Synchronized
    fun of(poolName: String): ExternalCallBulkhead {
        val existing = created[poolName]
        if (existing != null) return existing

        val cfg = props.instances[poolName]
            ?: throw IllegalStateException(
                "bulkhead 인스턴스 '$poolName' 가 market.bulkhead.instances 에 정의되지 않았다",
            )
        val newBulkhead = ExternalCallBulkhead.create(registry, poolName, cfg)
        created[poolName] = newBulkhead
        return newBulkhead
    }
}
