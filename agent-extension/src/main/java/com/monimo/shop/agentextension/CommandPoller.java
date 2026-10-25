package com.monimo.shop.agentextension;

import io.opentelemetry.api.impl.InstrumentationUtil;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 일꾼 스레드의 일. Collector 에 "명령 있어?" 를 묻고(롱폴링), 명령이 오면 덤프를 떠서 회신 주소로 보낸다.
 *
 * <pre>
 * loop {
 *   GET {collector}/agent/commands?service=…&instance=…   (Collector 는 최대 25초 붙잡는다, 우리는 30초까지 기다린다)
 *     204       → 명령 없음, 곧바로 다시 묻는다
 *     200       → THREAD_DUMP 면 덤프 → POST {reply_to}/agent/commands/{id}/result (1초 간격 3번까지)
 *                 읽을 수 없는 본문이면 백오프 (주소가 Collector 가 아닌 곳을 가리킬 때 고속 루프를 막는다)
 *     409       → 같은 이름표의 다른 JVM 이 폴링 중 (이름표 중복 설정). 백오프
 *     그 밖     → 1 → 60초 백오프 뒤 다시
 * }
 * </pre>
 *
 * 모든 HTTP 호출은 InstrumentationUtil.suppressInstrumentation 으로 감싼다. 안 감싸면 에이전트가 이 호출도
 * 스팬으로 남겨 25초마다 가짜 트레이스가 쌓인다 (#34 research ⑤ 시험 : 감싸면 0개, 안 감싸면 2개).
 */
final class CommandPoller implements Runnable {

    static final String TOKEN_HEADER = "X-Monimo-Agent-Token";
    static final Duration POLL_TIMEOUT = Duration.ofSeconds(30);   // Collector 대기(25초)보다 길게
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration RESULT_TIMEOUT = Duration.ofSeconds(10);
    static final int RESULT_ATTEMPTS = 3;
    static final long RESULT_RETRY_MILLIS = 1_000;

    private static final Logger log = Logger.getLogger(CommandPoller.class.getName());

    private final ExtensionConfig config;
    private final HttpClient http;
    private final ThreadDumper dumper;
    private final Backoff backoff = new Backoff();
    private String lastFailure;

    CommandPoller(ExtensionConfig config) {
        this(config, HttpClient.newBuilder()
                // pg-stub 때처럼 h2c 업그레이드로 끊기지 않게 HTTP/1.1 로 고정한다 (shop #20)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build(), new ThreadDumper());
    }

    CommandPoller(ExtensionConfig config, HttpClient http, ThreadDumper dumper) {
        this.config = config;
        this.http = http;
        this.dumper = dumper;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (!pollOnce()) sleep(backoff.nextDelayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                // Collector 가 꺼져 있거나 재시작 중. 다음 GET 이 새 연결로 나가므로 따로 재연결 코드는 없다.
                // Exception 이 아니라 Throwable 을 잡는다 : Error(에이전트 버전 불일치 · 메모리)로 일꾼이 조용히 죽으면 다시 뜨지 않는다.
                // 같은 종류의 실패는 처음 한 번만 WARNING (Collector 가 꺼진 동안 로그가 넘치지 않게)
                String kind = e.getClass().getName();
                if (!kind.equals(lastFailure)) {
                    log.log(Level.WARNING, "[monimo] 명령 폴링 실패 (같은 실패는 다시 안 찍는다) : " + e);
                    lastFailure = kind;
                }
                try {
                    sleep(backoff.nextDelayMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** 한 번 묻는다. 정상(200 · 204)이면 true, 백오프가 필요하면 false */
    boolean pollOnce() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(pollUri())
                .timeout(POLL_TIMEOUT)
                .header(TOKEN_HEADER, config.token())
                .GET()
                .build();
        HttpResponse<String> response = quietly(() -> http.send(request, HttpResponse.BodyHandlers.ofString()));
        if (response.statusCode() == 204) {
            backoff.reset();
            lastFailure = null;
            return true;
        }
        if (response.statusCode() != 200) {
            log.warning("[monimo] 수집기가 명령 폴링에 " + response.statusCode() + " 로 답했다 (401 이면 토큰 확인)");
            return false;
        }
        Command command = Command.parse(response.body());
        if (command == null) {
            log.warning("[monimo] 명령으로 읽을 수 없는 200 응답 (수집기 주소 확인) : " + abbreviate(response.body()));
            return false;
        }
        backoff.reset();
        lastFailure = null;
        // THREAD_DUMP 말고는 무시한다. Collector 가 뚫려도 쇼핑몰에서 임의 동작을 시킬 수 없게 (합의안 4)
        if (!Command.THREAD_DUMP.equals(command.type())) {
            log.warning("[monimo] 모르는 명령을 무시했다 : " + command.type());
            return true;
        }
        sendResult(command, dumper.dump());
        return true;
    }

    /** 덤프를 명령을 준 Collector(reply_to)로 보낸다. 실패하면 1초 간격 3번까지, 그래도 안 되면 로그만 남기고 버린다 */
    void sendResult(Command command, ThreadDumper.Dump dump) throws InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(stripSlash(command.replyTo()) + "/agent/commands/"
                        + URLEncoder.encode(command.commandId(), StandardCharsets.UTF_8) + "/result"))
                .timeout(RESULT_TIMEOUT)
                .header(TOKEN_HEADER, config.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(resultJson(dump)))
                .build();
        for (int attempt = 1; attempt <= RESULT_ATTEMPTS; attempt++) {
            try {
                int status = quietly(() -> http.send(request, HttpResponse.BodyHandlers.discarding())).statusCode();
                if (status == 200) return;
                // 404 = Collector 가 이미 기다리기를 끝냈다(시간 초과). 다시 보내도 받을 쪽이 없다
                if (status == 404) {
                    log.warning("[monimo] 덤프 결과를 받을 명령이 이미 끝났다 : " + command.commandId());
                    return;
                }
                log.warning("[monimo] 덤프 결과 전송 " + attempt + "회 실패 : " + status);
            } catch (Exception e) {
                if (e instanceof InterruptedException ie) throw ie;
                log.warning("[monimo] 덤프 결과 전송 " + attempt + "회 실패 : " + e);
            }
            if (attempt < RESULT_ATTEMPTS) sleep(RESULT_RETRY_MILLIS);
        }
    }

    String resultJson(ThreadDumper.Dump dump) {
        return "{\"service\":" + Json.quote(config.service())
                + ",\"instance\":" + Json.quote(config.instance())
                + ",\"taken_at\":" + Json.quote(dump.takenAt().toString())
                + ",\"elapsed_ms\":" + dump.elapsedMs()
                + ",\"thread_count\":" + dump.threadCount()
                + ",\"format\":\"jstack\""
                + ",\"dump\":" + Json.quote(dump.text())
                + "}";
    }

    URI pollUri() {
        return URI.create(stripSlash(config.collectorUrl()) + "/agent/commands?service="
                + URLEncoder.encode(config.service(), StandardCharsets.UTF_8)
                + "&instance=" + URLEncoder.encode(config.instance(), StandardCharsets.UTF_8));
    }

    /** 이 블록 안의 HTTP 호출은 에이전트가 스팬으로 남기지 않는다 */
    private static <T> T quietly(Callable<T> call) throws Exception {
        Object[] result = new Object[1];
        Exception[] error = new Exception[1];
        InstrumentationUtil.suppressInstrumentation(() -> {
            try {
                result[0] = call.call();
            } catch (Exception e) {
                error[0] = e;
            }
        });
        if (error[0] != null) throw error[0];
        @SuppressWarnings("unchecked")
        T value = (T) result[0];
        return value;
    }

    private static String abbreviate(String s) {
        return s == null || s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }
}
