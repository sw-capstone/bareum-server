package com.bareum.server.domain.auth.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bareum.server.domain.auth.dto.request.EmailVerificationConfirmRequest;
import com.bareum.server.domain.auth.dto.response.EmailVerificationConfirmResponse;
import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.service.EmailVerificationConfirmService;
import com.bareum.server.global.exception.handler.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class EmailVerificationConfirmControllerTests {

    private static final String PATH = "/api/v1/auth/email-verification/code";
    private final EmailVerificationConfirmService service = mock(EmailVerificationConfirmService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new EmailVerificationConfirmController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private String body(String code) {
        return "{\"email\":\"user@example.com\",\"purpose\":\"SIGNUP\",\"code\":" + code + "}";
    }

    @Test
    void successReturnsProofOnlyAndDisablesCaching() throws Exception {
        when(service.confirm(new EmailVerificationConfirmRequest(
                "user@example.com", VerificationPurpose.SIGNUP, "012345")))
                .thenReturn(new EmailVerificationConfirmResponse("test-proof", 600));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("\"012345\"")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"verificationToken\":\"test-proof\",\"expiresIn\":600}", JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"\"", "\"12345\"", "\"1234567\"", "\"abcdef\"", "\" 12345\""})
    void invalidCodeIsNotPassedToServiceOrEchoed(String code) throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(code)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-0002"))
                .andExpect(jsonPath("$.errors[*].rejectedValue").doesNotExist());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"email\":\"invalid\",\"purpose\":\"SIGNUP\",\"code\":\"012345\"}",
            "{\"email\":\"user@example.com\",\"code\":\"012345\"}"
    })
    void missingOrInvalidFieldsAreRejected(String body) throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-0002"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @EnumSource(value = AuthErrorCode.class, names = {
            "EMAIL_VERIFICATION_NOT_CONFIGURED", "EMAIL_VERIFICATION_INVALID",
            "EMAIL_VERIFICATION_EXPIRED", "EMAIL_VERIFICATION_CODE_MISMATCH",
            "EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED", "EMAIL_VERIFICATION_ALREADY_VERIFIED"
    })
    void businessErrorsUseCommonProblemFormat(AuthErrorCode error) throws Exception {
        when(service.confirm(any())).thenThrow(new AuthException(error));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("\"012345\"")))
                .andExpect(status().is(error.getHttpStatus().value()))
                .andExpect(jsonPath("$.code").value(error.getCode()))
                .andExpect(jsonPath("$.verificationToken").doesNotExist());
    }

    @Test
    void dtoStringRepresentationsDoNotExposeCredentials() {
        assertFalse(new EmailVerificationConfirmRequest(
                "user@example.com", VerificationPurpose.SIGNUP, "012345").toString().contains("012345"));
        assertFalse(new EmailVerificationConfirmResponse("test-proof", 600).toString().contains("test-proof"));
    }
}
