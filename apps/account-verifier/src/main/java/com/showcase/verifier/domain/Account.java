package com.showcase.verifier.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @Column(name = "account_id")
    private String accountId;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "balance")
    private BigDecimal balance;

    @Column(name = "status")
    private String status;

    protected Account() {
        // Required by JPA/Hibernate
    }

    Account(String accountId, String customerName, BigDecimal balance, String status) {
        this.accountId = accountId;
        this.customerName = customerName;
        this.balance = balance;
        this.status = status;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public String getStatus() {
        return status;
    }
}
