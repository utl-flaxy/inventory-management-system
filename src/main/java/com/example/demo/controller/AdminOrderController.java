package com.example.demo.controller;

import com.example.demo.dto.OrderResponse;
import com.example.demo.dto.SalesResponse;
import com.example.demo.dto.StatusUpdateRequest;
import com.example.demo.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayInputStream;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/orders")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminOrderController {

    private final OrderService orderService;

    // 注文一覧取得
    @GetMapping
    public List<OrderResponse> getAll() {

        return orderService.findAll().stream().map(OrderResponse::from).toList();
    }

    // 注文詳細取得
    @GetMapping("/{id}")
    public OrderResponse getById(@PathVariable Long id) {

        return OrderResponse.from(orderService.findById(id));
    }

    // 売上集計
    @GetMapping("/sales")
    public SalesResponse getSales() {

        return orderService.getSales();
    }

    // 注文ステータス変更
    @PatchMapping("/{id}/status")
    public OrderResponse updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody StatusUpdateRequest request) {

        return OrderResponse.from(orderService.updateStatus(
                id,
                request.getStatus()
        ));
    }

    // 注文キャンセル
    @RequestMapping(value = "/{id}/cancel", method = {RequestMethod.POST, RequestMethod.PATCH})
    public OrderResponse cancel(@PathVariable Long id) {

        return OrderResponse.from(orderService.cancelOrder(id));
    }

    @PostMapping("/{id}/ship")
    public OrderResponse ship(@PathVariable Long id) {
        return OrderResponse.from(orderService.shipOrder(id));
    }

    @PostMapping("/{id}/complete")
    public OrderResponse complete(@PathVariable Long id) {
        return OrderResponse.from(orderService.completeOrder(id));
    }

    // CSV出力
    @GetMapping("/export")
    public ResponseEntity<InputStreamResource> exportCsv() {

        ByteArrayInputStream csv =
                orderService.exportCsv();

        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=orders.csv"
                )
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(
                        new InputStreamResource(csv)
                );
    }
}
