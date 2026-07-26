package com.qizhi.gateway.controller;

import com.qizhi.common.core.result.R;
import com.qizhi.gateway.filter.RateLimitFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/config/rate-limit")
@CrossOrigin(origins = {"http://127.0.0.1:5173", "http://localhost:5173"})
@RequiredArgsConstructor
public class RateLimitConfigController {

    private final RateLimitFilter rateLimitFilter;

    @GetMapping
    public R<Map<String, Object>> get() {
        return R.ok(rateLimitFilter.currentConfiguration());
    }

    @PutMapping
    public R<Map<String, Object>> update(@Valid @RequestBody UpdateRequest request) {
        rateLimitFilter.updateLimits(request.userQps, request.ipQps, request.submitQps,
                request.whiteUsers, request.whiteIps);
        return R.ok(rateLimitFilter.currentConfiguration());
    }

    @Data
    public static class UpdateRequest {
        @Min(1)
        private int userQps;
        @Min(1)
        private int ipQps;
        @Min(1)
        private int submitQps;
        private List<String> whiteUsers = List.of();
        private List<String> whiteIps = List.of();
    }
}
