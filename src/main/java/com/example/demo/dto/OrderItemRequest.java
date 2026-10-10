package com.example.demo.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemRequest {

    @NotNull(message = "商品IDは必須です")
    @Positive(message = "商品IDは正の値を入力してください")
    private Long productId;

    @NotNull(message = "数量は必須です")
    @Min(value = 1, message = "数量は1以上を入力してください")
    private Integer quantity;

}
