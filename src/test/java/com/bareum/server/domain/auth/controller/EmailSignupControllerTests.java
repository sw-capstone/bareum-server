package com.bareum.server.domain.auth.controller;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bareum.server.domain.auth.dto.request.EmailSignupRequest;
import com.bareum.server.domain.auth.dto.response.EmailSignupResponse;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.service.EmailSignupService;
import com.bareum.server.domain.term.exception.TermErrorCode;
import com.bareum.server.domain.term.exception.TermException;
import com.bareum.server.global.exception.handler.GlobalExceptionHandler;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class EmailSignupControllerTests {

    private static final String PATH = "/api/v1/auth/signup";
    private static final String TOKEN = "A".repeat(43);
    private static final String BODY = """
            {"email":"user@example.invalid","name":"사용자","password":"${EXAMPLE_PASSWORD}",
             "verificationToken":"%s","terms":[{"termId":1,"version":"1.0","agreed":true}]}
            """.formatted(TOKEN).replace("${EXAMPLE_PASSWORD}", String.join("", "Password", "123", "!"));
    private final EmailSignupService service = mock(EmailSignupService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new EmailSignupController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void signupReturnsOnlyMemberIdWithoutLoggingIn() throws Exception {
        when(service.signup(any())).thenReturn(EmailSignupResponse.of(7L));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(content().json("{\"memberId\":7}", JsonCompareMode.STRICT))
                .andExpect(header().doesNotExist("Set-Cookie"));
        verify(service).signup(any());
    }

    @ParameterizedTest
    @MethodSource("invalidFields")
    void fieldErrorsAreRejectedBeforeServiceAndHideSubmittedValues(String field, Object value, String errorField) throws Exception {
        var mapper = JsonMapper.builder().build();
        var body = mapper.readTree(BODY).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) body).set(field, mapper.valueToTree(value));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("COMMON-0002"))
                .andExpect(jsonPath("$.errors[*].field", hasItem(errorField)))
                .andExpect(jsonPath("$.errors[*].rejectedValue").doesNotExist());
        verifyNoInteractions(service);
    }

    static Stream<Arguments> invalidFields() {
        return Stream.of(
                Arguments.of("email", null, "email"),
                Arguments.of("email", "invalid", "email"),
                Arguments.of("name", "   ", "name"),
                Arguments.of("name", "x".repeat(256), "name"),
                Arguments.of("password", "Short123!", "password"),
                Arguments.of("password", null, "password"),
                Arguments.of("verificationToken", "invalid", "verificationToken"),
                Arguments.of("verificationToken", null, "verificationToken"),
                Arguments.of("terms", List.of(), "terms"),
                Arguments.of("terms", null, "terms"),
                Arguments.of("terms", List.of(java.util.Map.of("termId", -1, "version", "1.0", "agreed", true)), "terms[0].termId"),
                Arguments.of("terms", List.of(java.util.Map.of("termId", 1, "version", "", "agreed", true)), "terms[0].version"),
                Arguments.of("terms", List.of(java.util.Map.of("termId", 1, "version", "1.0")), "terms[0].agreed")
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{"})
    void unreadableBodyUsesCommonError(String body) throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-0003"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @MethodSource("businessErrors")
    void businessErrorsKeepTheirStatusAndCode(RuntimeException exception, int statusCode, String code) throws Exception {
        when(service.signup(any())).thenThrow(exception);
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    static Stream<Arguments> businessErrors() {
        return Stream.concat(
                Stream.of(AuthErrorCode.EMAIL_ALREADY_REGISTERED, AuthErrorCode.EMAIL_VERIFICATION_INVALID,
                                AuthErrorCode.EMAIL_VERIFICATION_TOKEN_EXPIRED, AuthErrorCode.INVALID_SIGNUP_PASSWORD)
                        .map(e -> Arguments.of(new AuthException(e), e.getHttpStatus().value(), e.getCode())),
                Stream.of(TermErrorCode.INVALID_CONSENT_REQUEST, TermErrorCode.REQUIRED_TERM_NOT_AGREED,
                                TermErrorCode.SIGNUP_TERMS_NOT_CONFIGURED)
                        .map(e -> Arguments.of(new TermException(e), e.getHttpStatus().value(), e.getCode())));
    }

    @Test
    void requestStringDoesNotExposePasswordOrProof() {
        String text = new EmailSignupRequest("user@example.invalid", "사용자", "Password123!", TOKEN, List.of()).toString();
        assertFalse(text.contains("Password123!"));
        assertFalse(text.contains(TOKEN));
    }
}
