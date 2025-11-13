package com.buhmwoo.oneask.modules.document.api.dto;

import lombok.AllArgsConstructor; // ✅ DTO 생성을 단순화하기 위해 Lombok 애너테이션을 임포트합니다.
import lombok.Builder; // ✅ 가독성 높은 객체 생성을 위해 빌더 패턴을 활용합니다.
import lombok.Data; // ✅ 게터/세터/equals 등을 자동 생성하기 위해 임포트합니다.
import lombok.NoArgsConstructor; // ✅ 직렬화 프레임워크 호환을 위해 기본 생성자를 제공합니다.

import java.util.List; // ✅ 복수의 출처 정보를 포함하기 위해 List 자료구조를 임포트합니다.

/**
 * GPT 응답을 앱 클라이언트에 전달하기 위한 표준 DTO입니다. // ✅ 제목·본문·출처·캐시 여부까지 한 번에 제공하도록 구성합니다.
 */
@Data // ✅ 표준 메서드를 자동 생성해 코드를 간결하게 유지합니다.
@Builder(toBuilder = true) // ✅ 캐시 복제 시 활용할 수 있도록 toBuilder 옵션을 활성화합니다.
@NoArgsConstructor // ✅ 역직렬화 시 기본 생성자가 필요하므로 제공합니다.
@AllArgsConstructor // ✅ 모든 필드를 채우는 생성자를 자동 생성합니다.
public class QuestionAnswerResponseDto {

    private String title; // ✅ 앱에서 카드 형태로 보여줄 수 있도록 응답 제목을 제공합니다.
    private String answer; // ✅ GPT가 생성한 최종 답변 본문입니다.
    private List<QuestionAnswerSourceDto> sources; // ✅ 답변 근거를 추적할 수 있도록 검색된 출처 목록을 포함합니다.
    private boolean fromCache; // ✅ 동일 질의가 캐시를 통해 처리되었는지 여부를 알려줍니다.
}