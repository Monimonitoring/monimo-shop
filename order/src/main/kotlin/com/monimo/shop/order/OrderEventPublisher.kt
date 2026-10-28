package com.monimo.shop.order

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * 주문이 끝났다는 것을 토픽에 넣는다. 재고 서비스가 읽어 조회 캐시를 다시 채운다 (#54, backend `#259` 문제 2).
 *
 * 왜 만드나 : 서버맵이 그리는 간선 종류가 셋(서비스 · DB · 외부)뿐이고 비동기 구간이 아예 안 보였다.
 * 쇼핑몰이 전부 동기 MVC 라(ADR `#33` · `#25`) PRODUCER · CONSUMER 스팬이 스팬 495만건 중 0건이었다(실측 2026-10-28).
 * 우리 백엔드의 Kafka 로는 못 만든다 : Collector · Ingester 는 트레이스를 끄고 있다(backend `#213` 되먹임).
 *
 * 서비스를 하나 더 만드는 대신 **있는 서비스 둘 사이에** 토픽을 둔다 : 서비스를 늘리면 에이전트 몫으로
 * 메모리가 75 ~ 126MB 붙고(`#51` 실측) 멘토가 "수보다 종류" 라고 했다.
 *
 * **스팬을 직접 만들지 않는다** (ADR `#33`). 에이전트의 Kafka 계측 모듈이 PRODUCER 스팬을 자동으로 만든다.
 *
 * 토픽 이름이 비어 있으면 아무것도 보내지 않는다 : 내 컴퓨터에서 직접 띄울 때와 테스트에는 Kafka 가 없다.
 * 보내기가 실패해도 주문은 성공으로 끝낸다 : 뒤처리(캐시 다시 채우기)는 안 해도 30초 TTL 이 지나면 맞춰진다.
 * 브로커가 죽어 있을 때 send 가 메타데이터를 기다리며 멈추는 것은 application.yml 의 max.block.ms 가 막는다
 */
@Component
class OrderEventPublisher(
    private val kafka: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    @Value("\${shop.order.completed-topic:}") private val topic: String,
) {
    fun publishCompleted(event: OrderCompleted) {
        if (topic.isEmpty()) return
        try {
            // 키를 상품 번호로 둔다. 같은 상품의 뒤처리가 한 칸에 들어가 캐시를 덮는 순서가 뒤집히지 않는다
            kafka.send(topic, event.productId, objectMapper.writeValueAsString(event))
        } catch (e: RuntimeException) {
            log.warn("주문 완료 발행 실패. 뒤처리를 건너뛴다 : orderId={}", event.orderId, e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(OrderEventPublisher::class.java)
    }
}
