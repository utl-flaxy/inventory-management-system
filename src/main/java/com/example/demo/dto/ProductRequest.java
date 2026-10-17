package com.example.demo.dto;

import com.example.demo.validation.CodePointSize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ProductRequest {

    @NotBlank(message = "商品名は必須です")
    @CodePointSize(max = 255, message = "商品名は255コードポイント以内の正しいUnicodeで入力してください")
    @Schema(minLength = 1, maxLength = 255,
            description = "商品名。最大255 Unicodeコードポイント（絵文字などの補助平面文字も1）。trim・Unicode正規化はしない")
    private String name;

    @NotNull(message = "価格は必須です")
    @Min(value = 1, message = "価格は1円以上を入力してください")
    private Long price;

    @NotNull(message = "在庫数は必須です")
    @Min(value = 0, message = "在庫数は0以上を入力してください")
    private Integer stock;
}
