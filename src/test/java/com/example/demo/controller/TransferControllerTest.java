package com.example.demo.controller;

import com.example.demo.entity.TransferStatus;
import com.example.demo.exception.InsufficientBalanceException;
import com.example.demo.exception.InvalidTransferStateException;
import com.example.demo.exception.TransferNotFoundException;
import com.example.demo.exception.UserNotFoundException;
import com.example.demo.facade.TransferFacade;
import com.example.demo.facade.dto.*;
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
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {TransferController.class})
@AutoConfigureMockMvc
@DisplayName("TransferController Tests")
class TransferControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransferFacade transferFacade;

    @Autowired
    private ObjectMapper objectMapper;

    // ===== POST /transfers - createTransfer Tests =====

    @Test
    @DisplayName("createTransfer - With valid request - Returns 201 CREATED")
    void createTransfer_WithValidRequest_Returns201Created() throws Exception {
        // Given
        CreateTransferRequest request = buildCreateTransferRequest("user_001", "user_002", new BigDecimal("100.00"));
        CreateTransferResponse mockResponse = buildCreateTransferResponse(1L, "user_001", "user_002", new BigDecimal("100.00"));

        when(transferFacade.createTransfer(any(CreateTransferRequest.class)))
            .thenReturn(mockResponse);

        // When & Then
        mockMvc.perform(post("/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.fromUserId").value("user_001"))
            .andExpect(jsonPath("$.toUserId").value("user_002"))
            .andExpect(jsonPath("$.amount").value(100.00))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.createdAt").exists());

        verify(transferFacade, times(1)).createTransfer(any(CreateTransferRequest.class));
    }

    @Test
    @DisplayName("createTransfer - With null fromUserId - Returns 400 BAD_REQUEST")
    void createTransfer_WithNullFromUserId_Returns400BadRequest() throws Exception {
        // Given
        CreateTransferRequest request = buildCreateTransferRequest(null, "user_002", new BigDecimal("100.00"));

        // When & Then
        mockMvc.perform(post("/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Validation Failed"))
            .andExpect(jsonPath("$.message").value(containsString("FromUserId cannot be null or blank")));

        verify(transferFacade, never()).createTransfer(any(CreateTransferRequest.class));
    }

    @Test
    @DisplayName("createTransfer - With too short toUserId - Returns 400 BAD_REQUEST")
    void createTransfer_WithTooShortToUserId_Returns400BadRequest() throws Exception {
        // Given
        CreateTransferRequest request = buildCreateTransferRequest("user_001", "ab", new BigDecimal("100.00"));

        // When & Then
        mockMvc.perform(post("/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Validation Failed"))
            .andExpect(jsonPath("$.message").value(containsString("length must be between 3 and 50")));

        verify(transferFacade, never()).createTransfer(any(CreateTransferRequest.class));
    }

    @Test
    @DisplayName("createTransfer - With zero amount - Returns 400 BAD_REQUEST")
    void createTransfer_WithZeroAmount_Returns400BadRequest() throws Exception {
        // Given
        CreateTransferRequest request = buildCreateTransferRequest("user_001", "user_002", BigDecimal.ZERO);

        // When & Then
        mockMvc.perform(post("/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Validation Failed"))
            .andExpect(jsonPath("$.message").value(containsString("Amount must be > 0")));

        verify(transferFacade, never()).createTransfer(any(CreateTransferRequest.class));
    }

    @Test
    @DisplayName("createTransfer - With negative amount - Returns 400 BAD_REQUEST")
    void createTransfer_WithNegativeAmount_Returns400BadRequest() throws Exception {
        // Given
        CreateTransferRequest request = buildCreateTransferRequest("user_001", "user_002", new BigDecimal("-50.00"));

        // When & Then
        mockMvc.perform(post("/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Validation Failed"))
            .andExpect(jsonPath("$.message").value(containsString("Amount must be > 0")));

        verify(transferFacade, never()).createTransfer(any(CreateTransferRequest.class));
    }

    @Test
    @DisplayName("createTransfer - With self transfer - Returns 400 BAD_REQUEST")
    void createTransfer_WithSelfTransfer_Returns400BadRequest() throws Exception {
        // Given - same fromUserId and toUserId
        CreateTransferRequest request = buildCreateTransferRequest("user_001", "user_001", new BigDecimal("100.00"));

        // When & Then
        mockMvc.perform(post("/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Validation Failed"))
            .andExpect(jsonPath("$.message").value(containsString("Cannot transfer to yourself")));

        verify(transferFacade, never()).createTransfer(any(CreateTransferRequest.class));
    }

    @Test
    @DisplayName("createTransfer - With non-existing user - Returns 404 NOT_FOUND")
    void createTransfer_WithNonExistingUser_Returns404NotFound() throws Exception {
        // Given
        CreateTransferRequest request = buildCreateTransferRequest("user_001", "nonexistent_user", new BigDecimal("100.00"));

        // Mock facade to throw UserNotFoundException
        when(transferFacade.createTransfer(any(CreateTransferRequest.class)))
            .thenThrow(new UserNotFoundException("nonexistent_user"));

        // When & Then
        mockMvc.perform(post("/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.error").value("User Not Found"))
            .andExpect(jsonPath("$.message").value(containsString("nonexistent_user")));

        verify(transferFacade, times(1)).createTransfer(any(CreateTransferRequest.class));
    }

    @Test
    @DisplayName("createTransfer - With insufficient balance - Returns 400 BAD_REQUEST")
    void createTransfer_WithInsufficientBalance_Returns400BadRequest() throws Exception {
        // Given
        CreateTransferRequest request = buildCreateTransferRequest("user_001", "user_002", new BigDecimal("1000.00"));

        // Mock facade to throw InsufficientBalanceException
        when(transferFacade.createTransfer(any(CreateTransferRequest.class)))
            .thenThrow(new InsufficientBalanceException("user_001", new BigDecimal("500.00"), new BigDecimal("1000.00")));

        // When & Then
        mockMvc.perform(post("/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Insufficient Balance"));

        verify(transferFacade, times(1)).createTransfer(any(CreateTransferRequest.class));
    }

    // ===== GET /transfers - getTransferHistory Tests =====

    @Test
    @DisplayName("getTransferHistory - With default pagination - Returns 200 OK")
    void getTransferHistory_WithDefaultPagination_Returns200Ok() throws Exception {
        // Given
        String userId = "user_001";
        TransferHistoryResponse mockResponse = buildTransferHistoryResponse(5, 0, 20, 5);

        when(transferFacade.getTransferHistory(anyString(), anyInt(), anyInt()))
            .thenReturn(mockResponse);

        // When & Then
        mockMvc.perform(get("/transfers")
                .param("userId", userId))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.transfers").isArray())
            .andExpect(jsonPath("$.transfers.length()").value(5))
            .andExpect(jsonPath("$.pagination.currentPage").value(0))
            .andExpect(jsonPath("$.pagination.pageSize").value(20))
            .andExpect(jsonPath("$.pagination.totalElements").value(5))
            .andExpect(jsonPath("$.pagination.hasNext").value(false))
            .andExpect(jsonPath("$.pagination.hasPrevious").value(false));

        verify(transferFacade, times(1)).getTransferHistory(userId, 0, 20);
    }

    @Test
    @DisplayName("getTransferHistory - With custom pagination - Returns 200 OK")
    void getTransferHistory_WithCustomPagination_Returns200Ok() throws Exception {
        // Given
        String userId = "user_001";
        int page = 1;
        int size = 10;
        TransferHistoryResponse mockResponse = buildTransferHistoryResponse(10, page, size, 25);

        when(transferFacade.getTransferHistory(anyString(), anyInt(), anyInt()))
            .thenReturn(mockResponse);

        // When & Then
        mockMvc.perform(get("/transfers")
                .param("userId", userId)
                .param("page", String.valueOf(page))
                .param("size", String.valueOf(size)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transfers.length()").value(10))
            .andExpect(jsonPath("$.pagination.currentPage").value(page))
            .andExpect(jsonPath("$.pagination.pageSize").value(size))
            .andExpect(jsonPath("$.pagination.totalElements").value(25))
            .andExpect(jsonPath("$.pagination.hasNext").value(true))
            .andExpect(jsonPath("$.pagination.hasPrevious").value(true));

        verify(transferFacade, times(1)).getTransferHistory(userId, page, size);
    }

    @Test
    @DisplayName("getTransferHistory - With blank userId - Returns 400 BAD_REQUEST")
    void getTransferHistory_WithBlankUserId_Returns400BadRequest() throws Exception {
        // When & Then - userId param is required by @NotBlank
        mockMvc.perform(get("/transfers")
                .param("userId", "  "))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Validation Failed"));

        verify(transferFacade, never()).getTransferHistory(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("getTransferHistory - With too short userId - Returns 400 BAD_REQUEST")
    void getTransferHistory_WithTooShortUserId_Returns400BadRequest() throws Exception {
        // Given
        String shortUserId = "ab";

        // When & Then
        mockMvc.perform(get("/transfers")
                .param("userId", shortUserId))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Validation Failed"));

        verify(transferFacade, never()).getTransferHistory(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("getTransferHistory - With negative page - Returns 400 BAD_REQUEST")
    void getTransferHistory_WithNegativePage_Returns400BadRequest() throws Exception {
        // When & Then
        mockMvc.perform(get("/transfers")
                .param("userId", "user_001")
                .param("page", "-1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Validation Failed"))
            .andExpect(jsonPath("$.message").value(containsString("Page number must be >= 0")));

        verify(transferFacade, never()).getTransferHistory(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("getTransferHistory - With zero page size - Returns 400 BAD_REQUEST")
    void getTransferHistory_WithZeroPageSize_Returns400BadRequest() throws Exception {
        // When & Then
        mockMvc.perform(get("/transfers")
                .param("userId", "user_001")
                .param("size", "0"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Validation Failed"))
            .andExpect(jsonPath("$.message").value(containsString("Page size must be >= 1")));

        verify(transferFacade, never()).getTransferHistory(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("getTransferHistory - With too large page size - Returns 400 BAD_REQUEST")
    void getTransferHistory_WithTooLargePageSize_Returns400BadRequest() throws Exception {
        // When & Then
        mockMvc.perform(get("/transfers")
                .param("userId", "user_001")
                .param("size", "101"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Validation Failed"))
            .andExpect(jsonPath("$.message").value(containsString("Page size must be <= 100")));

        verify(transferFacade, never()).getTransferHistory(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("getTransferHistory - With non-existing user - Returns 404 NOT_FOUND")
    void getTransferHistory_WithNonExistingUser_Returns404NotFound() throws Exception {
        // Given
        String userId = "nonexistent_user";

        // Mock facade to throw UserNotFoundException
        when(transferFacade.getTransferHistory(anyString(), anyInt(), anyInt()))
            .thenThrow(new UserNotFoundException(userId));

        // When & Then
        mockMvc.perform(get("/transfers")
                .param("userId", userId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.error").value("User Not Found"));

        verify(transferFacade, times(1)).getTransferHistory(userId, 0, 20);
    }

    // ===== POST /transfers/{transferId}/cancel - cancelTransfer Tests =====

    @Test
    @DisplayName("cancelTransfer - With pending transfer - Returns 200 OK")
    void cancelTransfer_WithPendingTransfer_Returns200Ok() throws Exception {
        // Given
        Long transferId = 123L;
        CancelTransferResponse mockResponse = buildCancelTransferResponse(transferId, "user_001", "user_002", new BigDecimal("100.00"));

        when(transferFacade.cancelTransfer(anyLong()))
            .thenReturn(mockResponse);

        // When & Then
        mockMvc.perform(post("/transfers/{transferId}/cancel", transferId))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.id").value(transferId))
            .andExpect(jsonPath("$.fromUserId").value("user_001"))
            .andExpect(jsonPath("$.toUserId").value("user_002"))
            .andExpect(jsonPath("$.amount").value(100.00))
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.cancelledAt").exists());

        verify(transferFacade, times(1)).cancelTransfer(transferId);
    }

    @Test
    @DisplayName("cancelTransfer - With zero transferId - Returns 400 BAD_REQUEST")
    void cancelTransfer_WithZeroTransferId_Returns400BadRequest() throws Exception {
        // When & Then
        mockMvc.perform(post("/transfers/{transferId}/cancel", 0L))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Validation Failed"))
            .andExpect(jsonPath("$.message").value(containsString("TransferId must be positive")));

        verify(transferFacade, never()).cancelTransfer(anyLong());
    }

    @Test
    @DisplayName("cancelTransfer - With negative transferId - Returns 400 BAD_REQUEST")
    void cancelTransfer_WithNegativeTransferId_Returns400BadRequest() throws Exception {
        // When & Then
        mockMvc.perform(post("/transfers/{transferId}/cancel", -1L))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Validation Failed"))
            .andExpect(jsonPath("$.message").value(containsString("TransferId must be positive")));

        verify(transferFacade, never()).cancelTransfer(anyLong());
    }

    @Test
    @DisplayName("cancelTransfer - With non-existing transfer - Returns 404 NOT_FOUND")
    void cancelTransfer_WithNonExistingTransfer_Returns404NotFound() throws Exception {
        // Given
        Long transferId = 999L;

        // Mock facade to throw TransferNotFoundException
        when(transferFacade.cancelTransfer(anyLong()))
            .thenThrow(new TransferNotFoundException(transferId));

        // When & Then
        mockMvc.perform(post("/transfers/{transferId}/cancel", transferId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.error").value("Transfer Not Found"));

        verify(transferFacade, times(1)).cancelTransfer(transferId);
    }

    @Test
    @DisplayName("cancelTransfer - With non-pending transfer - Returns 400 BAD_REQUEST")
    void cancelTransfer_WithNonPendingTransfer_Returns400BadRequest() throws Exception {
        // Given
        Long transferId = 123L;

        // Mock facade to throw InvalidTransferStateException
        when(transferFacade.cancelTransfer(anyLong()))
            .thenThrow(new InvalidTransferStateException("Cannot cancel transfer in COMPLETED state"));

        // When & Then
        mockMvc.perform(post("/transfers/{transferId}/cancel", transferId))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Invalid Transfer State"));

        verify(transferFacade, times(1)).cancelTransfer(transferId);
    }

    // ===== Helper Methods =====

    private CreateTransferRequest buildCreateTransferRequest(String fromUserId, String toUserId, BigDecimal amount) {
        return CreateTransferRequest.builder()
            .fromUserId(fromUserId)
            .toUserId(toUserId)
            .amount(amount)
            .build();
    }

    private CreateTransferResponse buildCreateTransferResponse(Long id, String fromUserId, String toUserId, BigDecimal amount) {
        return CreateTransferResponse.builder()
            .id(id)
            .fromUserId(fromUserId)
            .toUserId(toUserId)
            .amount(amount)
            .status(TransferStatus.PENDING)
            .createdAt(LocalDateTime.now())
            .build();
    }

    private TransferHistoryResponse buildTransferHistoryResponse(int transferCount, int page, int size, long total) {
        // Create transfer items
        List<TransferItemDto> transfers = List.of();
        for (int i = 0; i < transferCount; i++) {
            transfers = new java.util.ArrayList<>(transfers);
            transfers.add(buildTransferItemDto((long) (i + 1), "user_001", "user_002", new BigDecimal("100.00")));
        }

        // Create pagination metadata
        PaginationMeta pagination = PaginationMeta.builder()
            .currentPage(page)
            .pageSize(size)
            .totalElements(total)
            .totalPages((int) Math.ceil((double) total / size))
            .hasNext(page < Math.ceil((double) total / size) - 1)
            .hasPrevious(page > 0)
            .build();

        return TransferHistoryResponse.builder()
            .transfers(transfers)
            .pagination(pagination)
            .build();
    }

    private TransferItemDto buildTransferItemDto(Long id, String fromUserId, String toUserId, BigDecimal amount) {
        return TransferItemDto.builder()
            .id(id)
            .fromUserId(fromUserId)
            .toUserId(toUserId)
            .amount(amount)
            .status(TransferStatus.COMPLETED)
            .createdAt(LocalDateTime.now())
            .completedAt(LocalDateTime.now())
            .build();
    }

    private CancelTransferResponse buildCancelTransferResponse(Long id, String fromUserId, String toUserId, BigDecimal amount) {
        return CancelTransferResponse.builder()
            .id(id)
            .fromUserId(fromUserId)
            .toUserId(toUserId)
            .amount(amount)
            .status(TransferStatus.CANCELLED)
            .cancelledAt(LocalDateTime.now())
            .build();
    }
}
