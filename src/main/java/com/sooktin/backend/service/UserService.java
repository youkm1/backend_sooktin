package com.sooktin.backend.service;

import com.sooktin.backend.domain.CareerCardStorage;
import com.sooktin.backend.domain.User;
import com.sooktin.backend.domain.UserRole;
import com.sooktin.backend.dto.email.EmailCheckResponse;
import com.sooktin.backend.dto.user.NicknameResponse;
import com.sooktin.backend.dto.user.PasswordChangeResponse;
import com.sooktin.backend.dto.user.RegisterRequest;
import com.sooktin.backend.dto.verification.VerficationResponse;
import com.sooktin.backend.global.exception.auth.DuplicateEmailException;
import com.sooktin.backend.global.exception.auth.DuplicateNicknameException;
import com.sooktin.backend.global.exception.auth.PasswordMismatchException;
import com.sooktin.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    @Qualifier("redisTemplate")
    private final RedisTemplate<String, String> redisTemplate;


    @Transactional
    public void registerUser(RegisterRequest request) {

        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new PasswordMismatchException("비밀번호가 일치하지 않습니다.");
        }
        if (userRepository.existsByNickname(request.getNickname())) {
            throw new DuplicateEmailException();
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateNicknameException();
        }

        User newUser = User.builder()
                .email(request.getEmail())
                .nickname(request.getNickname())
                .password(passwordEncoder.encode(request.getPassword()))
                .roles(Collections.singleton(UserRole.USER))
                .build();
        CareerCardStorage storage = CareerCardStorage.builder()
                .user(newUser)
                .build();

        newUser.setCareerCardStorage(storage);

        userRepository.save(newUser);
    }

    @Transactional
    public PasswordChangeResponse changePassword(Long userId, String oldPassword, String newPassword) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("없는 회원입니다!"));

        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            return new PasswordChangeResponse(400, "비밀번호를 제대로 입력해주세요",null);
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        return new PasswordChangeResponse(200, "비밀번호가 변경되었습니다",null);
    }

    @Transactional
    public NicknameResponse changeNickname(String currentNickname, String newNickname) {
        User user = userRepository.findByNickname(currentNickname)
                .orElseThrow(() -> new UsernameNotFoundException("없는 회원입니다"));

        if (user.getNickname().equals(newNickname)){
            throw new IllegalArgumentException("중복 닉네임입니다.");
        }
        if (userRepository.existsByNickname(newNickname)) {
            throw new IllegalArgumentException("다른 닉네임을 입력해주세요");
        }
        user.setNickname(newNickname);
        userRepository.save(user);

        return new NicknameResponse(200,"닉네임이 변경되었습니다.", user);
    }

    @Transactional
    public VerficationResponse verifyEmail(String email, String code) {
        EmailService.VerificationResult result = emailService.verifyCode(email, code);

        return switch (result) {
            case SUCCESS -> {
                log.info("이메일 인증 성공 - 이메일: {}", email);
                yield VerficationResponse.success();
            }
            case CODE_NOT_FOUND_OR_EXPIRED -> {
                log.warn("인증 코드 만료 또는 없음 - 이메일: {}", email);
                yield VerficationResponse.expired();
            }
            case CODE_MISMATCH -> {
                log.warn("인증 코드 불일치 - 이메일: {}", email);
                yield VerficationResponse.invalidCode();
            }
            case TOO_MANY_ATTEMPTS -> {
                log.warn("인증 시도 횟수 초과 - 이메일: {}", email);
                yield VerficationResponse.tooManyAttempts();
            }
            default -> {
                log.error("인증 시스템 오류 - 이메일: {}, 결과: {}", email, result);
                yield VerficationResponse.systemError();
            }
        };
    }

    @Transactional(readOnly = true)
    public EmailCheckResponse checkEmail(String email) {

            if (userRepository.existsByEmail(email)) {
                return EmailCheckResponse.loginRequired();
            }
            return EmailCheckResponse.registerRequired();


    }


    @Transactional(readOnly = true)
    public Optional<User> findUserById(Long id) {
        return userRepository.findById(id);
    }
    
    @Transactional(readOnly = true)
    public Optional<User> findUserByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    @Transactional(readOnly = true)
    public Optional<User> search(String nickname) {
        return userRepository.findByNickname(nickname);
    }

    @Transactional
    public void delete(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));

        // 연관된 데이터 처리 (예: 게시글, 댓글 등)
        // postRepository.deleteByUser(user);
        // commentRepository.deleteByUser(user);

        userRepository.delete(user);

        String refreshtoken = "REFRESH_" + user.getEmail();
        try {
            redisTemplate.delete(refreshtoken);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 연결 실패로 refresh token 삭제를 건너뜁니다. email={}", email, e);
        }
    }


    @Transactional
    public void removeVerificationToken(String email) {
        emailService.deleteVerificationCode(email);
        log.info("인증 코드 삭제 완료 by ADMIN!- 이메일: {}", email);
    }
}
