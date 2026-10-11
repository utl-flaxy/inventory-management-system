package com.example.demo.controller;

import com.example.demo.dto.InventoryAdjustmentRequest;
import com.example.demo.dto.InventoryMovementResponse;
import com.example.demo.dto.PageResponse;
import com.example.demo.service.InventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/products/{id}")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Slf4j
public class InventoryController {
    private final InventoryService service;

    @PostMapping("/inventory-adjustments")
    @Operation(summary = "現在在庫に差分を適用し履歴を記録する（ADMIN）")
    public InventoryMovementResponse adjust(@PathVariable Long id,
            @Parameter(description = "認証済みactor単位のopaque key。同じkey/内容は初回履歴を返し、内容・商品違いは409。成功keyにTTLなし。",
                    schema = @Schema(type = "string", pattern = "[A-Za-z0-9._:-]{1,128}", minLength = 1, maxLength = 128))
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody InventoryAdjustmentRequest request) {
        InventoryMovementResponse response = service.adjust(id, request, idempotencyKey);
        log.atInfo().addKeyValue("event", "inventory.adjustment.completed")
                .addKeyValue("movementId", response.id()).addKeyValue("productId", response.productId())
                .addKeyValue("delta", response.delta()).log("Inventory adjustment completed");
        return response;
    }

    @GetMapping("/inventory-movements")
    @Operation(summary = "商品の在庫履歴（ADMIN）", description = "page/sizeだけを使用。sortはcreatedAt DESC, id DESCへ固定する")
    public PageResponse<InventoryMovementResponse> history(@PathVariable Long id, @ParameterObject Pageable pageable) {
        return PageResponse.from(service.history(id, pageable));
    }
}
