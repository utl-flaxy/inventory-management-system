package com.example.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SalesResponse {

    private Long totalSales;
    private Long totalOrders;
}
