package com.abhinav.taskflow.user;

import com.abhinav.taskflow.user.event.SecurityEventRepository;
import com.abhinav.taskflow.user.event.SecurityEventType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class LoginHistoryService {

    public static final Sort SORT = Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"));

    static final Set<SecurityEventType> LOGIN_TYPES = Set.of(
            SecurityEventType.LOGIN_SUCCEEDED,
            SecurityEventType.LOGIN_FAILED,
            SecurityEventType.ACCOUNT_LOCKED);

    private final SecurityEventRepository securityEventRepository;

    @Transactional(readOnly = true)
    public Page<LoginHistoryResponse> getLoginHistory(long userAccountId, Pageable pageable) {
        return securityEventRepository.findByUserAccountIdAndEventTypeIn(userAccountId, LOGIN_TYPES, pageable).map(LoginHistoryResponse::from);
    }
}
