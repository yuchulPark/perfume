package com.perfume.api.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import com.perfume.api.service.PerfumeNotFoundException;

@RestControllerAdvice(basePackageClasses = {BrandController.class, PerfumeController.class})
public class CatalogExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(PerfumeNotFoundException.class)
    public ProblemDetail notFound(PerfumeNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }
}
