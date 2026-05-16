package com.example.market.application.port.out

import com.example.market.domain.shared.Money
import com.example.market.domain.shared.UserId

/**
 * 판매자 정산 송금. Mock 또는 실제 은행 API.
 */
interface BankTransferClient {

    fun send(request: SendRequest): SendResult

    @JvmRecord
    data class SendRequest(
        val idempotencyKey: String,
        val sellerId: UserId,
        val amount: Money,
        val memo: String,
    )

    @JvmRecord
    data class SendResult(
        val accepted: Boolean,
        val bankTransferId: String?,
        val errorMessage: String?,
    ) {
        companion object {
            @JvmStatic
            fun accepted(bankTransferId: String): SendResult = SendResult(true, bankTransferId, null)

            @JvmStatic
            fun rejected(msg: String): SendResult = SendResult(false, null, msg)
        }
    }
}
