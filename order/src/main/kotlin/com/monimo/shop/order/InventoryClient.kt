package com.monimo.shop.order

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate

/**
 * inventory 서비스의 POST /api/stock/deduct · /restore 를 부른다. Service map 의 order → inventory 화살표가 여기서 나온다.
 * 쇼핑몰 서비스끼리는 코드로 의존하지 않으므로(루트 build.gradle.kts 가드) 요청 모양을 여기서 다시 정의한다.
 */
@Component
class InventoryClient(
    private val restTemplate: RestTemplate,
    @Value("\${inventory.base-url}") private val baseUrl: String,
) {
    data class DeductRequest(val orderId: Long, val productId: String, val quantity: Int)
    data class RestoreRequest(val orderId: Long)

    enum class DeductResult { OK, SOLD_OUT, ERROR }

    /** 재고가 있으면 OK, 모자라면(409) SOLD_OUT, 재고 서비스가 실패하거나 응답이 없으면 ERROR. 예외를 여기서 끊는다. */
    fun deduct(orderId: Long, productId: String, quantity: Int, fault: String?): DeductResult {
        val headers = HttpHeaders().apply { fault?.let { set(PaymentClient.FAULT_HEADER, it) } }
        return try {
            restTemplate.postForEntity("$baseUrl/api/stock/deduct", HttpEntity(DeductRequest(orderId, productId, quantity), headers), Map::class.java)
            DeductResult.OK
        } catch (e: HttpStatusCodeException) {
            if (e.statusCode == HttpStatus.CONFLICT) DeductResult.SOLD_OUT else DeductResult.ERROR
        } catch (e: RestClientException) {
            DeductResult.ERROR
        }
    }

    /** 결제가 실패한 주문의 재고를 되돌린다. 실패해도 주문 응답은 바꾸지 않는다(이미 502 라서). 성공 여부만 돌려준다. */
    fun restore(orderId: Long): Boolean =
        try {
            restTemplate.postForEntity("$baseUrl/api/stock/restore", RestoreRequest(orderId), Map::class.java)
            true
        } catch (e: RestClientException) {
            false
        }
}
