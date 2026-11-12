package com.monimo.shop.agentextension

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import java.lang.management.ManagementFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch

/** 이 테스트 JVM 의 진짜 덤프로 모양과 연타 보호(10초)를 본다 */
class ThreadDumperTest : BehaviorSpec({
    class MovableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }

    /** 스레드들이 기대한 상태가 될 때까지 최대 2초 기다린다 */
    fun settle(vararg expected: Pair<Thread, Thread.State>) {
        repeat(200) {
            if (expected.all { (t, s) -> t.state == s }) return
            Thread.sleep(10)
        }
    }

    Given("덤프") {
        val clock = MovableClock(Instant.parse("2026-10-08T00:00:00Z"))
        val dumper = ThreadDumper(ManagementFactory.getThreadMXBean(), clock)

        When("처음 뜨면") {
            val dump = dumper.dump()
            Then("jstack 모양 — 머리말, 스레드 머리줄 · 상태 줄 · cpu= · at 줄 · synchronizer 절") {
                dump.threadCount() shouldBeGreaterThan 0
                dump.text() shouldContain "Full thread dump "
                dump.text() shouldContain "\"${Thread.currentThread().name}\" #"
                dump.text() shouldContain "   java.lang.Thread.State: RUNNABLE"
                Regex(""" cpu=\d+\.\d\dms """).containsMatchIn(dump.text()) shouldBe true
                dump.text() shouldContain "\tat "
                dump.text() shouldContain "   Locked ownable synchronizers:\n\t- None"
                dump.text() shouldNotContain "Found one Java-level deadlock"
            }
            Then("스레드마다 스택이 128줄을 넘지 않는다") {
                dump.text().split("\n\n").forEach { block ->
                    block.lines().count { it.startsWith("\tat ") } shouldBeLessThanOrEqual ThreadDumper.MAX_DEPTH
                }
            }
        }
        When("10초 안에 또 오면") {
            val first = dumper.dump()
            clock.now = clock.now.plusSeconds(9)
            Then("직전 결과를 그대로 준다 (JVM 을 다시 멈추지 않는다)") {
                (dumper.dump() === first) shouldBe true
            }
        }
        When("10초가 지나면") {
            val first = dumper.dump()
            clock.now = clock.now.plusSeconds(10)
            Then("새로 뜬다") {
                (dumper.dump() === first) shouldBe false
                dumper.dump().takenAt() shouldNotBe first.takenAt()
            }
        }
    }

    Given("Object.wait 로 기다리는 스레드와 서로 락을 쥔 두 스레드") {
        val lock = Object()
        val waiter = Thread({ synchronized(lock) { lock.wait() } }, "dumper-test-waiter").apply { isDaemon = true; start() }
        val a = Object()
        val b = Object()
        val both = CountDownLatch(2)
        fun locker(first: Any, second: Any, name: String) = Thread({
            synchronized(first) {
                both.countDown()
                both.await()
                synchronized(second) {}
            }
        }, name).apply { isDaemon = true; start() }
        val dead1 = locker(a, b, "dumper-test-dead-1")
        val dead2 = locker(b, a, "dumper-test-dead-2")
        settle(waiter to Thread.State.WAITING, dead1 to Thread.State.BLOCKED, dead2 to Thread.State.BLOCKED)
        val text = ThreadDumper(ManagementFactory.getThreadMXBean(), Clock.systemUTC()).dump().text()

        When("뜨면") {
            Then("기다리는 스레드는 WAITING (on object monitor) 와 waiting on <0x…> (a 클래스) 줄") {
                val block = text.substringAfter("\"dumper-test-waiter\" #").substringBefore("\n\n")
                block shouldContain " in Object.wait()\n   java.lang.Thread.State: WAITING (on object monitor)"
                Regex("""\t- waiting on <0x[0-9a-f]{16}> \(a java\.lang\.Object\)""").containsMatchIn(block) shouldBe true
            }
            Then("막힌 스레드는 BLOCKED (on object monitor) 와 waiting to lock · locked 줄") {
                val block = text.substringAfter("\"dumper-test-dead-1\" #").substringBefore("\n\n")
                block shouldContain " waiting for monitor entry\n   java.lang.Thread.State: BLOCKED (on object monitor)"
                Regex("""\t- waiting to lock <0x[0-9a-f]{16}> \(a java\.lang\.Object\)""").containsMatchIn(block) shouldBe true
                Regex("""\t- locked <0x[0-9a-f]{16}> \(a java\.lang\.Object\)""").containsMatchIn(block) shouldBe true
            }
            Then("끝에 데드락 절이 붙고 두 스레드가 서로를 쥐고 있다고 적힌다") {
                val section = text.substringAfter("Found one Java-level deadlock:")
                section shouldContain "\"dumper-test-dead-1\":\n  waiting to lock <0x"
                section shouldContain "which is held by \"dumper-test-dead-2\""
                section shouldContain "which is held by \"dumper-test-dead-1\""
                section shouldContain "Java stack information for the threads listed above:"
                text shouldContain "Found 1 deadlock."
            }
        }
        waiter.interrupt()
    }
})
