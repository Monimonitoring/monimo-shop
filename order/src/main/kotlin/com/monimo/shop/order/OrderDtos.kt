package com.monimo.shop.order

import java.time.LocalDateTime

/** POST /api/orders 요청. Watchdog 카나리 · k6 가 이 모양으로 보낸다. */
data class CreateOrderRequest(
    val productId: String,
    val quantity: Int,
    val amount: Int,
)

/** POST /api/orders 응답 (201 · 409 · 502 모두 이 모양) */
data class CreateOrderResponse(
    val orderId: Long,
    val status: OrderStatus,
    val paymentId: String? = null,
    val reason: String? = null,
)

/** GET /api/orders/{id} 응답 */
data class OrderResponse(
    val orderId: Long,
    val productId: String,
    val quantity: Int,
    val amount: Int,
    val status: OrderStatus,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(o: Order) = OrderResponse(o.id, o.productId, o.quantity, o.amount, o.status, o.createdAt)
    }
}
