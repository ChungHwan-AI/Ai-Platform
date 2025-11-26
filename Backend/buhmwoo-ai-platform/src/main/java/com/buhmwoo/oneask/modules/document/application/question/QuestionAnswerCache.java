package com.buhmwoo.oneask.modules.document.application.question;

import com.buhmwoo.oneask.modules.document.api.dto.QuestionAnswerResponseDto; // ✅ 캐시가 보관할 응답 DTO를 사용하기 위해 임포트합니다.
import com.buhmwoo.oneask.modules.document.api.dto.QuestionAnswerSourceDto; // ✅ 출처 리스트를 깊은 복사하기 위해 임포트합니다.
import org.springframework.stereotype.Component; // ✅ 스프링 빈으로 등록하기 위해 Component 애너테이션을 임포트합니다.

import java.time.Duration; // ✅ TTL 계산을 위해 Duration 클래스를 임포트합니다.
import java.time.Instant; // ✅ 캐시 만료 시점을 기록하기 위해 Instant를 임포트합니다.
import java.util.LinkedHashMap; // ✅ LRU 전략 구현을 위해 LinkedHashMap을 임포트합니다.
import java.util.List; // ✅ 출처 리스트 복제 시 활용하기 위해 임포트합니다.
import java.util.Map; // ✅ 맵 기반으로 캐시 엔트리를 관리하기 위해 임포트합니다.
import java.util.Optional; // ✅ 캐시 조회 결과를 Optional로 감싸 호출자가 안전하게 처리하도록 합니다.
import java.util.stream.Collectors; // ✅ 스트림을 사용해 출처 DTO를 복제하기 위해 임포트합니다.

/**
 * 질문-문서 조합에 대한 응답을 간단히 캐싱하는 유틸리티입니다. // ✅ 동일한 질문에 대한 불필요한 RAG 호출을 줄이려는 목적을 설명합니다.
 */
@Component // ✅ 서비스 계층에서 주입받아 사용할 수 있도록 스프링 빈으로 등록합니다.
public class QuestionAnswerCache {

    private static final int MAX_ENTRIES = 100; // ✅ 캐시 메모리 사용량을 제한하기 위한 최대 보관 개수입니다.
    private static final Duration TTL = Duration.ofMinutes(10); // ✅ 응답의 신선도를 보장하기 위한 보관 시간입니다.

    private final Map<String, CacheEntry> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > MAX_ENTRIES; // ✅ 최신 순으로 유지하면서 일정 개수를 초과하면 자동으로 제거합니다.
        }
    };

    /**
     * 캐시에 저장된 응답을 조회합니다. // ✅ TTL을 초과한 경우 즉시 무효화합니다.
     */
    public synchronized Optional<QuestionAnswerResponseDto> get(String docId, String question, BotMode mode) {
        String key = buildKey(docId, question, mode); // ✅ 모드까지 포함해 캐시 키를 구성해 혼선을 방지합니다.
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            return Optional.empty(); // ✅ 캐시에 없는 경우 빈 Optional을 반환합니다.
        }
        if (Instant.now().isAfter(entry.expireAt())) {
            cache.remove(key); // ✅ 만료된 엔트리는 즉시 제거해 메모리를 회수합니다.
            return Optional.empty();
        }
        QuestionAnswerResponseDto cached = entry.response(); // ✅ 저장된 응답을 꺼냅니다.
        QuestionAnswerResponseDto copy = cached.toBuilder()
                .sources(copySources(cached.getSources()))
                .fromCache(true)
                .build(); // ✅ 캐시된 응답임을 표시하는 사본을 만들어 반환합니다.
        return Optional.of(copy);
    }

    /**
     * 새로운 응답을 캐시에 저장합니다. // ✅ 동일 키에 대한 이전 응답은 덮어씁니다.
     */
    public synchronized void put(String docId, String question, BotMode mode, QuestionAnswerResponseDto response) {
        String key = buildKey(docId, question, mode); // ✅ 모드가 다른 응답을 별도로 보관합니다.
        QuestionAnswerResponseDto stored = response.toBuilder()
                .sources(copySources(response.getSources()))
                .fromCache(false)
                .build(); // ✅ 원본 응답 정보를 복제해 캐시 내부 상태를 캡슐화합니다.
        cache.put(key, new CacheEntry(stored, Instant.now().plus(TTL))); // ✅ 응답과 만료 시각을 함께 저장합니다.
    }

    private String buildKey(String docId, String question, BotMode mode) {
        return (docId == null ? "ALL" : docId) + "::" + mode.name() + "::" + question.trim(); // ✅ 봇 모드까지 키에 포함해 캐시 충돌을 막습니다.
    }

    private List<QuestionAnswerSourceDto> copySources(List<QuestionAnswerSourceDto> sources) {
        if (sources == null) {
            return List.of(); // ✅ 출처가 없을 때는 불변 빈 리스트를 반환해 NPE를 방지합니다.
        }
        return sources.stream()
                .map(source -> QuestionAnswerSourceDto.builder()
                        .reference(source.getReference())
                        .source(source.getSource())
                        .page(source.getPage())
                        .preview(source.getPreview())
                        .build())
                .collect(Collectors.toUnmodifiableList()); // ✅ 불변 리스트로 반환해 외부에서 변경하지 못하도록 보호합니다.
    }

    /**
     * 캐시 내부에 저장할 응답과 만료 시각을 묶은 불변 레코드입니다. // ✅ Map 값 객체의 용도를 명확히 합니다.
     */
    private record CacheEntry(QuestionAnswerResponseDto response, Instant expireAt) {
    }
}