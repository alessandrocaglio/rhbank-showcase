package com.showcase.engine.repository;

import com.showcase.engine.domain.TransactionLedger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(properties = {
    "spring.flyway.locations=classpath:db/migration"
})
class TransactionLedgerRepositoryTest {

    @Autowired
    private TransactionLedgerRepository repository;

    @Test
    void shouldSaveAndFindLedgerRecord() {
        TransactionLedger ledger = TransactionLedger.create(
                "txn-001",
                "ACC-001",
                "ACC-002",
                new BigDecimal("150.00"),
                "USD",
                "PENDING");

        repository.save(ledger);

        Optional<TransactionLedger> found = repository.findById("txn-001");

        assertThat(found).isPresent();
        TransactionLedger saved = found.get();
        assertThat(saved.getTransactionId()).isEqualTo("txn-001");
        assertThat(saved.getSourceAccount()).isEqualTo("ACC-001");
        assertThat(saved.getDestinationAccount()).isEqualTo("ACC-002");
        assertThat(saved.getAmount()).isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(saved.getCurrency()).isEqualTo("USD");
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void shouldCountTwoSavedRecords() {
        TransactionLedger first = TransactionLedger.create(
                "txn-002",
                "ACC-001",
                "ACC-002",
                new BigDecimal("200.00"),
                "USD",
                "PENDING");

        TransactionLedger second = TransactionLedger.create(
                "txn-003",
                "ACC-003",
                "ACC-004",
                new BigDecimal("75.50"),
                "EUR",
                "COMPLETED");

        repository.save(first);
        repository.save(second);

        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void shouldReturnEmptyForUnknownId() {
        Optional<TransactionLedger> result = repository.findById("does-not-exist");

        assertThat(result).isEmpty();
    }
}
