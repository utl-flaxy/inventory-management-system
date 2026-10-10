package com.example.demo.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProductUpdateRequest extends ProductRequest {

    @NotNull(message = "更新元のversionは必須です")
    @Min(value = 0, message = "versionは0以上を入力してください")
    private Long version;
}
