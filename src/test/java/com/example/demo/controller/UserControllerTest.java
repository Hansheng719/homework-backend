package com.example.demo.controller;

import com.example.demo.exception.UserAlreadyExistsException;
import com.example.demo.exception.UserNotFoundException;
import com.example.demo.facade.BalanceFacade;
import com.example.demo.facade.dto.CreateUserRequest;
import com.example.demo.facade.dto.CreateUserResponse;
import com.example.demo.facade.dto.GetBalanceResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = UserController.class)
@AutoConfigureMockMvc
@DisplayName("UserController Tests")
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BalanceFacade balanceFacade;

    @Autowired
    private ObjectMapper objectMapper;

    // ===== POST /users - createUser Tests =====

    @Test
    @DisplayName("createUser - With valid request - Returns 201 CREATED")
    void createUser_WithValidRequest_Returns201Created() throws Exception {
        // Given
        CreateUserRequest request = buildCreateUserRequest("user_001", new BigDecimal("1000.00"));
        CreateUserResponse mockResponse = buildCreateUserResponse("user_001", new BigDecimal("1000.00"));

        when(balanceFacade.createUser(any(CreateUserRequest.class)))
            .thenReturn(mockResponse);

        // When & Then
        mockMvc.perform(post("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.userId").value("user_001"))
            .andExpect(jsonPath("$.balance").value(1000.00))
            .andExpect(jsonPath("$.createdAt").exists())
            .andExpect(jsonPath("$.version").exists());

        // Verify facade interaction
        verify(balanceFacade, times(1)).createUser(any(CreateUserRequest.class));
    }

    @Test
    @DisplayName("createUser - With zero initial balance - Returns 201 CREATED")
    void createUser_WithZeroInitialBalance_Returns201Created() throws Exception {
        // Given
        CreateUserRequest request = buildCreateUserRequest("user_002", BigDecimal.ZERO);
        CreateUserResponse mockResponse = buildCreateUserResponse("user_002", BigDecimal.ZERO);

        when(balanceFacade.createUser(any(CreateUserRequest.class)))
            .thenReturn(mockResponse);

        // When & Then
        mockMvc.perform(post("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.userId").value("user_002"))
            .andExpect(jsonPath("$.balance").value(0.00));

        verify(balanceFacade, times(1)).createUser(any(CreateUserRequest.class));
    }

    @Test
    @DisplayName("createUser - With null userId - Returns 400 BAD_REQUEST")
    void createUser_WithNullUserId_Returns400BadRequest() throws Exception {
        // Given - userId is null
        CreateUserRequest request = buildCreateUserRequest(null, new BigDecimal("100.00"));

        // When & Then
        mockMvc.perform(post("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Validation Failed"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("UserId cannot be null or blank")));

        // Verify facade never called
        verify(balanceFacade, never()).createUser(any(CreateUserRequest.class));
    }

    @Test
    @DisplayName("createUser - With too short userId - Returns 400 BAD_REQUEST")
    void createUser_WithTooShortUserId_Returns400BadRequest() throws Exception {
        // Given - userId has only 2 characters
        CreateUserRequest request = buildCreateUserRequest("ab", new BigDecimal("100.00"));

        // When & Then
        mockMvc.perform(post("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Validation Failed"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("length must be between 3 and 50")));

        verify(balanceFacade, never()).createUser(any(CreateUserRequest.class));
    }

    @Test
    @DisplayName("createUser - With too long userId - Returns 400 BAD_REQUEST")
    void createUser_WithTooLongUserId_Returns400BadRequest() throws Exception {
        // Given - userId has 51 characters
        String longUserId = "a".repeat(51);
        CreateUserRequest request = buildCreateUserRequest(longUserId, new BigDecimal("100.00"));

        // When & Then
        mockMvc.perform(post("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Validation Failed"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("length must be between 3 and 50")));

        verify(balanceFacade, never()).createUser(any(CreateUserRequest.class));
    }

    @Test
    @DisplayName("createUser - With negative initial balance - Returns 400 BAD_REQUEST")
    void createUser_WithNegativeInitialBalance_Returns400BadRequest() throws Exception {
        // Given - negative balance
        CreateUserRequest request = buildCreateUserRequest("user_003", new BigDecimal("-100.00"));

        // When & Then
        mockMvc.perform(post("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Validation Failed"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("InitialBalance must be >= 0")));

        verify(balanceFacade, never()).createUser(any(CreateUserRequest.class));
    }

    @Test
    @DisplayName("createUser - With null initial balance - Returns 400 BAD_REQUEST")
    void createUser_WithNullInitialBalance_Returns400BadRequest() throws Exception {
        // Given - null balance
        CreateUserRequest request = buildCreateUserRequest("user_004", null);

        // When & Then
        mockMvc.perform(post("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Validation Failed"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("InitialBalance cannot be null")));

        verify(balanceFacade, never()).createUser(any(CreateUserRequest.class));
    }

    @Test
    @DisplayName("createUser - With existing user - Returns 409 CONFLICT")
    void createUser_WithExistingUser_Returns409Conflict() throws Exception {
        // Given
        CreateUserRequest request = buildCreateUserRequest("existing_user", new BigDecimal("100.00"));

        // Mock facade to throw UserAlreadyExistsException
        when(balanceFacade.createUser(any(CreateUserRequest.class)))
            .thenThrow(new UserAlreadyExistsException("existing_user"));

        // When & Then
        mockMvc.perform(post("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.error").value("User Already Exists"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("existing_user")));

        verify(balanceFacade, times(1)).createUser(any(CreateUserRequest.class));
    }

    // ===== GET /users/{userId}/balance - getBalance Tests =====

    @Test
    @DisplayName("getBalance - With existing user - Returns 200 OK")
    void getBalance_WithExistingUser_Returns200Ok() throws Exception {
        // Given
        String userId = "user_001";
        GetBalanceResponse mockResponse = buildGetBalanceResponse(userId, new BigDecimal("500.00"));

        when(balanceFacade.getBalance(anyString()))
            .thenReturn(mockResponse);

        // When & Then
        mockMvc.perform(get("/users/{userId}/balance", userId))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.userId").value(userId))
            .andExpect(jsonPath("$.balance").value(500.00));

        verify(balanceFacade, times(1)).getBalance(userId);
    }

    @Test
    @DisplayName("getBalance - With too short userId - Returns 400 BAD_REQUEST")
    void getBalance_WithTooShortUserId_Returns400BadRequest() throws Exception {
        // Given - userId with only 2 characters
        String shortUserId = "ab";

        // When & Then
        mockMvc.perform(get("/users/{userId}/balance", shortUserId))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Validation Failed"));

        verify(balanceFacade, never()).getBalance(anyString());
    }

    @Test
    @DisplayName("getBalance - With too long userId - Returns 400 BAD_REQUEST")
    void getBalance_WithTooLongUserId_Returns400BadRequest() throws Exception {
        // Given - userId with 51 characters
        String longUserId = "a".repeat(51);

        // When & Then
        mockMvc.perform(get("/users/{userId}/balance", longUserId))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Validation Failed"));

        verify(balanceFacade, never()).getBalance(anyString());
    }

    @Test
    @DisplayName("getBalance - With non-existing user - Returns 404 NOT_FOUND")
    void getBalance_WithNonExistingUser_Returns404NotFound() throws Exception {
        // Given
        String userId = "nonexistent_user";

        // Mock facade to throw UserNotFoundException
        when(balanceFacade.getBalance(anyString()))
            .thenThrow(new UserNotFoundException(userId));

        // When & Then
        mockMvc.perform(get("/users/{userId}/balance", userId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.error").value("User Not Found"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(userId)));

        verify(balanceFacade, times(1)).getBalance(userId);
    }

    // ===== Helper Methods =====

    private CreateUserRequest buildCreateUserRequest(String userId, BigDecimal initialBalance) {
        return CreateUserRequest.builder()
            .userId(userId)
            .initialBalance(initialBalance)
            .build();
    }

    private CreateUserResponse buildCreateUserResponse(String userId, BigDecimal balance) {
        return CreateUserResponse.builder()
            .userId(userId)
            .balance(balance)
            .createdAt(LocalDateTime.now())
            .version(0L)
            .build();
    }

    private GetBalanceResponse buildGetBalanceResponse(String userId, BigDecimal balance) {
        return GetBalanceResponse.builder()
            .userId(userId)
            .balance(balance)
            .build();
    }
}
