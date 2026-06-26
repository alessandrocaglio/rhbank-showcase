package com.showcase.gateway.service;

import com.showcase.gateway.dto.PaymentRequest;
import com.showcase.gateway.dto.PaymentResponse;

public interface PaymentService {

    PaymentResponse initiatePayment(PaymentRequest request);
}
