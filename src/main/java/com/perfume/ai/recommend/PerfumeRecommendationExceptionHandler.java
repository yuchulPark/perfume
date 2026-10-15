package com.perfume.ai.recommend;

import com.perfume.ai.search.SemanticSearchFailure;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice(assignableTypes = PerfumeRecommendationController.class)
public class PerfumeRecommendationExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(RecommendationFailure.class) public ProblemDetail failure(RecommendationFailure failure) { return problem(failure.code()); }
    @ExceptionHandler(SemanticSearchFailure.class) public ProblemDetail invalidRequest(SemanticSearchFailure failure) { return problem(RecommendationFailure.Code.INVALID_REQUEST); }
    @ExceptionHandler(Exception.class) public ProblemDetail unexpected(Exception failure) { return problem(RecommendationFailure.Code.GENERATION_FAILED); }
    @Override protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var detail = problem(status.is4xxClientError() ? RecommendationFailure.Code.INVALID_REQUEST : RecommendationFailure.Code.GENERATION_FAILED);
        detail.setStatus(status.value()); detail.setTitle(HttpStatus.valueOf(status.value()).getReasonPhrase());
        return super.handleExceptionInternal(exception, detail, headers, status, request);
    }
    private static ProblemDetail problem(RecommendationFailure.Code code) {
        var detail = ProblemDetail.forStatusAndDetail(code.status(), code.detail()); detail.setProperty("code", code.name()); return detail;
    }
}
