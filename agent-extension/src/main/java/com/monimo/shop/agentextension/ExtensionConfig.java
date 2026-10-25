package com.monimo.shop.agentextension;

/**
 * Extension 이 쓰는 설정 · 에이전트 식별 값. ConfigCapture 가 에이전트가 뜰 때 한 번 채운다.
 *
 * <p>설정은 에이전트 설정 체계를 그대로 탄다 : -D > 환경변수 > agent.properties (#30 과 같은 규칙).
 * <ul>
 *   <li>otel.monimo.collector.url (OTEL_MONIMO_COLLECTOR_URL) : Collector HTTP 주소. 기본 http://collector:8081
 *       (OTLP 를 받는 4317 이 아니라 Spring MVC 쪽 포트다)
 *   <li>otel.monimo.agent.token (OTEL_MONIMO_AGENT_TOKEN) : 에이전트 전용 토큰. 비면 Extension 이 아무것도 안 한다
 * </ul>
 * 식별 값은 설정이 아니라 에이전트가 만든 Resource 에서 읽는다 (service.name · service.instance.id). 백엔드 agent_id 와 같은 값이다.
 */
record ExtensionConfig(String collectorUrl, String token, String service, String instance) {

    static final String DEFAULT_COLLECTOR_URL = "http://collector:8081";

    private static volatile ExtensionConfig current;

    static void set(ExtensionConfig config) {
        current = config;
    }

    /** ConfigCapture 가 아직 안 불렸으면 null */
    static ExtensionConfig get() {
        return current;
    }

    boolean enabled() {
        return token != null && !token.isBlank() && service != null && instance != null;
    }
}
