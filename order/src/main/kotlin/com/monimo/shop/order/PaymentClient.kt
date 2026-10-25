package com.monimo.shop.order

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate

/**
 * payment 서비스의 POST /api/payments 를 부른다. Service map 의 order → payment 화살표가 여기서 나온다.
 * 쇼핑몰 서비스끼리는 코드로 의존하지 않으므로(루트 build.gradle.kts 가드) 요청 · 응답 모양을 여기서 다시 정의한다.
 */
@Component
class PaymentClient(
    private val restTemplate: RestTemplate,
    @Value("\${payment.base-url}") private val baseUrl: String,
) {
    data class PaymentRequest(val orderId: Long, val amount: Int)
    data class PaymentResponse(val paymentId: String, val status: String)

    /** 성공하면 응답, 실패(5xx · 타임아웃)하면 null. 예외를 여기서 끊어 order 가 502 로 답하게 한다. */
    fun pay(orderId: Long, amount: Int, fault: String?): PaymentResponse? {
        val headers = HttpHeaders().apply { fault?.let { set(FAULT_HEADER, it) } }
        return try {
            restTemplate
                .postForEntity(
                    "$baseUrl/api/payments",
                    HttpEntity(PaymentRequest(orderId, amount), headers),
                    PaymentResponse::class.java,
                )
                .body
        } catch (e: RestClientException) {
            null
        }
    }

    companion object {
        /** payment 의 PaymentService.FAULT_HEADER 와 같은 값이어야 한다 (k6 와도 약속). */
        const val FAULT_HEADER = "X-Shop-Fault"
    }
}
