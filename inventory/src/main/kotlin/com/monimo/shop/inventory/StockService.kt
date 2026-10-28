package com.monimo.shop.inventory

import org.slf4j.LoggerFactory
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 재고 조회 · 차감 · 복원.
 *  - 조회는 Redis 캐시(cache-aside). 처음엔 MySQL 을 읽어 Redis 에 넣고, 다음부터는 Redis 값을 준다 (research ⑧ : b1)
 *  - 차감은 MySQL 에서 조건부 UPDATE 로 하고, 커밋된 뒤에 캐시를 지운다 (evictAfterCommit)
 *  - 복원은 주문 번호로 한 번만 된다 (stock_deductions.restored)
 * X-Shop-Fault 헤더로 일부러 실패하거나 느려진다 (차감 때만). k6 가 이 스위치를 쓴다.
 *  - inventory-error → InjectedFailure (컨트롤러가 500 으로)
 *  - inventory-slow  → 1.5초 잠들었다 정상 처리
 */
@Service
class StockService(
    private val repository: StockRepository,
    private val cacheManager: CacheManager,
) {
    class InjectedFailure : RuntimeException("injected failure")
    class ProductNotFound(productId: String) : RuntimeException("product not found: $productId")
    class SoldOut(productId: String) : RuntimeException("sold out: $productId")
    class DeductionNotFound(orderId: Long) : RuntimeException("deduction not found: $orderId")

    /** 캐시 이름 stock, 키는 상품 번호. Redis 에는 "stock::P-100" 으로 들어간다 */
    @Cacheable(cacheNames = [CACHE], key = "#productId")
    fun find(productId: String): StockResponse =
        StockResponse(productId, repository.findQty(productId) ?: throw ProductNotFound(productId))

    @Transactional
    fun deduct(req: DeductRequest, fault: String?): StockChangeResponse {
        // 0 이하를 받으면 qty >= ? 가 늘 참이라 재고가 거꾸로 는다. 컨트롤러가 400 으로 바꾼다
        require(req.quantity > 0) { "quantity must be positive: ${req.quantity}" }
        when (fault) {
            FAULT_ERROR -> throw InjectedFailure()
            FAULT_SLOW -> Thread.sleep(SLOW_MILLIS)
        }
        // 같은 주문이 다시 오면(주문 서비스 재시도) 차감하지 않고 지금 수량만 돌려준다
        if (!repository.insertDeduction(req.orderId, req.productId, req.quantity)) {
            return StockChangeResponse(req.orderId, req.productId, repository.findQty(req.productId) ?: 0)
        }
        if (!repository.deductIfEnough(req.productId, req.quantity)) {
            // 예외로 끝나므로 @Transactional 이 위의 차감 기록 INSERT 까지 롤백한다
            repository.findQty(req.productId) ?: throw ProductNotFound(req.productId)
            throw SoldOut(req.productId)
        }
        evict(req.productId)
        return StockChangeResponse(req.orderId, req.productId, repository.findQty(req.productId) ?: 0)
    }

    @Transactional
    fun restore(req: RestoreRequest): StockChangeResponse {
        val d = repository.findDeduction(req.orderId) ?: throw DeductionNotFound(req.orderId)
        // 두 번째 복원 요청은 재고를 더하지 않는다 (멱등)
        if (repository.markRestored(req.orderId)) {
            repository.restore(d.productId, d.quantity)
            evict(d.productId)
        }
        return StockChangeResponse(req.orderId, d.productId, repository.findQty(d.productId) ?: 0)
    }

    /**
     * 주문이 끝난 뒤 조회 캐시를 **지우지 않고 다시 채운다** (#54 비동기 뒤처리).
     *
     * 차감은 커밋 뒤에 캐시를 지우기만 한다(evict). 그러면 다음 손님 조회가 반드시 MySQL 을 읽는다.
     * 주문이 끝난 시점에 미리 채워 두면 그 조회가 Redis 에서 끝난다.
     * 이 메서드는 토픽에서 온 알림으로 불린다(OrderCompletedListener). 손님 요청 흐름 밖이라 늦어도 상관없다.
     *
     * 상품이 없으면 아무것도 넣지 않고 null 을 돌려준다. 없는 상품을 캐시에 넣으면 404 가 캐시된다.
     * Redis 가 죽어 PUT 이 실패해도 로그만 남긴다 : 안 채워도 다음 조회가 MySQL 을 읽어 맞춰진다
     */
    fun refreshCache(productId: String): Int? {
        val qty = repository.findQty(productId) ?: return null
        val cache = cacheManager.getCache(CACHE) ?: return qty
        try {
            cache.put(productId, StockResponse(productId, qty))
        } catch (e: RuntimeException) {
            log.warn("캐시 미리 채우기 실패 (Redis 장애?). 다음 조회가 MySQL 을 읽는다 : productId={}", productId, e)
        }
        return qty
    }

    /**
     * 커밋 뒤에 캐시를 지운다. 커밋 전에 지우면 그 틈에 다른 조회가 옛 값을 다시 채울 수 있다.
     * Redis 가 죽어 DEL 이 실패해도 차감은 이미 커밋됐으니 로그만 남긴다 (TTL 30초가 지나면 맞춰진다).
     * @Cacheable 쪽은 CacheConfig 의 LoggingCacheErrorHandler 가 같은 일을 하지만, 직접 부르는 evict 에는 그 처리기가 안 걸려 여기서 잡는다
     */
    private fun evict(productId: String) {
        val cache = cacheManager.getCache(CACHE) ?: return
        val evictQuietly = {
            try {
                cache.evict(productId)
            } catch (e: RuntimeException) {
                log.warn("캐시 evict 실패 (Redis 장애?). TTL 로 맞춰진다 : productId={}", productId, e)
            }
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = evictQuietly()
            })
        } else {
            evictQuietly()
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(StockService::class.java)
        const val CACHE = "stock"
        const val FAULT_HEADER = "X-Shop-Fault"
        const val FAULT_ERROR = "inventory-error"
        const val FAULT_SLOW = "inventory-slow"
        const val SLOW_MILLIS = 1_500L
    }
}
