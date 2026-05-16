package com.example.market.application.port.out

import java.time.Duration

/**
 * 검수 사진 저장. S3 (또는 LocalStack). PUT presigned URL 발급 → 검수자가 직접 업로드.
 */
interface PhotoStoragePort {

    /** 업로드용 presigned URL — TTL 동안 유효. */
    fun issueUploadUrl(objectKey: String, contentType: String, ttl: Duration): UploadUrl

    /** 다운로드용 presigned URL — 검수 결과 view 시 발급. */
    fun issueDownloadUrl(objectKey: String, ttl: Duration): String

    @JvmRecord
    data class UploadUrl(val url: String, val objectKey: String)
}
