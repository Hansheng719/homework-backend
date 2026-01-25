package com.example.demo.controller;

import com.example.demo.ConfigureMockConsumerBeanApplication;
import com.example.demo.ConfigureMockDatasourceBeanApplication;
import com.example.demo.exception.*;
import com.example.demo.facade.BalanceFacade;
import com.example.demo.facade.TransferFacade;
import com.example.demo.facade.dto.CreateTransferRequest;
import com.example.demo.facade.dto.CreateUserRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {TransferController.class, UserController.class})
@AutoConfigureMockMvc
@DisplayName("GlobalExceptionHandler Tests")
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BalanceFacade balanceFacade;

    @MockitoBean
    private TransferFacade transferFacade;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("handleUserNotFoundException - Returns 404 with error response")
    void handleUserNotFoundException_Returns404WithErrorResponse() throws Exception {
        // Given - Mock facade to throw UserNotFoundException
        String userId = "nonexistent_user";
        when(balanceFacade.getBalance(anyString()))
            .thenThrow(new UserNotFoundException(userId));

        // When & Then
        mockMvc.perform(get("/users/{userId}/balance", userId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.error").value("User Not Found"))
            .andExpect(jsonPath("$.message").value(containsString(userId)))
            .andExpect(jsonPath("$.timestamp").exists())
            .andExpect(jsonPath("$.path").value("/users/" + userId + "/balance"));
    }

    @Test
    @DisplayName("handleUserAlreadyExistsException - Returns 409 with error response")
    void handleUserAlreadyExistsException_Returns409WithErrorResponse() throws Exception {
        // Given - Mock facade to throw UserAlreadyExistsException
        String userId = "existing_user";
        CreateUserRequest request = CreateUserRequest.builder()
            .userId(userId)
            .initialBalance(new BigDecimal("100.00"))
            .build();

        when(balanceFacade.createUser(any(CreateUserRequest.class)))
            .thenThrow(new UserAlreadyExistsException(userId));

        // When & Then
        mockMvc.perform(post("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.error").value("User Already Exists"))
            .andExpect(jsonPath("$.message").value(containsString(userId)))
            .andExpect(jsonPath("$.timestamp").exists())
            .andExpect(jsonPath("$.path").value("/users"));
    }

    @Test
    @DisplayName("handleInsufficientBalanceException - Returns 400 with error response")
    void handleInsufficientBalanceException_Returns400WithErrorResponse() throws Exception {
        // Given - Mock facade to throw InsufficientBalanceException
        CreateTransferRequest request = CreateTransferRequest.builder()
            .fromUserId("user_001")
            .toUserId("user_002")
            .amount(new BigDecimal("1000.00"))
            .build();

        when(transferFacade.createTransfer(any(CreateTransferRequest.class)))
            .thenThrow(new InsufficientBalanceException("user_001", new BigDecimal("500.00"), new BigDecimal("1000.00")));

        // When & Then
        mockMvc.perform(post("/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Insufficient Balance"))
            .andExpect(jsonPath("$.message").exists())
            .andExpect(jsonPath("$.timestamp").exists())
            .andExpect(jsonPath("$.path").value("/transfers"));
    }

    @Test
    @DisplayName("handleTransferNotFoundException - Returns 404 with error response")
    void handleTransferNotFoundException_Returns404WithErrorResponse() throws Exception {
        // Given - Mock facade to throw TransferNotFoundException
        Long transferId = 999L;
        when(transferFacade.cancelTransfer(anyLong()))
            .thenThrow(new TransferNotFoundException(transferId));

        // When & Then
        mockMvc.perform(post("/transfers/{transferId}/cancel", transferId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.error").value("Transfer Not Found"))
            .andExpect(jsonPath("$.message").value(containsString(String.valueOf(transferId))))
            .andExpect(jsonPath("$.timestamp").exists())
            .andExpect(jsonPath("$.path").value("/transfers/" + transferId + "/cancel"));
    }

    @Test
    @DisplayName("handleInvalidTransferStateException - Returns 400 with error response")
    void handleInvalidTransferStateException_Returns400WithErrorResponse() throws Exception {
        // Given - Mock facade to throw InvalidTransferStateException
        Long transferId = 123L;
        when(transferFacade.cancelTransfer(anyLong()))
            .thenThrow(new InvalidTransferStateException("Cannot cancel transfer in COMPLETED state"));

        // When & Then
        mockMvc.perform(post("/transfers/{transferId}/cancel", transferId))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Invalid Transfer State"))
            .andExpect(jsonPath("$.message").exists())
            .andExpect(jsonPath("$.timestamp").exists())
            .andExpect(jsonPath("$.path").value("/transfers/" + transferId + "/cancel"));
    }

    @Test
    @DisplayName("handleIllegalArgumentException - Returns 400 with error response")
    void handleIllegalArgumentException_Returns400WithErrorResponse() throws Exception {
        // Given - Mock facade to throw IllegalArgumentException
        CreateUserRequest request = CreateUserRequest.builder()
            .userId("test_user")
            .initialBalance(new BigDecimal("100.00"))
            .build();

        when(balanceFacade.createUser(any(CreateUserRequest.class)))
            .thenThrow(new IllegalArgumentException("Invalid argument provided"));

        // When & Then
        mockMvc.perform(post("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Invalid Request"))
            .andExpect(jsonPath("$.message").value("Invalid argument provided"))
            .andExpect(jsonPath("$.timestamp").exists())
            .andExpect(jsonPath("$.path").value("/users"));
    }

    @Test
    @DisplayName("handleMethodArgumentNotValidException - Returns 400 with validation errors")
    void handleMethodArgumentNotValidException_Returns400WithValidationErrors() throws Exception {
        // Given - Invalid request body with multiple validation errors
        CreateUserRequest request = CreateUserRequest.builder()
            .userId(null) // null userId
            .initialBalance(new BigDecimal("-10.00")) // negative balance
            .build();

        // When & Then
        mockMvc.perform(post("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Validation Failed"))
            .andExpect(jsonPath("$.message").exists())
            .andExpect(jsonPath("$.timestamp").exists())
            .andExpect(jsonPath("$.path").value("/users"));
    }

    @Test
    @DisplayName("handleConstraintViolationException - Returns 400 with validation errors")
    void handleConstraintViolationException_Returns400WithValidationErrors() throws Exception {
        // Given - Invalid path variable (too short userId)
        String shortUserId = "ab";

        // When & Then
        mockMvc.perform(get("/users/{userId}/balance", shortUserId))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Validation Failed"))
            .andExpect(jsonPath("$.message").exists())
            .andExpect(jsonPath("$.timestamp").exists())
            .andExpect(jsonPath("$.path").value("/users/" + shortUserId + "/balance"));
    }

    @Test
    @DisplayName("handleGenericException - Returns 500 with generic error message")
    void handleGenericException_Returns500WithGenericErrorMessage() throws Exception {
        // Given - Mock facade to throw unexpected RuntimeException
        when(balanceFacade.getBalance(anyString()))
            .thenThrow(new RuntimeException("Unexpected database error"));

        // When & Then
        mockMvc.perform(get("/users/{userId}/balance", "test_user"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.status").value(500))
            .andExpect(jsonPath("$.error").value("Internal Server Error"))
            .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
            .andExpect(jsonPath("$.timestamp").exists())
            .andExpect(jsonPath("$.path").value("/users/test_user/balance"));
    }
}
