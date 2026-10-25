package com.monimo.shop.agentextension

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.longs.shouldBeBetween

/** Collector 에 안 닿을 때 1 → 2 → 4 … 60초, 무작위 0 ~ 50% 를 더하고, 성공하면 처음으로 */
class BackoffTest : BehaviorSpec({
    Given("백오프") {
        When("연달아 실패하면") {
            val b = Backoff()
            Then("두 배씩 늘고 60초(+50%)에서 멈춘다") {
                b.nextDelayMillis().shouldBeBetween(1_000, 1_500)
                b.nextDelayMillis().shouldBeBetween(2_000, 3_000)
                b.nextDelayMillis().shouldBeBetween(4_000, 6_000)
                repeat(10) { b.nextDelayMillis() }
                b.nextDelayMillis().shouldBeBetween(60_000, 90_000)
            }
        }
        When("성공해서 초기화하면") {
            val b = Backoff()
            repeat(5) { b.nextDelayMillis() }
            b.reset()
            Then("다시 1초부터") {
                b.nextDelayMillis().shouldBeBetween(1_000, 1_500)
            }
        }
    }
})
