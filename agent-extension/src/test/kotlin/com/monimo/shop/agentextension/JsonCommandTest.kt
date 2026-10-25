package com.monimo.shop.agentextension

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/** 라이브러리 없이 쓴 작은 JSON 도우미와 명령 읽기 (Extension 의존성 0개라 직접 만든 부분) */
class JsonCommandTest : BehaviorSpec({
    Given("Collector 가 준 명령 JSON") {
        When("필드가 다 있으면") {
            val cmd = Command.parse("""{"command_id":"c-1","type":"THREAD_DUMP","reply_to":"http://collector:8081","timeout_ms":12000}""")
            Then("command_id · type · reply_to · timeout_ms 를 읽는다") {
                cmd shouldBe Command("c-1", "THREAD_DUMP", "http://collector:8081", 12_000)
            }
        }
        When("timeout_ms 가 없으면") {
            Then("10초로 본다") {
                Command.parse("""{"command_id":"c-1","type":"THREAD_DUMP","reply_to":"http://x"}""")!!.timeoutMs() shouldBe 10_000
            }
        }
        When("reply_to 가 없거나 JSON 이 깨졌으면") {
            Then("null 이라 무시된다") {
                Command.parse("""{"command_id":"c-1","type":"THREAD_DUMP"}""") shouldBe null
                Command.parse("""{"command_id":"c-1",""") shouldBe null
                Command.parse("not json") shouldBe null
            }
        }
    }

    Given("덤프 본문을 JSON 문자열로") {
        When("줄바꿈 · 탭 · 따옴표 · 역슬래시가 있으면") {
            val raw = "\"main\" #1 prio=5\n\tat a.B.c(B.java:1)\\x"
            val quoted = Json.quote(raw)
            Then("이스케이프되고, 다시 읽으면 원문이 된다") {
                quoted shouldBe "\"\\\"main\\\" #1 prio=5\\n\\tat a.B.c(B.java:1)\\\\x\""
                Json.parseFlatObject("{\"dump\":$quoted}")!!["dump"] shouldBe raw
            }
        }
    }
})
