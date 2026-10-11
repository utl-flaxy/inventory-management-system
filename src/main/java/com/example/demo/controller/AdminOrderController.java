package com.example.demo.controller;

import com.example.demo.dto.OrderResponse;
import com.example.demo.dto.SalesResponse;
import com.example.demo.dto.StatusUpdateRequest;
import com.example.demo.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/orders")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Slf4j
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
    public SalesResponse getSales(@RequestParam(required = false) Instant from,
                                  @RequestParam(required = false) Instant to) {
        return orderService.getSales(from, to);
    }

    // 注文ステータス変更
    @PatchMapping("/{id}/status")
    public OrderResponse updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody StatusUpdateRequest request) {

        OrderResponse response = OrderResponse.from(orderService.updateStatus(
                id,
                request.getStatus()
        ));
        String event = switch (response.status()) {
            case CANCELLED -> "order.cancel.completed";
            case SHIPPED -> "order.ship.completed";
            case COMPLETED -> "order.complete.completed";
            case PENDING -> null; // Serviceが拒否するため成功コマンドは存在しない。
        };
        if (event != null) {
            logCommand(event, response);
        }
        return response;
    }

    // 注文キャンセル
    @RequestMapping(value = "/{id}/cancel", method = {RequestMethod.POST, RequestMethod.PATCH})
    public OrderResponse cancel(@PathVariable Long id) {

        OrderResponse response = OrderResponse.from(orderService.cancelOrder(id));
        logCommand("order.cancel.completed", response);
        return response;
    }

    @PostMapping("/{id}/ship")
    public OrderResponse ship(@PathVariable Long id) {
        OrderResponse response = OrderResponse.from(orderService.shipOrder(id));
        logCommand("order.ship.completed", response);
        return response;
    }

    @PostMapping("/{id}/complete")
    public OrderResponse complete(@PathVariable Long id) {
        OrderResponse response = OrderResponse.from(orderService.completeOrder(id));
        logCommand("order.complete.completed", response);
        return response;
    }

    private void logCommand(String event, OrderResponse response) {
        log.atInfo().addKeyValue("event", event).addKeyValue("orderId", response.id())
                .addKeyValue("status", response.status()).log("Order command completed");
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
