package com.bareum.server.domain.auth.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.bareum.server.domain.auth.dto.response.EmailVerificationDeliveryResponse;
import com.bareum.server.domain.auth.service.EmailVerificationDeliveryService;
import com.bareum.server.global.exception.handler.GlobalExceptionHandler;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class EmailVerificationDeliveryControllerTests {
    private final EmailVerificationDeliveryService service = mock(EmailVerificationDeliveryService.class);
    private MockMvc mvc;
    private final UUID id = UUID.randomUUID();
    private static final String PATH = "/api/v1/auth/email-verification/deliveries/";
    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new EmailVerificationDeliveryController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @Test
    void statusReturnsNoSensitiveFieldsAndCannotBeCached() throws Exception {
        when(service.status(id)).thenReturn(Optional.of(new EmailVerificationDeliveryResponse(id,"SENT",299)));
        mvc.perform(get(PATH+id)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.status").value("SENT")).andExpect(jsonPath("$.expiresIn").value(299))
                .andExpect(jsonPath("$.email").doesNotExist()).andExpect(jsonPath("$.code").doesNotExist());
    }
    @Test
    void unknownIdReturnsEmptyNotFoundWithoutCaching() throws Exception {
        when(service.status(id)).thenReturn(Optional.empty());
        mvc.perform(get(PATH+id)).andExpect(status().isNotFound()).andExpect(content().string(""))
                .andExpect(header().string("Cache-Control","no-store"));
    }
    @Test
    void invalidUuidUsesCommonProblemDetail() throws Exception {
        mvc.perform(get(PATH+"invalid")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-0005"));
        verifyNoInteractions(service);
    }
}
