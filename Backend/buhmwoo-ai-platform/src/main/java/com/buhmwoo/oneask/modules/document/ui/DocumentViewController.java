package com.buhmwoo.oneask.modules.document.ui;

import com.buhmwoo.oneask.common.dto.ApiResponseDto;
import com.buhmwoo.oneask.common.dto.PageResponse;
import com.buhmwoo.oneask.modules.document.api.dto.DocumentListItemResponseDto;
import com.buhmwoo.oneask.modules.document.api.service.DocumentService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * 문서 관련 API를 호출해 스프링 부트 자체 UI를 제공하는 MVC 컨트롤러입니다. // ✅ 뷰 전용 컨트롤러의 역할을 설명합니다.
 */
@Controller // ✅ 해당 클래스가 Thymeleaf 템플릿을 렌더링하기 위한 MVC 컨트롤러임을 명시합니다.
@RequestMapping("/documents") // ✅ 문서 화면 접근 시 사용할 기본 경로를 고정합니다.
public class DocumentViewController {

    private final DocumentService documentService; // ✅ 기존 문서 서비스 빈을 주입해 동일한 비즈니스 로직을 재사용합니다.

    public DocumentViewController(DocumentService documentService) { // ✅ 생성자 주입을 통해 테스트와 유지보수가 쉬운 구조로 만듭니다.
        this.documentService = documentService;
    }

    @ModelAttribute("defaultPageSize")
    public int defaultPageSize() { // ✅ 템플릿에서 기본 페이지 크기 값을 참조할 수 있도록 모델에 노출합니다.
        return 10;
    }

    @GetMapping // ✅ 문서 목록 화면 진입 시 호출되는 GET 핸들러입니다.
    public String showDocumentPage(
            @RequestParam(value = "fileName", required = false) String fileName, // ✅ 파일명 검색어를 전달받습니다.
            @RequestParam(value = "uploadedBy", required = false) String uploadedBy, // ✅ 업로더 검색어를 전달받습니다.
            @RequestParam(value = "uploadedFrom", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate uploadedFrom, // ✅ 검색 시작일을 로컬 날짜로 파싱합니다.
            @RequestParam(value = "uploadedTo", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate uploadedTo, // ✅ 검색 종료일을 로컬 날짜로 파싱합니다.
            @RequestParam(value = "page", defaultValue = "0") int page, // ✅ 요청된 페이지 번호를 받아옵니다.
            @RequestParam(value = "size", defaultValue = "10") int size, // ✅ 페이지 크기를 받아옵니다.
            Model model // ✅ 뷰에 데이터를 전달하기 위해 스프링 모델을 사용합니다.
    ) {
        int safePage = Math.max(page, 0); // ✅ 음수 페이지 요청을 방지하기 위해 0 미만 값을 보정합니다.
        int safeSize = Math.max(size, 1); // ✅ 최소 1건 이상 표시되도록 페이지 크기를 보정합니다.

        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "uploadedAt")); // ✅ 업로드 최신순으로 페이지 정보를 구성합니다.
        PageResponse<DocumentListItemResponseDto> pageResponse = documentService.getDocumentPage(fileName, uploadedBy, uploadedFrom, uploadedTo, pageable); // ✅ 기존 서비스 레이어를 호출해 목록 데이터를 조회합니다.

        model.addAttribute("page", pageResponse); // ✅ 페이징 전체 정보를 템플릿에 전달합니다.
        model.addAttribute("documents", pageResponse.getContent()); // ✅ 문서 목록만 별도로 꺼내어 반복 렌더링에 사용합니다.
        model.addAttribute("searchParams", buildSearchParams(fileName, uploadedBy, uploadedFrom, uploadedTo)); // ✅ 검색 값이 유지되도록 파라미터를 모델에 전달합니다.
        model.addAttribute("pageNumbers", buildPageNumbers(pageResponse.getTotalPages())); // ✅ 페이지 네비게이션 렌더링을 위한 번호 목록을 제공합니다.
        return "documents"; // ✅ documents.html 템플릿을 렌더링하도록 반환합니다.
    }

    @PostMapping("/upload") // ✅ 문서 업로드 폼 제출을 처리하는 POST 핸들러입니다.
    public String uploadDocument(
            @RequestParam("file") MultipartFile file, // ✅ 업로드 대상 파일을 Multipart 형태로 전달받습니다.
            @RequestParam(value = "description", required = false) String description, // ✅ 파일 설명을 선택적으로 수집합니다.
            @RequestParam(value = "uploadedBy", defaultValue = "system") String uploadedBy, // ✅ 업로더 이름을 지정하거나 기본값을 사용합니다.
            RedirectAttributes redirectAttributes // ✅ 결과 메시지를 리다이렉트 이후에도 보여주기 위해 플래시 속성을 활용합니다.
    ) {
        if (file == null || file.isEmpty()) { // ✅ 파일이 비어있을 때 사용자에게 에러를 안내합니다.
            redirectAttributes.addFlashAttribute("alertMessage", "업로드할 파일을 선택해주세요.");
            redirectAttributes.addFlashAttribute("alertType", "danger");
            return "redirect:/documents";
        }

        ApiResponseDto<Map<String, Object>> response = documentService.uploadFile(file, description, uploadedBy); // ✅ 기존 업로드 로직을 호출해 파일 저장 및 인덱싱을 수행합니다.
        redirectAttributes.addFlashAttribute("alertMessage", response.getMessage()); // ✅ 처리 결과 메시지를 알림으로 전달합니다.
        redirectAttributes.addFlashAttribute("alertType", response.isSuccess() ? "success" : "danger"); // ✅ 성공 여부에 따라 스타일을 구분합니다.
        redirectAttributes.addFlashAttribute("uploadResult", response.getData()); // ✅ 프리뷰 데이터를 후속 화면에서 활용할 수 있도록 전달합니다.
        return "redirect:/documents"; // ✅ PRG 패턴으로 새로고침 시 중복 제출을 방지합니다.
    }

