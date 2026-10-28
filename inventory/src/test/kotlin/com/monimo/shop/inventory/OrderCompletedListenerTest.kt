package com.monimo.shop.inventory

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.BehaviorSpec
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

/**
 * 주문 완료 알림 처리 (#54). Kafka 없이 리스너 메서드를 직접 부른다.
 *  - 제대로 온 알림은 그 상품의 캐시를 다시 채운다
 *  - 못 읽는 알림 · productId 가 없는 알림은 넘긴다 (소비가 막히지 않게)
 */
class OrderCompletedListenerTest : BehaviorSpec({

    class Fakes {
        val stock: StockService = Mockito.mock(StockService::class.java)
        val listener = OrderCompletedListener(stock, ObjectMapper())
    }

    Given("주문 완료 알림") {
        When("제대로 온 알림이면") {
            val f = Fakes()
            f.listener.onOrderCompleted("""{"orderId":7,"productId":"P-100","quantity":2,"status":"PAID"}""")

            Then("그 상품의 조회 캐시를 다시 채운다") {
                verify(f.stock).refreshCache("P-100")
            }
        }

        When("JSON 이 깨져 있으면") {
            val f = Fakes()
            f.listener.onOrderCompleted("{not json")

            Then("넘기고 캐시를 건드리지 않는다") {
                verify(f.stock, never()).refreshCache(Mockito.anyString())
            }
        }

        When("productId 가 없으면") {
            val f = Fakes()
            f.listener.onOrderCompleted("""{"orderId":7,"status":"PAID"}""")

            Then("넘기고 캐시를 건드리지 않는다") {
                verify(f.stock, never()).refreshCache(Mockito.anyString())
            }
        }
    }
})
