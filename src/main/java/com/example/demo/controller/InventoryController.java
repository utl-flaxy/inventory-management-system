package com.example.demo.controller;

import com.example.demo.dto.InventoryAdjustmentRequest;
import com.example.demo.dto.InventoryMovementResponse;
import com.example.demo.service.InventoryService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/products/{id}")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class InventoryController {
    private final InventoryService service;

    @PostMapping("/inventory-adjustments")
    @Operation(summary = "現在在庫に差分を適用し履歴を記録する（ADMIN）")
    public InventoryMovementResponse adjust(@PathVariable Long id,
            @Valid @RequestBody InventoryAdjustmentRequest request) {
        return service.adjust(id, request);
    }

    @GetMapping("/inventory-movements")
    @Operation(summary = "商品の在庫履歴（ADMIN）", description = "page/sizeだけを使用。sortはcreatedAt DESC, id DESCへ固定する")
    public Page<InventoryMovementResponse> history(@PathVariable Long id, @ParameterObject Pageable pageable) {
        return service.history(id, pageable);
    }
}
