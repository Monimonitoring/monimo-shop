package com.monimo.shop.order

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/orders")
class OrderController(private val service: OrderService) {

    /** Watchdog 카나리와 k6 가 부르는 문. 결제 · 재고 서비스 실패면 502(5xx 규칙), 재고 부족이면 409(4xx 규칙)로 답한다. */
    @PostMapping
    fun create(
        @RequestBody req: CreateOrderRequest,
        @RequestHeader(PaymentClient.FAULT_HEADER, required = false) fault: String?,
    ): ResponseEntity<CreateOrderResponse> {
        val res = service.create(req, fault)
        val status = when (res.status) {
            OrderStatus.PAID -> HttpStatus.CREATED
            OrderStatus.SOLD_OUT -> HttpStatus.CONFLICT
            else -> HttpStatus.BAD_GATEWAY
        }
        return ResponseEntity.status(status).body(res)
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): ResponseEntity<Any> =
        service.get(id)
            ?.let { ResponseEntity.ok(OrderResponse.from(it)) }
            ?: ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("reason" to "order not found"))
}
