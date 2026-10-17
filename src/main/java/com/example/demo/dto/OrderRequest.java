package com.example.demo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
public class OrderRequest {

    public static final int MAX_ITEMS = 100;
    public static final String ITEMS_LIMIT_MESSAGE = "注文明細は100件以内で入力してください";

    @NotEmpty(message = "注文明細は1件以上必要です")
    @Size(max = MAX_ITEMS, message = ITEMS_LIMIT_MESSAGE)
    @ArraySchema(minItems = 1, maxItems = MAX_ITEMS, schema = @Schema(implementation = OrderItemRequest.class),
            arraySchema = @Schema(description = "注文明細。入力配列で1〜100件。重複商品IDは拒否し、暗黙に統合しない"))
    @Valid
    private List<@NotNull(message = "注文明細は必須です") OrderItemRequest> items;

}
