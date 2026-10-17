package com.example.demo.dto;

import com.example.demo.validation.CodePointSize;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "商品metadataだけを更新する。stock/unknown fieldは400で拒否",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class ProductUpdateRequest {

    @NotBlank(message = "商品名は必須です")
    @CodePointSize(max = 255, message = "商品名は255コードポイント以内の正しいUnicodeで入力してください")
    @Schema(minLength = 1, maxLength = 255,
            description = "商品名。最大255 Unicodeコードポイント（絵文字などの補助平面文字も1）。trim・Unicode正規化はしない")
    private String name;
    @NotNull(message = "価格は必須です")
    @Min(value = 1, message = "価格は1円以上を入力してください")
    private Long price;

    @NotNull(message = "更新元のversionは必須です")
    @Min(value = 0, message = "versionは0以上を入力してください")
    private Long version;

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("商品更新に未対応のfieldがあります");
    }
}
