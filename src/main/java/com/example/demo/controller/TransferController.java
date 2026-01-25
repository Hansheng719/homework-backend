package com.example.demo.controller;

import com.example.demo.facade.TransferFacade;
import com.example.demo.facade.dto.CancelTransferResponse;
import com.example.demo.facade.dto.CreateTransferRequest;
import com.example.demo.facade.dto.CreateTransferResponse;
import com.example.demo.facade.dto.TransferHistoryResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Validated
@RestController
@RequestMapping("/transfers")
@RequiredArgsConstructor
public class TransferController {

    private final TransferFacade transferFacade;

    @PostMapping
    public ResponseEntity<CreateTransferResponse> createTransfer(
            @Valid @RequestBody CreateTransferRequest request) {
        log.info("POST /transfers - fromUserId={}, toUserId={}, amount={}",
                request.getFromUserId(), request.getToUserId(), request.getAmount());

        CreateTransferResponse response = transferFacade.createTransfer(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<TransferHistoryResponse> getTransferHistory(
            @RequestParam
            @NotBlank(message = "UserId cannot be null or blank")
            @Size(min = 3, max = 50, message = "UserId length must be between 3 and 50 characters")
            String userId,

            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "Page number must be >= 0")
            int page,

            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "Page size must be >= 1")
            @Max(value = 100, message = "Page size must be <= 100")
            int size) {
        log.info("GET /transfers?userId={}&page={}&size={}", userId, page, size);

        TransferHistoryResponse response = transferFacade.getTransferHistory(userId, page, size);

        return ResponseEntity.ok(response);
    }

    @PostMapping("/{transferId}/cancel")
    public ResponseEntity<CancelTransferResponse> cancelTransfer(
            @PathVariable
            @Positive(message = "TransferId must be positive")
            Long transferId) {
        log.info("POST /transfers/{}/cancel", transferId);

        CancelTransferResponse response = transferFacade.cancelTransfer(transferId);

        return ResponseEntity.ok(response);
    }
}
