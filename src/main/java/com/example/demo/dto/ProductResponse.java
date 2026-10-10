package com.example.demo.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProductResponse {

    private Long id;
    private String name;
    private Long price;
    private Integer stock;
    private Long version;

}
