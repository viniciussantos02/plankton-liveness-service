package com.plankton.liveness.controller;

import com.plankton.liveness.dto.request.CreateSessionRequest;
import com.plankton.liveness.dto.response.CreateSessionResponse;
import com.plankton.liveness.service.LivenessSessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints de gerenciamento de sessões de liveness.
 */
@RestController
@RequestMapping("/api/v1/liveness/sessions")
@RequiredArgsConstructor
public class LivenessSessionController {

    private final LivenessSessionService livenessSessionService;

    @PostMapping
    public ResponseEntity<CreateSessionResponse> createSession(
            @RequestHeader("X-Device-ID") String deviceId,
            @Valid @RequestBody CreateSessionRequest request) {

        CreateSessionResponse response = livenessSessionService.createSession(request, deviceId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
