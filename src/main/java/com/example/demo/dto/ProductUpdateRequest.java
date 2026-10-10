package com.example.demo.dto;

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
