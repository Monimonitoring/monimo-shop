package com.monimo.shop.inventory

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * 주문이 끝났다는 알림을 읽어 재고 조회 캐시를 다시 채운다 (#54, backend `#259` 문제 2).
 *
 * 서버맵에 비동기 간선을 만드는 자리다. 에이전트의 Kafka 계측 모듈이 CONSUMER 스팬을 자동으로 만들고,
 * 이 메서드 안에서 도는 MySQL · Redis 호출이 그 스팬의 자식이 된다. **스팬을 직접 만들지 않는다** (ADR `#33`).
 *
 * `@ConditionalOnProperty` 로 토픽 이름이 있을 때만 만든다 : 없으면 빈이 없어서 에이전트도 브로커도 안 찾는다.
 * 내 컴퓨터에서 직접 띄울 때와 테스트에는 Kafka 가 없다.
 *
 * 실패하면 다시 안 읽는다(기본 설정대로 넘긴다) : 뒤처리를 못 해도 30초 TTL 이 지나면 캐시가 맞춰진다.
 * 재시도를 걸면 손님 조회와 상관없는 일로 소비가 막힌다
 */
@Component
@ConditionalOnProperty("shop.inventory.completed-topic")
class OrderCompletedListener(
    private val stockService: StockService,
    private val objectMapper: ObjectMapper,
) {
    // 소비자 그룹은 application.yml 의 spring.kafka.consumer.group-id (shop-inventory)
    @KafkaListener(topics = ["\${shop.inventory.completed-topic}"])
    fun onOrderCompleted(payload: String) {
        val event = try {
            objectMapper.readTree(payload)
        } catch (e: JsonProcessingException) {
            // JsonParseException 은 IOException 계열이라 RuntimeException 으로는 안 잡힌다 (실측)
            log.warn("주문 완료 알림을 못 읽었다. 넘긴다 : payload={}", payload, e)
            return
        }
        val productId = event.path("productId").asText("")
        if (productId.isEmpty()) {
            log.warn("주문 완료 알림에 productId 가 없다. 넘긴다 : payload={}", payload)
            return
        }
        val qty = stockService.refreshCache(productId)
        log.info("재고 조회 캐시를 다시 채웠다 : productId={} qty={}", productId, qty)
    }

    companion object {
        private val log = LoggerFactory.getLogger(OrderCompletedListener::class.java)
    }
}
