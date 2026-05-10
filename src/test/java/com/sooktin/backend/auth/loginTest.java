package com.sooktin.backend.auth;


import com.sooktin.backend.dto.user.LoginRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
public class loginTest {

    private Validator validator;

    @BeforeEach
    void setUp(){
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    @DisplayName("로그인리퀘스트 validation피하나요")
    void testValidLoginReqDto(){
        LoginRequest dto = new LoginRequest();
        dto.setEmail("foo@ex.com");
        dto.setPassword("password1");

        Set<ConstraintViolation<LoginRequest>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty());
    }

    @Test
    @DisplayName("로그인리퀘스트 invalid email")
    void testInvalidEmailLoginReqDto(){
        LoginRequest dto = new LoginRequest();
        dto.setEmail("invalid");
        dto.setPassword("password1");

        Set<ConstraintViolation<LoginRequest>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        assertEquals("이메일 형식이 맞지 않아요",violations.iterator().next().getMessage());
    }
}
