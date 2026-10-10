package com.example.demo.dto;

import com.example.demo.entity.OrderStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StatusUpdateRequest {

    @NotNull(message = "注文ステータスは必須です")
    private OrderStatus status;

}
