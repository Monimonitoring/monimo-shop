package com.monimo.shop.order

import org.springframework.stereotype.Service

/**
 * 주문 흐름: PENDING 으로 저장 → 재고 차감 → 결제 호출 → PAID 또는 FAILED 로 갱신.
 * 재고가 모자라면 결제를 부르지 않고 SOLD_OUT (409). 결제가 실패하면 줄인 재고를 되돌린다 (#32).
 * 재고가 실제로 바뀐 주문(PAID · FAILED)은 끝낸 뒤 토픽에 알린다. 재고 서비스가 읽어 조회 캐시를 다시 채운다 (#54).
 */
@Service
class OrderService(
    private val repository: OrderRepository,
    private val inventoryClient: InventoryClient,
    private val paymentClient: PaymentClient,
    private val eventPublisher: OrderEventPublisher,
) {
    fun create(req: CreateOrderRequest, fault: String?): CreateOrderResponse {
        val id = repository.insertPending(req)
        when (inventoryClient.deduct(id, req.productId, req.quantity, fault)) {
            // 차감이 안 됐으니 재고가 그대로다. 알릴 것이 없다
            InventoryClient.DeductResult.SOLD_OUT -> return finish(id, OrderStatus.SOLD_OUT, reason = "sold out")
            InventoryClient.DeductResult.ERROR -> {
                // 타임아웃이면 재고 쪽은 이미 커밋됐을 수 있다. 복원은 멱등이고 차감 기록이 없으면 404 라 부작용이 없으니 한 번 불러 본다
                inventoryClient.restore(id)
                return finish(id, OrderStatus.FAILED, reason = "inventory error", req = req)
            }
            InventoryClient.DeductResult.OK -> {}
        }
        val payment = paymentClient.pay(id, req.amount, fault)
        return if (payment != null) {
            finish(id, OrderStatus.PAID, paymentId = payment.paymentId, req = req)
        } else {
            // 팔리지 않은 재고를 되돌린다. 같은 주문 번호라 두 번 불러도 한 번만 더해진다 (inventory 가 막는다)
            inventoryClient.restore(id)
            finish(id, OrderStatus.FAILED, reason = "payment error", req = req)
        }
    }

    // req 를 받은 주문만 알린다. 재고가 바뀌지 않은 주문(SOLD_OUT)은 캐시를 다시 채울 이유가 없다
    private fun finish(
        id: Long,
        status: OrderStatus,
        paymentId: String? = null,
        reason: String? = null,
        req: CreateOrderRequest? = null,
    ): CreateOrderResponse {
        repository.updateResult(id, status, paymentId)
        // 저장이 끝난 뒤에 알린다. 먼저 알리면 재고 서비스가 아직 안 끝난 주문을 뒤처리할 수 있다
        req?.let { eventPublisher.publishCompleted(OrderCompleted(id, it.productId, it.quantity, status)) }
        return CreateOrderResponse(id, status, paymentId = paymentId, reason = reason)
    }

    fun get(id: Long): Order? = repository.findById(id)
}