    @PostMapping("/{uuid}/delete") // ✅ 각 문서 행에서 삭제 버튼을 처리하는 POST 핸들러입니다.
    public String deleteDocument(
            @PathVariable("uuid") String uuid, // ✅ 삭제 대상 문서를 식별하는 UUID를 경로 변수로 받습니다.
            RedirectAttributes redirectAttributes // ✅ 삭제 결과를 화면에 알리기 위해 플래시 속성을 사용합니다.
    ) {
        ApiResponseDto<Map<String, Object>> response = documentService.deleteDocument(uuid); // ✅ 문서 삭제 비즈니스 로직을 호출합니다.
        redirectAttributes.addFlashAttribute("alertMessage", response.getMessage()); // ✅ 결과 메시지를 사용자에게 안내합니다.
        redirectAttributes.addFlashAttribute("alertType", response.isSuccess() ? "success" : "danger"); // ✅ 성공 여부에 맞춰 알림 색상을 조정합니다.
        return "redirect:/documents"; // ✅ 다시 목록 화면으로 이동합니다.
    }

    @PostMapping("/{uuid}/ask") // ✅ 특정 문서 범위에서 질의할 때 사용하는 POST 핸들러입니다.
    public String askDocument(
            @PathVariable("uuid") String uuid, // ✅ 질문 대상 문서 UUID를 경로 변수로 받습니다.
            @RequestParam("question") String question, // ✅ 질의 내용 텍스트를 요청 파라미터로 받습니다.
            RedirectAttributes redirectAttributes // ✅ 질문 결과를 리다이렉트 이후에도 표시합니다.
    ) {
        ApiResponseDto<String> response = documentService.ask(uuid, question); // ✅ FastAPI 백엔드를 호출해 질의 응답을 받아옵니다.
        redirectAttributes.addFlashAttribute("alertMessage", response.getMessage()); // ✅ 질의 처리 메시지를 플래시로 보냅니다.
        redirectAttributes.addFlashAttribute("alertType", response.isSuccess() ? "info" : "danger"); // ✅ 질의 결과는 정보성 알림으로 표현합니다.
        redirectAttributes.addFlashAttribute("askResult", response.getData()); // ✅ 응답 본문을 화면에서 그대로 보여줄 수 있도록 전달합니다.
        redirectAttributes.addFlashAttribute("askTarget", uuid); // ✅ 어느 문서에 대한 질문인지 식별해 템플릿에서 강조합니다.
        return "redirect:/documents"; // ✅ 결과 확인을 위해 목록 화면으로 리다이렉트합니다.
    }

    @PostMapping("/ask") // ✅ 전체 문서를 대상으로 하는 자유 질의 핸들러입니다.
    public String askAll(
            @RequestParam("question") String question, // ✅ 전체 질의 텍스트를 전달받습니다.
            RedirectAttributes redirectAttributes // ✅ 응답 메시지를 유지하기 위해 플래시 속성을 사용합니다.
    ) {
        ApiResponseDto<String> response = documentService.ask(null, question); // ✅ UUID 없이 호출해 전체 문서를 대상으로 FastAPI에 질의합니다.
        redirectAttributes.addFlashAttribute("alertMessage", response.getMessage()); // ✅ FastAPI 응답 메시지를 사용자에게 보여줍니다.
        redirectAttributes.addFlashAttribute("alertType", response.isSuccess() ? "info" : "danger"); // ✅ 성공 시 정보 알림, 실패 시 경고 알림을 표시합니다.
        redirectAttributes.addFlashAttribute("askResult", response.getData()); // ✅ 질의 답변 텍스트를 화면에서 출력합니다.
        redirectAttributes.addFlashAttribute("askTarget", "ALL"); // ✅ 전체 질의임을 표시해 템플릿에서 구분합니다.
        return "redirect:/documents"; // ✅ Post/Redirect/Get 흐름을 유지합니다.
    }

    private Map<String, Object> buildSearchParams(String fileName, String uploadedBy, LocalDate uploadedFrom, LocalDate uploadedTo) { // ✅ 검색 입력값을 템플릿으로 전달하기 위한 헬퍼 메서드입니다.
        Map<String, Object> params = new HashMap<>(); // ✅ 수정 가능한 맵을 생성해 모델 속성으로 활용합니다.
        if (StringUtils.hasText(fileName)) {
            params.put("fileName", fileName); // ✅ 파일명 검색어가 있을 때만 맵에 포함합니다.
        }
        if (StringUtils.hasText(uploadedBy)) {
            params.put("uploadedBy", uploadedBy); // ✅ 업로더 검색어가 있을 때만 맵에 포함합니다.
        }
        if (uploadedFrom != null) {
            params.put("uploadedFrom", uploadedFrom); // ✅ 날짜 검색 조건을 그대로 노출합니다.
        }
        if (uploadedTo != null) {
            params.put("uploadedTo", uploadedTo); // ✅ 종료일 검색 조건을 그대로 노출합니다.
        }
        return params; // ✅ 템플릿에서 입력 값을 유지하는 데 활용합니다.
    }

    private List<Integer> buildPageNumbers(int totalPages) { // ✅ 페이지 네비게이션을 구성하기 위한 헬퍼 메서드입니다.
        if (totalPages <= 0) { // ✅ 페이지가 없을 때는 빈 목록을 반환합니다.
            return List.of();
        }
        return IntStream.range(0, totalPages).boxed().toList(); // ✅ 0부터 (totalPages-1)까지의 번호 목록을 생성합니다.
    }
}