package com.monimo.shop.agentextension;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 이 JVM 의 스레드 덤프를 jstack 과 같은 모양의 글로 만든다.
 *
 * <ul>
 *   <li>ThreadInfo.toString() 은 스택을 8줄에서 자르고 모양도 jstack 과 달라 직접 만든다. 화면 파서와 외부 분석 도구가 jstack 모양을 읽는다 (#57)
 *   <li>jstack 에 있고 여기 없는 것 : nid · tid · elapsed(ThreadMXBean 이 스레드 시작 시각을 주지 않는다) · JVM 내부 스레드
 *   <li>스택은 스레드마다 128줄까지 (MAX_DEPTH). 원인은 위쪽(지금 실행 중인 줄)에 있다
 *   <li>덤프는 모든 스레드를 잠깐 멈춘다(safepoint). 실측 : 결제 스레드 30개 약 15 ms, 주문 스레드 42개 66 ms (#34).
 *       락을 쥔 synchronizer 를 찾느라 힙을 훑어서(jstack -l 과 같음) 힙이 크면 더 길 수 있다. 10초 안에 또 오면 직전 결과를 다시 준다 (연타 보호)
 * </ul>
 */
final class ThreadDumper {

    static final int MAX_DEPTH = 128;
    static final long REUSE_MILLIS = 10_000;

    record Dump(Instant takenAt, long elapsedMs, int threadCount, String text) {}

    private final ThreadMXBean mx;
    private final Clock clock;
    private final String vmLine;
    private Dump last;

    ThreadDumper() {
        this(ManagementFactory.getThreadMXBean(), Clock.systemUTC());
    }

    ThreadDumper(ThreadMXBean mx, Clock clock) {
        this.mx = mx;
        this.clock = clock;
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        this.vmLine = "Full thread dump " + rt.getVmName() + " (" + rt.getVmVersion() + "):";
    }

    /** 일꾼 스레드 하나만 부르지만, 테스트 · 안전을 위해 동기화한다 */
    synchronized Dump dump() {
        Instant now = clock.instant();
        if (last != null && now.toEpochMilli() - last.takenAt().toEpochMilli() < REUSE_MILLIS) {
            return last;
        }
        long t0 = System.nanoTime();
        ThreadInfo[] infos = mx.dumpAllThreads(mx.isObjectMonitorUsageSupported(), mx.isSynchronizerUsageSupported(), MAX_DEPTH);
        long[] deadlocked = mx.isSynchronizerUsageSupported() ? mx.findDeadlockedThreads() : mx.findMonitorDeadlockedThreads();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        boolean cpu = mx.isThreadCpuTimeSupported() && mx.isThreadCpuTimeEnabled();

        StringBuilder b = new StringBuilder(infos.length * 1024);
        b.append(now).append('\n').append(vmLine).append("\n\n");
        int count = 0;
        for (ThreadInfo info : infos) {
            if (info == null) continue;
            count++;
            format(info, cpu ? mx.getThreadCpuTime(info.getThreadId()) : -1, b);
        }
        if (deadlocked != null && deadlocked.length > 0) appendDeadlocks(infos, deadlocked, b);
        last = new Dump(now, elapsedMs, count, b.toString());
        return last;
    }

    /** jstack 모양 한 스레드. cpuNanos 가 음수면(미지원 · 이미 끝난 스레드) cpu= 를 뺀다 */
    static void format(ThreadInfo t, long cpuNanos, StringBuilder b) {
        Thread.State state = t.getThreadState();
        StackTraceElement[] stack = t.getStackTrace();
        String top = stack.length > 0 ? stack[0].getClassName() + "." + stack[0].getMethodName() : "";
        Kind kind = kindOf(state, top);

        b.append('"').append(t.getThreadName()).append("\" #").append(t.getThreadId());
        if (t.isDaemon()) b.append(" daemon");
        b.append(" prio=").append(t.getPriority());
        if (cpuNanos >= 0) b.append(String.format(" cpu=%.2fms", cpuNanos / 1_000_000.0));
        b.append(' ').append(kind.head).append('\n');
        b.append("   java.lang.Thread.State: ").append(state);
        if (kind.detail != null) b.append(" (").append(kind.detail).append(')');
        b.append('\n');

        MonitorInfo[] monitors = t.getLockedMonitors();
        for (int i = 0; i < stack.length; i++) {
            b.append("\tat ").append(stack[i]).append('\n');
            if (i == 0 && t.getLockInfo() != null && kind.lockVerb != null) {
                b.append("\t- ").append(kind.lockVerb).append(' ').append(ref(t.getLockInfo())).append('\n');
            }
            for (MonitorInfo m : monitors) {
                if (m.getLockedStackDepth() == i) b.append("\t- locked ").append(ref(m)).append('\n');
            }
        }
        if (stack.length == MAX_DEPTH) b.append("\t...\n");

        b.append("\n   Locked ownable synchronizers:\n");
        LockInfo[] syncs = t.getLockedSynchronizers();
        if (syncs.length == 0) b.append("\t- None\n");
        for (LockInfo l : syncs) b.append("\t- ").append(ref(l)).append('\n');
        b.append('\n');
    }

    /** jstack 의 락 표기 {@code <0x주소> (a 클래스)}. 주소 자리에 identityHashCode — 같은 덤프 안에서 같은 객체를 잇는 용도라 충분하다 */
    static String ref(LockInfo l) {
        return String.format("<0x%016x> (a %s)", l.getIdentityHashCode(), l.getClassName());
    }

    /** 머리줄 설명 · 상태 줄의 괄호 설명 · 락 줄의 동사. 동사가 null 이면 락 줄을 쓰지 않는다 */
    record Kind(String head, String detail, String lockVerb) {}

    /** 상태와 맨 위 프레임으로 jstack 과 같은 낱말을 고른다 */
    static Kind kindOf(Thread.State state, String top) {
        return switch (state) {
            case BLOCKED -> new Kind("waiting for monitor entry", "on object monitor", "waiting to lock");
            case WAITING, TIMED_WAITING -> {
                if (top.equals("java.lang.Object.wait")) yield new Kind("in Object.wait()", "on object monitor", "waiting on");
                if (top.equals("java.lang.Thread.sleep")) yield new Kind("waiting on condition", "sleeping", null);
                if (top.endsWith("Unsafe.park")) yield new Kind("waiting on condition", "parking", "parking to wait for");
                yield new Kind("waiting on condition", null, null);
            }
            case RUNNABLE -> new Kind("runnable", null, null);
            case NEW -> new Kind("new", null, null);
            case TERMINATED -> new Kind("terminated", null, null);
        };
    }

    /** jstack 의 데드락 절. findDeadlockedThreads 는 평평한 id 목록이라 lockOwnerId 를 따라가 고리마다 한 절을 쓴다 */
    static void appendDeadlocks(ThreadInfo[] infos, long[] ids, StringBuilder b) {
        Map<Long, ThreadInfo> byId = new HashMap<>();
        for (ThreadInfo t : infos) if (t != null) byId.put(t.getThreadId(), t);
        Set<Long> seen = new HashSet<>();
        List<List<ThreadInfo>> cycles = new ArrayList<>();
        for (long id : ids) {
            List<ThreadInfo> cycle = new ArrayList<>();
            long cur = id;
            while (cur >= 0 && !seen.contains(cur) && byId.containsKey(cur)) {
                seen.add(cur);
                ThreadInfo t = byId.get(cur);
                cycle.add(t);
                cur = t.getLockOwnerId();
            }
            if (!cycle.isEmpty()) cycles.add(cycle);
        }
        for (List<ThreadInfo> cycle : cycles) {
            b.append("Found one Java-level deadlock:\n=============================\n");
            for (ThreadInfo t : cycle) {
                b.append('"').append(t.getThreadName()).append("\":\n");
                if (t.getLockInfo() != null) b.append("  waiting to lock ").append(ref(t.getLockInfo())).append(",\n");
                if (t.getLockOwnerName() != null) b.append("  which is held by \"").append(t.getLockOwnerName()).append("\"\n");
            }
            b.append("\nJava stack information for the threads listed above:\n===================================================\n");
            for (ThreadInfo t : cycle) {
                b.append('"').append(t.getThreadName()).append("\":\n");
                for (StackTraceElement e : t.getStackTrace()) b.append("\tat ").append(e).append('\n');
            }
            b.append('\n');
        }
        b.append("Found ").append(cycles.size()).append(cycles.size() == 1 ? " deadlock.\n" : " deadlocks.\n");
    }
}
