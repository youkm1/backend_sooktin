package com.sooktin.backend.dto.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginRequest {
    @NotBlank(message = "이메일을 입력해주세요")
    @Email(message = "이메일 형식이 맞지 않아요")
    private String email;

    @NotBlank(message = "비밀번호을 입력해주세요")
    @Size(min = 8, max = 20, message = "비밀번호는 8자 이상 20자 이하여야 합니다")
    private String password;
}
