package com.bareum.server.domain.auth.controller;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bareum.server.domain.auth.dto.request.EmailVerificationRequest;
import com.bareum.server.domain.auth.dto.response.EmailVerificationSendResponse;
import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.service.EmailVerificationSendService;
import com.bareum.server.global.exception.handler.GlobalExceptionHandler;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class EmailVerificationSendControllerTests {

    private static final String PATH = "/api/v1/auth/email-verification";
    private static final String EMAIL = "user@example.com";

    private final EmailVerificationSendService service = mock(EmailVerificationSendService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new EmailVerificationSendController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @ParameterizedTest
    @EnumSource(VerificationPurpose.class)
    void validRequestReturnsOnlyRemainingLifetime(VerificationPurpose purpose) throws Exception {
        EmailVerificationRequest request = new EmailVerificationRequest(EMAIL, purpose);
        when(service.send(request)).thenReturn(EmailVerificationSendResponse.of(298));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"purpose\":\"" + purpose.name() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"expiresIn\":298}", JsonCompareMode.STRICT));

        verify(service).send(request);
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void invalidFieldsAreRejectedBeforeCallingService(String body, String field) throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("COMMON-0002"))
                .andExpect(jsonPath("$.instance").value(PATH))
                .andExpect(jsonPath("$.errors[*].field", hasItem(field)))
                .andExpect(jsonPath("$.errors[*].rejectedValue").doesNotExist());

        verifyNoInteractions(service);
    }

    private static Stream<Arguments> invalidRequests() {
        String longEmail = "a".repeat(64) + "@" + "b".repeat(63) + "."
                + "c".repeat(63) + "." + "d".repeat(63);
        return Stream.of(
                Arguments.of("{\"purpose\":\"SIGNUP\"}", "email"),
                Arguments.of("{\"email\":null,\"purpose\":\"SIGNUP\"}", "email"),
                Arguments.of("{\"email\":\"\",\"purpose\":\"SIGNUP\"}", "email"),
                Arguments.of("{\"email\":\"   \",\"purpose\":\"SIGNUP\"}", "email"),
                Arguments.of("{\"email\":\"invalid-email\",\"purpose\":\"SIGNUP\"}", "email"),
                Arguments.of("{\"email\":\"" + longEmail + "\",\"purpose\":\"SIGNUP\"}", "email"),
                Arguments.of("{\"email\":\"user@example.com\"}", "purpose"),
                Arguments.of("{\"email\":\"user@example.com\",\"purpose\":null}", "purpose")
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "null", "{",
            "{\"email\":\"user@example.com\",\"purpose\":\"UNKNOWN\"}"
    })
    void unreadableBodyUsesCommonProblemDetail(String body) throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("COMMON-0003"))
                .andExpect(jsonPath("$.errors").doesNotExist());

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @EnumSource(AuthErrorCode.class)
    void businessErrorsKeepExistingStatusAndCode(AuthErrorCode error) throws Exception {
        EmailVerificationRequest request = new EmailVerificationRequest(EMAIL, VerificationPurpose.SIGNUP);
        when(service.send(request)).thenThrow(new AuthException(error));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"purpose\":\"SIGNUP\"}"))
                .andExpect(status().is(error.getHttpStatus().value()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(error.getHttpStatus().value()))
                .andExpect(jsonPath("$.code").value(error.getCode()))
                .andExpect(jsonPath("$.detail").value(error.getMessage()))
                .andExpect(jsonPath("$.instance").value(PATH))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void unsupportedMethodDoesNotCallService() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("COMMON-0006"));
        verifyNoInteractions(service);
    }

    @Test
    void unsupportedMediaTypeDoesNotCallService() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.TEXT_PLAIN).content("request"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("COMMON-0007"));
        verifyNoInteractions(service);
    }
}
