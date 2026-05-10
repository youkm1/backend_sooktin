package com.sooktin.backend.global.exception;

import com.sooktin.backend.dto.ResponseDto;
import com.sooktin.backend.global.exception.auth.SearchNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.naming.AuthenticationException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hibernate.query.sqm.tree.SqmNode.log;

@RestControllerAdvice
public class ResponseExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ResponseDto<Object>> handleMethodArgumentNotValidException(MethodArgumentNotValidException ex) {
        Map<String,List<String>> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error -> {
            errors.computeIfAbsent(error.getField(), key -> new ArrayList<>()).add(error.getDefaultMessage());
        });
        ResponseDto<Object> response = new ResponseDto<>(
                400,
                "잘못된 접근입니다",
                errors
        );
        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ResponseDto<Object>> handleIllegalArgumentException(IllegalArgumentException ex) {
        ResponseDto<Object> response = new ResponseDto<>(
                400,
                ex.getMessage() != null ? ex.getMessage() : "잘못된 요청입니다.",
                null
        );

        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ResponseDto<Object>> handleMissingServletRequestParameterException(MissingServletRequestParameterException ex) {
        ResponseDto<Object> response = new ResponseDto<>(
                400,
                "검색어를 입력해주세요.",
                null
        );

        return ResponseEntity.badRequest().body(response);
    }
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ResponseDto<Object>> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, List<String>> errors = new HashMap<>();

        ex.getConstraintViolations().forEach(violation -> {
            String fieldName = violation.getPropertyPath().toString();
            String errorMessage = violation.getMessage();
            errors.computeIfAbsent(fieldName, key -> new ArrayList<>()).add(errorMessage);
        });

        ResponseDto<Object> response = new ResponseDto<>(
                400,
                "잘못된 접근입니다.",
                errors
        );

        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler
    public ResponseEntity<ResponseDto<Object>> handleAuthenticationException(AuthenticationException ex) {
        ResponseDto<Object> response = new ResponseDto<>(
                401,
                ex.getMessage(),
                null
        );

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    @ExceptionHandler
    public ResponseEntity<ResponseDto<Object>> handleUsernameNotFoundException(UsernameNotFoundException ex) {
        ResponseDto<Object> response = new ResponseDto<>(
                401,
                "유저가 없습니다.",
                null
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }


    @ExceptionHandler
    public ResponseEntity<ResponseDto<Object>> handleBadCredentialsException(BadCredentialsException ex) {
        ResponseDto<Object> response = new ResponseDto<>(
                401,
                "이메일을 확인해주세요",
                null
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }


    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ResponseDto<Object>> handleRuntimeException(RuntimeException ex) {
        ResponseDto<Object> response = new ResponseDto<>(
                500,
                "내부 서버 오류입니다. 다시 접속해주세요.",
                null
        );
        log.error("RuntimeException occurred: {}", ex.getMessage(), ex);
        return ResponseEntity.internalServerError().body(response);
    }

    @ExceptionHandler
    public ResponseEntity<ResponseDto<Object>> handleSearchNotFoundException(SearchNotFoundException ex) {
        ResponseDto<Object> response = new ResponseDto<>(
          404,
          ex.getMessage(),
          null
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }
}
