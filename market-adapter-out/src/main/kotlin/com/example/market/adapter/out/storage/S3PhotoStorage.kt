package com.example.market.adapter.out.storage

import com.example.market.application.port.out.PhotoStoragePort
import com.example.market.application.port.out.PhotoStoragePort.UploadUrl
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.time.Duration

/**
 * AWS S3 (또는 LocalStack) 기반 검수 사진 저장. `market.storage.s3-enabled=true` 시 활성.
 *
 * presigned URL 만 발급 — 검수자/구매자가 *직접* S3 업로드/다운로드 (서버는 stream 미경유).
 */
@Component
@ConditionalOnProperty(name = ["market.storage.s3-enabled"], havingValue = "true")
class S3PhotoStorage(
    private val presigner: S3Presigner,
    @Suppress("UNUSED_PARAMETER") private val s3Client: S3Client, // 객체 메타데이터 조회용 (선택)
    @Value("\${market.storage.bucket}") private val bucket: String,
) : PhotoStoragePort {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun issueUploadUrl(objectKey: String, contentType: String, ttl: Duration): UploadUrl {
        val put = PutObjectRequest.builder()
            .bucket(bucket).key(objectKey).contentType(contentType).build()
        val preReq = PutObjectPresignRequest.builder()
            .signatureDuration(ttl).putObjectRequest(put).build()
        val url = presigner.presignPutObject(preReq).url().toString()
        log.debug("[s3] issued upload url bucket={} key={} ttl={}s", bucket, objectKey, ttl.seconds)
        return UploadUrl(url, objectKey)
    }

    override fun issueDownloadUrl(objectKey: String, ttl: Duration): String {
        val get = GetObjectRequest.builder().bucket(bucket).key(objectKey).build()
        val preReq = GetObjectPresignRequest.builder()
            .signatureDuration(ttl).getObjectRequest(get).build()
        return presigner.presignGetObject(preReq).url().toString()
    }
}
