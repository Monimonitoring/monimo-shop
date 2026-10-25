package com.monimo.shop.agentextension;

import io.opentelemetry.javaagent.extension.AgentListener;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import java.util.logging.Logger;

/**
 * Extension 의 시작점. 에이전트 설치가 끝나면 불린다.
 *
 * <p>이 메서드는 앱 main() 보다 먼저, main 스레드에서 불린다 (#34 research ⑤ 시험 : afterAgent 3번째 줄, 앱 시작 14번째 줄).
 * 여기서 Collector 를 기다리면 쇼핑몰 기동이 그만큼 늦어지므로, 데몬 스레드(일꾼) 하나만 띄우고 바로 return 한다.
 * 데몬이라 쇼핑몰이 꺼질 때 붙잡지 않는다.
 */
public final class ThreadDumpAgentListener implements AgentListener {

    private static final Logger log = Logger.getLogger(ThreadDumpAgentListener.class.getName());

    @Override
    public void afterAgent(AutoConfiguredOpenTelemetrySdk sdk) {
        ExtensionConfig config = ExtensionConfig.get();
        if (config == null || !config.enabled()) {
            log.info("[monimo] 스레드 덤프 Extension 꺼짐 : 토큰(OTEL_MONIMO_AGENT_TOKEN) 또는 service.name · service.instance.id 가 없다");
            return;
        }
        Thread poller = new Thread(new CommandPoller(config), "monimo-command-poller");
        poller.setDaemon(true);
        poller.start();
        log.info("[monimo] 스레드 덤프 Extension 시작 : " + config.service() + "/" + config.instance() + " → " + config.collectorUrl());
    }
}
