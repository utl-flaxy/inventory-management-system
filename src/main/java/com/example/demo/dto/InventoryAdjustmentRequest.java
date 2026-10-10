package com.example.demo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InventoryAdjustmentRequest {
    @NotNull(message = "deltaは必須です")
    @NonZero
    @Schema(description = "signed non-zero quantity。現在在庫へ加算する差分", format = "int32")
    private Integer delta;

    @NotNull(message = "reasonは必須です")
    private ManualInventoryReason reason;

    @Size(max = 500, message = "noteは500文字以内で入力してください")
    @Schema(description = "任意の操作理由。blankはnullとして保存", maxLength = 500)
    private String note;
}
