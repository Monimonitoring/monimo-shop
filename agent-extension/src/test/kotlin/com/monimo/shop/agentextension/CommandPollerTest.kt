package com.monimo.shop.agentextension

import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 가짜 Collector(JDK HttpServer)를 세워 한 번 묻기(pollOnce)가 응답 코드마다 맞게 움직이는지 본다.
 * 에이전트 없이 돌므로 suppressInstrumentation 은 아무 일도 안 한다 (스팬 0건은 compose E2E 시험에서 본다).
 */
class CommandPollerTest : BehaviorSpec({
    data class Received(val method: String, val path: String, val token: String?, val body: String)

    class FakeCollector(private val pollStatus: Int, private val pollBody: String = "", private val resultStatuses: List<Int> = listOf(200)) {
        val received = CopyOnWriteArrayList<Received>()
        private val resultCalls = AtomicInteger()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/agent/commands") { ex ->
                val body = ex.requestBody.readAllBytes().decodeToString()
                received += Received(ex.requestMethod, ex.requestURI.toString(), ex.requestHeaders.getFirst(CommandPoller.TOKEN_HEADER), body)
                val (status, out) = if (ex.requestMethod == "GET") {
                    pollStatus to pollBody
                } else {
                    resultStatuses[minOf(resultCalls.getAndIncrement(), resultStatuses.size - 1)] to ""
                }
                val bytes = out.toByteArray()
                ex.sendResponseHeaders(status, if (status == 204 || bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty() && status != 204) ex.responseBody.use { it.write(bytes) }
                ex.close()
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
        fun stop() = server.stop(0)
    }

    fun poller(collector: FakeCollector) = CommandPoller(
        ExtensionConfig(collector.url, "t0ken", "shop-order", "shop-order-local-1"),
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
        ThreadDumper(),
    )

    Given("한 번 묻기") {
        When("수집기가 204(명령 없음)를 주면") {
            val c = FakeCollector(204)
            val ok = poller(c).pollOnce()
            c.stop()
            Then("정상이고, 토큰 헤더와 service · instance 를 붙여 물었다") {
                ok shouldBe true
                c.received.single().path shouldBe "/agent/commands?service=shop-order&instance=shop-order-local-1"
                c.received.single().token shouldBe "t0ken"
            }
        }
        When("수집기가 THREAD_DUMP 명령을 주면") {
            val c = FakeCollector(200, "")
            val cmd = """{"command_id":"c-42","type":"THREAD_DUMP","reply_to":"REPLY","timeout_ms":10000}"""
            val c2 = FakeCollector(200, cmd.replace("REPLY", c.url))
            val ok = poller(c2).pollOnce()
            c.stop(); c2.stop()
            Then("덤프를 떠서 reply_to 의 /agent/commands/{id}/result 로 보낸다") {
                ok shouldBe true
                val post = c.received.single { it.method == "POST" }
                post.path shouldBe "/agent/commands/c-42/result"
                post.token shouldBe "t0ken"
                post.body shouldContain "\"service\":\"shop-order\""
                post.body shouldContain "\"instance\":\"shop-order-local-1\""
                post.body shouldContain "\"format\":\"jstack\""
                post.body shouldContain "\\tat "
            }
        }
        When("THREAD_DUMP 가 아닌 명령이면") {
            val c = FakeCollector(200, """{"command_id":"c-1","type":"SHUTDOWN","reply_to":"http://127.0.0.1:1"}""")
            val ok = poller(c).pollOnce()
            c.stop()
            Then("무시한다 (아무것도 보내지 않는다)") {
                ok shouldBe true
                c.received.count { it.method == "POST" } shouldBe 0
            }
        }
        When("주소가 수집기가 아닌 곳이라 200 HTML 을 주면") {
            val c = FakeCollector(200, "<html>gateway</html>")
            val ok = poller(c).pollOnce()
            c.stop()
            Then("곧바로 다시 묻지 않고 백오프한다 (고속 루프 방지)") { ok shouldBe false }
        }
        When("수집기가 409 를 주면 (같은 이름표의 다른 JVM 이 폴링 중)") {
            val c = FakeCollector(409)
            val ok = poller(c).pollOnce()
            c.stop()
            Then("백오프한다 (서로 밀어내는 고속 루프 방지)") { ok shouldBe false }
        }
        When("수집기가 401 을 주면 (토큰 틀림)") {
            val c = FakeCollector(401)
            val ok = poller(c).pollOnce()
            c.stop()
            Then("백오프가 필요하다고 알린다") { ok shouldBe false }
        }
    }

    Given("결과 보내기") {
        When("회신 주소가 두 번 500 뒤 200 을 주면") {
            val reply = FakeCollector(204, resultStatuses = listOf(500, 500, 200))
            val p = poller(reply)
            p.sendResult(Command("c-7", "THREAD_DUMP", reply.url, 10_000), ThreadDumper().dump())
            reply.stop()
            Then("1초 간격으로 세 번까지 보내 성공한다") {
                reply.received.count { it.method == "POST" } shouldBe 3
            }
        }
        When("회신 주소가 404 를 주면 (이미 끝난 명령)") {
            val reply = FakeCollector(204, resultStatuses = listOf(404))
            poller(reply).sendResult(Command("c-8", "THREAD_DUMP", reply.url, 10_000), ThreadDumper().dump())
            reply.stop()
            Then("다시 보내지 않는다") {
                reply.received.count { it.method == "POST" } shouldBe 1
            }
        }
    }
})
