package com.buhmwoo.oneask.common.config;

import org.springframework.context.annotation.Bean; // ✅ 스프링 컨테이너에 빈을 등록하기 위해 Bean 너테이션을 임포트합니다.
import org.springframework.context.annotation.Configuration; // ✅ 설정 클래스를 선언하기 위해 Configuration 애너테이션을 임포트합니다.
import org.springframework.http.client.reactive.ReactorClientHttpConnector; // ✅ Reactor 기반 Netty 커넥터를 사용하기 위해 임포트합니다.
import org.springframework.web.reactive.function.client.ExchangeStrategies; // ✅ 대용량 페이로드 처리를 위해 codecs 설정을 조정하기 위해 임포트합니다.
import org.springframework.web.reactive.function.client.WebClient; // ✅ RAG 백엔드와 통신할 WebClient를 생성하기 위해 임포트합니다.
import reactor.netty.http.client.HttpClient; // ✅ 타임아웃 등 네트워크 옵션을 세밀하게 제어하기 위해 Netty HttpClient를 임포트합니다.

import java.time.Duration; // ✅ 응답 타임아웃을 지정하기 위해 Duration 클래스를 임포트합니다.

/**
 * RAG 백엔드 호출에 사용할 WebClient 빈을 중앙에서 구성합니다. // ✅ 공용 WebClient 구성을 묶어 중복 생성을 방지함을 설명합니다.
 */
@Configuration // ✅ 이 클래스가 빈 정의를 담는 설정 클래스임을 나타냅니다.
public class RagWebClientConfig {

    /**
     * RAG 백엔드 통신 전용 WebClient 빈을 생성합니다. // ✅ 통합된 네트워크 설정을 통해 재사용성을 높임을 명시합니다.
     */
    @Bean("ragWebClient") // ✅ 생성된 WebClient를 스프링 컨테이너에 등록합니다.
    public WebClient ragWebClient() {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(120)); // ✅ 대용량 응답을 안정적으로 받을 수 있도록 타임아웃을 확장합니다.

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient)) // ✅ Netty 기반 커넥터로 논블로킹 HTTP 호출을 구성합니다.
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(codec -> codec.defaultCodecs().maxInMemorySize(256 * 1024 * 1024)) // ✅ 256MB까지 인메모리 버퍼를 확장해 대용량 본문도 처리합니다.
                        .build())
                .build();
    }

    @Bean("geminiWebClient")
    public WebClient geminiWebClient() {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(120));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(codec -> codec.defaultCodecs().maxInMemorySize(64 * 1024 * 1024))
                        .build())
                .build();
    }    
}
