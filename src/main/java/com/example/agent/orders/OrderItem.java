package com.example.agent.orders;

import java.math.BigDecimal;

public record OrderItem(String sku, String name, int quantity, BigDecimal unitPrice) {
}
