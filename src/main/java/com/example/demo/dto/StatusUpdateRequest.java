package com.example.demo.dto;

import com.example.demo.entity.OrderStatus;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StatusUpdateRequest {

    private OrderStatus status;

}