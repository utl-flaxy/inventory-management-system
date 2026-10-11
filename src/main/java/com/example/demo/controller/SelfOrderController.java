package com.example.demo.controller;

import com.example.demo.dto.OrderRequest;
import com.example.demo.dto.OrderResponse;
import com.example.demo.dto.PageResponse;
import com.example.demo.service.OrderPageableNormalizer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.util.MultiValueMap;
import com.example.demo.service.OrderService;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/v1/me/orders")
@PreAuthorize("hasAnyRole('USER', 'ADMIN')")
@RequiredArgsConstructor
@Slf4j
public class SelfOrderController {
    private final OrderService orderService;

    @PostMapping
    public OrderResponse create(
            @Parameter(description = "customer単位の必須key。retryは同じkeyを再利用。同key/別内容は409。",
                    example = "01J9VQW2M7MX7KQ7S2H4F7ABCD")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody OrderRequest request) {
        OrderResponse response = orderService.createOrderResponse(request, idempotencyKey);
        log.atInfo().addKeyValue("event", "order.create.completed")
                .addKeyValue("orderId", response.id()).addKeyValue("status", response.status())
                .addKeyValue("itemCount", response.items().size()).log("Order creation completed");
        return response;
    }

    @GetMapping
    @Operation(description = "認証済みcustomerの注文一覧。破壊的変更: JSON配列からPageResponse<OrderResponse>へ変更。"
            + "同一request内はread-only REPEATABLE_READ snapshot。別ページrequest間の更新は固定しません。",
            parameters = {
                @io.swagger.v3.oas.annotations.Parameter(name = "page", in = io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY, description = "0-based。default 0。不正値は400 INVALID_REQUEST。",
                        schema = @Schema(type = "integer", defaultValue = "0", minimum = "0")),
                @io.swagger.v3.oas.annotations.Parameter(name = "size", in = io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY, description = "default 20、max 100。100超は100。不正値/0/負数は400 INVALID_REQUEST。",
                        schema = @Schema(type = "integer", defaultValue = "20", minimum = "1", maximum = "100")),
                @io.swagger.v3.oas.annotations.Parameter(name = "sort", in = io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY, description = "id,orderedAt,status,totalPriceのみ。field,asc/desc。複数sort可。default id,desc。id不在時だけid DESC追加。重複field/未知field/不正方向は400 INVALID_REQUEST。",
                        array = @ArraySchema(schema = @Schema(type = "string", example = "id,desc")))
            })
    public PageResponse<OrderResponse> getAll(
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters) {
        return PageResponse.from(orderService.findMyOrders(OrderPageableNormalizer.parse(parameters)));
    }

    @GetMapping("/{id}")
    public OrderResponse getById(@PathVariable Long id) {
        return orderService.findMyOrderResponse(id);
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable Long id) {
        OrderResponse response = orderService.cancelMyOrderResponse(id);
        log.atInfo().addKeyValue("event", "order.cancel.completed")
                .addKeyValue("orderId", response.id()).addKeyValue("status", response.status())
                .log("Order cancellation completed");
        return response;
    }
}
