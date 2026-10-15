package com.perfume.ai.search;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice(assignableTypes = PerfumeSemanticSearchController.class)
public class PerfumeSemanticSearchExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(SemanticSearchFailure.class)
    public ProblemDetail failure(SemanticSearchFailure failure) { return problem(failure.code()); }
    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception failure) { return problem(SemanticSearchFailure.Code.SEARCH_FAILED); }
    @Override protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var detail = status.is4xxClientError() ? problem(SemanticSearchFailure.Code.INVALID_REQUEST)
                : problem(SemanticSearchFailure.Code.SEARCH_FAILED);
        detail.setStatus(status.value()); detail.setTitle(HttpStatus.valueOf(status.value()).getReasonPhrase());
        return super.handleExceptionInternal(exception, detail, headers, status, request);
    }
    private static ProblemDetail problem(SemanticSearchFailure.Code code) {
        var detail = ProblemDetail.forStatusAndDetail(code.status(), code.detail()); detail.setProperty("code", code.name()); return detail;
    }
}
