package com.showcase.verifier.repository;

import com.showcase.verifier.domain.Account;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;

import java.util.Optional;

@ApplicationScoped
public class AccountRepository implements PanacheRepositoryBase<Account, String> {

    public Optional<Account> findByAccountIdForUpdate(String accountId) {
        return find("accountId", accountId)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResultOptional();
    }
}
