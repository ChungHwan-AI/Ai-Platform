package com.buhmwoo.oneask.modules.document.api.dto;

import lombok.AllArgsConstructor; // ✅ DTO 생성을 간결하게 하기 위해 Lombok 애너테이션을 임포트합니다.
import lombok.Builder; // ✅ 빌더 패턴을 사용해 선택적 필드를 유연하게 조합합니다.
import lombok.Data; // ✅ 게터/세터 등을 자동 생성해 보일러플레이트 코드를 줄입니다.
import lombok.NoArgsConstructor; // ✅ 역직렬화 지원을 위해 기본 생성자를 제공합니다.

/**
 * 검색 단계에서 확보한 출처 정보를 앱 응답에 포함하기 위한 DTO입니다. // ✅ 앱에서 출처 리스트를 그대로 표현할 수 있음을 설명합니다.
 */
@Data // ✅ 필드 접근자와 변경자를 자동으로 생성합니다.
@Builder // ✅ 가독성 높은 객체 생성을 지원합니다.
@NoArgsConstructor // ✅ JSON 역직렬화 시 기본 생성자가 필요하므로 제공합니다.
@AllArgsConstructor // ✅ 모든 필드를 한번에 채우는 생성자를 자동 생성합니다.
public class QuestionAnswerSourceDto {

    private String reference; // ✅ 답변 본문에서 사용할 [청크 N] 형태의 인용 라벨입니다.
    private String source; // ✅ 파일명 또는 문서 식별자 등 사용자에게 노출할 출처입니다.
    private Integer page; // ✅ 페이지/슬라이드 번호가 있을 경우 위치 정보를 전달합니다.
    private String preview; // ✅ UI에서 간단히 보여줄 수 있도록 정리한 청크 요약문입니다.
}