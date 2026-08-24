package com.example;

import org.junit.jupiter.api.Test;

class OrderServiceTest {
    @Test
    void constructs() {
        new OrderService(new MailConfig());
    }
}
