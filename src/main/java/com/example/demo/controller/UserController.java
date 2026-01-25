package com.example.demo.controller;

import com.example.demo.facade.BalanceFacade;
import com.example.demo.facade.dto.CreateUserRequest;
import com.example.demo.facade.dto.CreateUserResponse;
import com.example.demo.facade.dto.GetBalanceResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@Validated
public class UserController {

    private final BalanceFacade balanceFacade;

    @PostMapping
    public ResponseEntity<CreateUserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        log.info("POST /users - userId={}, initialBalance={}",
                request.getUserId(), request.getInitialBalance());

        CreateUserResponse response = balanceFacade.createUser(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{userId}/balance")
    public ResponseEntity<GetBalanceResponse> getBalance(
            @PathVariable
            @NotBlank(message = "UserId cannot be null or blank")
            @Size(min = 3, max = 50, message = "UserId length must be between 3 and 50 characters")
            String userId) {
        log.info("GET /users/{}/balance", userId);

        GetBalanceResponse response = balanceFacade.getBalance(userId);

        return ResponseEntity.ok(response);
    }
}
