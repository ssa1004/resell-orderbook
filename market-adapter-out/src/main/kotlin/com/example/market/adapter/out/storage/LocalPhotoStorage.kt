package com.example.market.adapter.out.storage

import com.example.market.application.port.out.PhotoStoragePort
import com.example.market.application.port.out.PhotoStoragePort.UploadUrl
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 로컬 dev 용 mock storage — 가짜 URL 만 반환. 실제 업로드/다운로드는 X.
 */
@Component
@ConditionalOnProperty(
    name = ["market.storage.s3-enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class LocalPhotoStorage : PhotoStoragePort {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun issueUploadUrl(objectKey: String, contentType: String, ttl: Duration): UploadUrl {
        val url = "http://localhost:8080/local-storage/upload/$objectKey"
        log.info("[local-storage] issue upload url {} ({}s)", objectKey, ttl.seconds)
        return UploadUrl(url, objectKey)
    }

    override fun issueDownloadUrl(objectKey: String, ttl: Duration): String =
        "http://localhost:8080/local-storage/download/$objectKey"
}
