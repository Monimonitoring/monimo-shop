package com.monimo.shop.inventory

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/stock")
class StockController(private val service: StockService) {

    /** 손님(k6)이 주문 전에 보는 재고. 게이트웨이가 이리로 넘긴다. Service map 의 inventory → Redis 화살표가 여기서 나온다 */
    @GetMapping("/{productId}")
    fun get(@PathVariable productId: String): StockResponse = service.find(productId)

    /** 주문 서비스가 결제 전에 부른다. 모자라면 409 */
    @PostMapping("/deduct")
    fun deduct(
        @RequestBody req: DeductRequest,
        @RequestHeader(StockService.FAULT_HEADER, required = false) fault: String?,
    ): StockChangeResponse = service.deduct(req, fault)

    /** 결제가 실패한 주문이 부른다. 같은 주문으로 두 번 불러도 한 번만 더해진다 */
    @PostMapping("/restore")
    fun restore(@RequestBody req: RestoreRequest): StockChangeResponse = service.restore(req)

    /** 재고 부족은 409. 요청은 맞지만 지금 상태로는 처리할 수 없다는 뜻이라 4xx 비율 규칙 재료가 된다 */
    @ExceptionHandler(StockService.SoldOut::class)
    fun onSoldOut(e: StockService.SoldOut): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("reason" to "sold out"))

    @ExceptionHandler(StockService.ProductNotFound::class, StockService.DeductionNotFound::class)
    fun onNotFound(e: RuntimeException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("reason" to e.message.orEmpty()))

    /** 수량이 0 이하면 400 */
    @ExceptionHandler(IllegalArgumentException::class)
    fun onBadRequest(e: IllegalArgumentException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("reason" to e.message.orEmpty()))

    /** 주입된 실패는 500 으로. 응답 모양 `{reason}` 을 payment 와 맞춘다 */
    @ExceptionHandler(StockService.InjectedFailure::class)
    fun onInjected(e: StockService.InjectedFailure): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(mapOf("reason" to e.message.orEmpty()))
}
