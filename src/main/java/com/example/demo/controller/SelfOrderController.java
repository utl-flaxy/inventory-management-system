package com.example.demo.controller;

import com.example.demo.dto.OrderRequest;
import com.example.demo.dto.OrderResponse;
import com.example.demo.service.OrderService;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/me/orders")
@PreAuthorize("hasAnyRole('USER', 'ADMIN')")
@RequiredArgsConstructor
public class SelfOrderController {
    private final OrderService orderService;

    @PostMapping
    public OrderResponse create(
            @Parameter(description = "customer単位の必須key。retryは同じkeyを再利用。同key/別内容は409。",
                    example = "01J9VQW2M7MX7KQ7S2H4F7ABCD")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody OrderRequest request) {
        return OrderResponse.from(orderService.createOrder(request, idempotencyKey));
    }

    @GetMapping
    public List<OrderResponse> getAll() {
        return orderService.findMyOrders().stream().map(OrderResponse::from).toList();
    }

    @GetMapping("/{id}")
    public OrderResponse getById(@PathVariable Long id) {
        return OrderResponse.from(orderService.findMyOrder(id));
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable Long id) {
        return OrderResponse.from(orderService.cancelMyOrder(id));
    }
}
