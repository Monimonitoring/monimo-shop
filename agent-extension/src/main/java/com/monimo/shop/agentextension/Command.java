package com.monimo.shop.agentextension;

/**
 * Collector 가 GET /agent/commands 의 200 응답으로 주는 명령.
 * {"command_id":"…","type":"THREAD_DUMP","reply_to":"http://collector-0.collector:8081","timeout_ms":10000}
 *
 * <p>reply_to 는 명령을 준 Collector 자신의 주소다. 결과는 Service 이름이 아니라 이 주소로 보낸다 (합의안 2 :
 * Collector 가 여러 대면 다른 Collector 로 가 버려 기다리는 쪽이 못 받는다).
 */
record Command(String commandId, String type, String replyTo, long timeoutMs) {

    static final String THREAD_DUMP = "THREAD_DUMP";

    /** 명령 JSON 을 읽는다. 라이브러리를 쓰지 않으려고 평평한 객체만 읽는 작은 파서(Json)를 쓴다. 모양이 틀리면 null */
    static Command parse(String body) {
        var fields = Json.parseFlatObject(body);
        if (fields == null) return null;
        String id = fields.get("command_id");
        String type = fields.get("type");
        String replyTo = fields.get("reply_to");
        if (id == null || type == null || replyTo == null) return null;
        long timeout;
        try {
            timeout = Long.parseLong(fields.getOrDefault("timeout_ms", "10000"));
        } catch (NumberFormatException e) {
            timeout = 10_000;
        }
        return new Command(id, type, replyTo, timeout);
    }
}
