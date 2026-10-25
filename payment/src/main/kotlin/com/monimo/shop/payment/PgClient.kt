package com.monimo.shop.payment

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate

/**
 * 더미 외부 결제사(pg-stub) 의 POST /v1/approvals 를 부른다. Service map 의 "payment → 밖의 결제사" 화살표가 여기서 나온다.
 * X-Shop-Fault 를 그대로 넘긴다. pg-stub 은 pg-error(503) · pg-slow(1.5초)에만 반응하고 나머지 값은 무시한다.
 */
@Component
class PgClient(
    private val restTemplate: RestTemplate,
    @Value("\${pg.base-url}") private val baseUrl: String,
) {
    data class ApprovalRequest(val orderId: Long, val amount: Int)
    data class ApprovalResponse(val approvalId: String, val status: String)

    class PgFailure(cause: Throwable?) : RuntimeException("pg failure", cause)

    /** 결제사 승인. 5xx · 타임아웃 · 연결 실패면 PgFailure (컨트롤러가 502 로 바꾼다). */
    fun approve(orderId: Long, amount: Int, fault: String?): ApprovalResponse {
        val headers = HttpHeaders().apply { fault?.let { set(PaymentService.FAULT_HEADER, it) } }
        return try {
            restTemplate
                .postForEntity("$baseUrl/v1/approvals", HttpEntity(ApprovalRequest(orderId, amount), headers), ApprovalResponse::class.java)
                .body ?: throw PgFailure(null)
        } catch (e: RestClientException) {
            throw PgFailure(e)
        }
    }
}
