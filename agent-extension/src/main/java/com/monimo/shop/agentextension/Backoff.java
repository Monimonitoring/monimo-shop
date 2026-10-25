package com.monimo.shop.agentextension;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Collector 에 닿지 않을 때 다시 묻기까지 쉬는 시간. 1 → 2 → 4 … 60초까지 두 배씩, 성공하면 처음으로.
 * 쇼핑몰이 여러 대면 Collector 가 다시 떴을 때 한꺼번에 몰리지 않게 0 ~ 50% 를 무작위로 더한다 (합의안 3).
 */
final class Backoff {

    static final long INITIAL_MILLIS = 1_000;
    static final long MAX_MILLIS = 60_000;

    private long next = INITIAL_MILLIS;

    /** 이번에 쉴 시간을 주고 다음 번을 두 배로 늘린다 */
    long nextDelayMillis() {
        long base = next;
        next = Math.min(next * 2, MAX_MILLIS);
        return base + ThreadLocalRandom.current().nextLong(base / 2 + 1);
    }

    void reset() {
        next = INITIAL_MILLIS;
    }
}
