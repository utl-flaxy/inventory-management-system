package com.example.demo.controller;

import com.example.demo.dto.OrderRequest;
import com.example.demo.dto.SalesResponse;
import com.example.demo.dto.StatusUpdateRequest;
import com.example.demo.entity.Order;
import com.example.demo.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayInputStream;
import java.util.List;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    // 注文登録
    @PostMapping
    public Order create(@RequestBody OrderRequest request) {

        return orderService.createOrder(request);
    }

    // 注文一覧取得
    @GetMapping
    public List<Order> getAll() {

        return orderService.findAll();
    }

    // 注文詳細取得
    @GetMapping("/{id}")
    public Order getById(@PathVariable Long id) {

        return orderService.findById(id);
    }

    // 売上集計
    @GetMapping("/sales")
    public SalesResponse getSales() {

        return orderService.getSales();
    }

    // 注文ステータス変更
    @PatchMapping("/{id}/status")
    public Order updateStatus(
            @PathVariable Long id,
            @RequestBody StatusUpdateRequest request) {

        return orderService.updateStatus(
                id,
                request.getStatus()
        );
    }

    // 注文キャンセル
    @PatchMapping("/{id}/cancel")
    public Order cancel(@PathVariable Long id) {

        return orderService.cancelOrder(id);
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