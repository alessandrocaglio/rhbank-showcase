package com.showcase.engine.service;

import com.showcase.engine.domain.TransactionLedger;

public interface MqPublishingService {

    void publishToClearingQueue(TransactionLedger ledger);
}
