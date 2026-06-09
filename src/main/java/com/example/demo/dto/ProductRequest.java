package com.example.demo.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ProductRequest {

    @NotBlank(message = "商品名は必須です")
    private String name;

    @NotNull(message = "価格は必須です")
    @Min(value = 1, message = "価格は1円以上を入力してください")
    private Integer price;

    @NotNull(message = "在庫数は必須です")
    @Min(value = 0, message = "在庫数は0以上を入力してください")
    private Integer stock;
}