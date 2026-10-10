package com.example.demo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class OrderRequest {

    @NotEmpty(message = "注文明細は1件以上必要です")
    @Valid
    private List<@NotNull(message = "注文明細は必須です") OrderItemRequest> items;

}
