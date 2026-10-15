package com.example.demo.controller;

import com.example.demo.dto.LoginResponse;
import com.example.demo.entity.User;
import com.example.demo.exception.InvalidCredentialsException;
import com.example.demo.exception.UsernameAlreadyExistsException;
import com.example.demo.security.JwtAuthenticationFilter;
import com.example.demo.security.JwtUtil;
import com.example.demo.security.LoginRateLimiter;
import com.example.demo.exception.LoginRateLimitExceededException;
import com.example.demo.service.AuthService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static com.example.demo.TestAuthRequests.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @MockitoBean private AuthService auth;
    @MockitoBean private LoginRateLimiter limiter;
    @MockitoBean private JwtAuthenticationFilter filter;
    @MockitoBean private JwtUtil jwt;
    @MockitoBean private UserDetailsService details;

    @ParameterizedTest
    @MethodSource("com.example.demo.TestAuthRequests#invalidApiRequests")
    void invalidCredentialsAre400BeforeServiceAndContainOnlyStaticFieldMessages(
            String endpoint, String field, Map<String, Object> body) throws Exception {
        String response = mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors." + field).isString())
                .andReturn().getResponse().getContentAsString();
        assertFalse(response.contains(PASSWORD));
        assertFalse(response.contains("rawPassword"));
        assertFalse(response.contains("token"));
        verifyNoInteractions(auth, jwt, limiter);
    }

    @Test
    void successKeeps200AndReturnsOnlyTheRegisterDtoOrLoginToken() throws Exception {
        when(auth.register(any())).thenReturn(new User(1L, "alice", "stored-hash", "ROLE_USER"));
        var registered = json.readTree(mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(credentials("alice", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(3, registered.size());
        assertFalse(registered.has("password"));
        assertEquals("alice", registered.path("username").asText());
        when(auth.login(any())).thenReturn(new LoginResponse("test-token"));
        mvc.perform(post("/auth/login").requestAttr("com.example.demo.security.LoginClientAddressFilter.source", "198.51.100.1").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(credentials("alice", PASSWORD))))
                .andExpect(status().isOk()).andExpect(content().json("{\"token\":\"test-token\"}"));
    }

    @Test
    void dedicatedExceptionsProduce409Or401WithStableCodes() throws Exception {
        when(auth.register(any())).thenThrow(new UsernameAlreadyExistsException());
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(credentials("alice", PASSWORD))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USERNAME_ALREADY_EXISTS"));
        when(auth.login(any())).thenThrow(new InvalidCredentialsException());
        mvc.perform(post("/auth/login").requestAttr("com.example.demo.security.LoginClientAddressFilter.source", "198.51.100.1").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(credentials("alice", PASSWORD))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/auth/register", "/auth/login"})
    void unexpectedAuthFailuresHideDatabaseDetailsAndCredentials(String endpoint) throws Exception {
        RuntimeException failure = new DataIntegrityViolationException("SQL BCrypt hash=" + PASSWORD + " token signing-secret");
        if (endpoint.endsWith("register")) when(auth.register(any())).thenThrow(failure);
        else when(auth.login(any())).thenThrow(failure);
        mvc.perform(post(endpoint).requestAttr("com.example.demo.security.LoginClientAddressFilter.source", "198.51.100.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(credentials("alice", PASSWORD))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("SYSTEM_ERROR"))
                .andExpect(jsonPath("$.detail").value("予期しないエラーが発生しました"));
    }

    @Test
    void rateLimitIs429WithSafeProblemAndRetryAfterBeforeAuthService() throws Exception {
        doThrow(new LoginRateLimitExceededException(17)).when(limiter).acquire(anyString(), eq("alice"));
        mvc.perform(post("/auth/login").requestAttr("com.example.demo.security.LoginClientAddressFilter.source", "198.51.100.1").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(credentials("alice", PASSWORD))))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "17"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"))
                .andExpect(jsonPath("$.title").value("Login Rate Limited"))
                .andExpect(jsonPath("$.detail").value(LoginRateLimitExceededException.DETAIL))
                .andExpect(jsonPath("$.instance").value("/auth/login"));
        verifyNoInteractions(auth, jwt);
    }

    @Test
    void missingCaptureReturnsSafe503WithoutAuthenticationOrQuotaAllocation() throws Exception {
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(credentials("alice", PASSWORD))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("LOGIN_SOURCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.instance").value("/auth/login"));
        verifyNoInteractions(auth, limiter, jwt);
    }

    @Test
    void registrationDoesNotConsultLoginLimiter() throws Exception {
        when(auth.register(any())).thenReturn(new User(1L, "alice", "stored-hash", "ROLE_USER"));
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(credentials("alice", PASSWORD)))).andExpect(status().isOk());
        verifyNoInteractions(limiter);
    }
}
