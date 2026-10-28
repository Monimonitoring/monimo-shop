package com.monimo.shop.order

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

/**
 * 주문 흐름의 순서만 본다 (MySQL · 재고 · 결제 서비스 · Kafka 없이, 전부 Mockito 가짜).
 *  - 재고가 모자라면 결제를 부르지 않고 SOLD_OUT
 *  - 결제가 실패하면 재고를 되돌린다
 *  - 결제가 성공하면 되돌리지 않는다
 *  - 재고가 바뀐 주문만 토픽에 알린다 (#54)
 */
class OrderServiceTest : BehaviorSpec({
    val req = CreateOrderRequest(productId = "P-100", quantity = 2, amount = 15000)

    class Fakes {
        val repo: OrderRepository = Mockito.mock(OrderRepository::class.java)
        val inventory: InventoryClient = Mockito.mock(InventoryClient::class.java)
        val payment: PaymentClient = Mockito.mock(PaymentClient::class.java)
        val events: OrderEventPublisher = Mockito.mock(OrderEventPublisher::class.java)
        val service = OrderService(repo, inventory, payment, events)
    }

    fun fakes(): Fakes = Fakes().also { Mockito.`when`(it.repo.insertPending(req)).thenReturn(7L) }

    Given("주문 생성") {
        When("재고가 있고 결제가 성공하면") {
            val f = fakes()
            Mockito.`when`(f.inventory.deduct(7, "P-100", 2, null)).thenReturn(InventoryClient.DeductResult.OK)
            Mockito.`when`(f.payment.pay(7, 15000, null)).thenReturn(PaymentClient.PaymentResponse("pay-1", "APPROVED"))

            val res = f.service.create(req, null)

            Then("PAID 이고 재고를 되돌리지 않는다") {
                res.status shouldBe OrderStatus.PAID
                verify(f.repo).updateResult(7, OrderStatus.PAID, "pay-1")
                verify(f.inventory, never()).restore(7)
            }

            Then("재고가 줄었으니 토픽에 알린다") {
                verify(f.events).publishCompleted(OrderCompleted(7, "P-100", 2, OrderStatus.PAID))
            }
        }
        When("재고가 모자라면") {
            val f = fakes()
            Mockito.`when`(f.inventory.deduct(7, "P-100", 2, null)).thenReturn(InventoryClient.DeductResult.SOLD_OUT)

            val res = f.service.create(req, null)

            Then("SOLD_OUT 이고 결제를 부르지 않는다") {
                res.status shouldBe OrderStatus.SOLD_OUT
                res.reason shouldBe "sold out"
                verify(f.payment, never()).pay(7, 15000, null)
                verify(f.repo).updateResult(7, OrderStatus.SOLD_OUT, null)
            }

            Then("재고가 그대로라 토픽에 알리지 않는다") {
                // any() 는 코틀린의 널 아님 파라미터에 null 을 넣어 NPE 가 된다. 아무 호출도 없음을 본다
                Mockito.verifyNoInteractions(f.events)
            }
        }
        When("재고는 줄였는데 결제가 실패하면") {
            val f = fakes()
            Mockito.`when`(f.inventory.deduct(7, "P-100", 2, "payment-error")).thenReturn(InventoryClient.DeductResult.OK)
            Mockito.`when`(f.payment.pay(7, 15000, "payment-error")).thenReturn(null)

            val res = f.service.create(req, "payment-error")

            Then("FAILED 이고 재고를 되돌린다") {
                res.status shouldBe OrderStatus.FAILED
                res.reason shouldBe "payment error"
                verify(f.inventory).restore(7)
            }

            Then("되돌린 재고도 바뀐 것이라 토픽에 알린다") {
                verify(f.events).publishCompleted(OrderCompleted(7, "P-100", 2, OrderStatus.FAILED))
            }
        }
        When("재고 서비스가 응답하지 않으면") {
            val f = fakes()
            Mockito.`when`(f.inventory.deduct(7, "P-100", 2, null)).thenReturn(InventoryClient.DeductResult.ERROR)

            val res = f.service.create(req, null)

            Then("FAILED(inventory error) 이고 결제를 부르지 않는다. 타임아웃이면 차감이 됐을 수 있어 복원은 한 번 불러 본다") {
                res.status shouldBe OrderStatus.FAILED
                res.reason shouldBe "inventory error"
                verify(f.payment, never()).pay(7, 15000, null)
                verify(f.inventory).restore(7)
            }
        }
    }
})
