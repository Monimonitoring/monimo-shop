package com.monimo.shop.order

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.kafka.core.KafkaTemplate

/**
 * 주문 완료 발행 (#54). Kafka 없이 KafkaTemplate 을 Mockito 가짜로 바꿔 본다.
 *  - 토픽 이름이 비어 있으면 아예 보내지 않는다 (내 컴퓨터 · 테스트)
 *  - 키는 상품 번호 (같은 상품의 뒤처리 순서가 뒤집히지 않게)
 *  - 보내기가 터져도 주문은 끝난다
 */
class OrderEventPublisherTest : BehaviorSpec({
    val objectMapper = ObjectMapper()
    val event = OrderCompleted(7, "P-100", 2, OrderStatus.PAID)

    @Suppress("UNCHECKED_CAST")
    fun template(): KafkaTemplate<String, String> = Mockito.mock(KafkaTemplate::class.java) as KafkaTemplate<String, String>

    Given("주문 완료 발행") {
        When("토픽 이름이 있으면") {
            val kafka = template()
            OrderEventPublisher(kafka, objectMapper, "shop.order.completed").publishCompleted(event)

            Then("그 토픽에 상품 번호를 키로 보낸다") {
                val topic = ArgumentCaptor.forClass(String::class.java)
                val key = ArgumentCaptor.forClass(String::class.java)
                val value = ArgumentCaptor.forClass(String::class.java)
                verify(kafka).send(topic.capture(), key.capture(), value.capture())
                topic.value shouldBe "shop.order.completed"
                key.value shouldBe "P-100"
                objectMapper.readTree(value.value)["orderId"].asLong() shouldBe 7
                objectMapper.readTree(value.value)["status"].asText() shouldBe "PAID"
            }
        }

        When("토픽 이름이 비어 있으면") {
            val kafka = template()
            OrderEventPublisher(kafka, objectMapper, "").publishCompleted(event)

            Then("아무것도 보내지 않는다") {
                verify(kafka, never()).send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString())
            }
        }

        When("보내다가 터지면") {
            val kafka = template()
            Mockito.`when`(kafka.send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenThrow(IllegalStateException("broker down"))

            Then("예외를 삼키고 주문 흐름을 막지 않는다") {
                OrderEventPublisher(kafka, objectMapper, "shop.order.completed").publishCompleted(event)
            }
        }
    }
})
