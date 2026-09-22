package com.abhinav.taskflow.common.web;

import com.abhinav.taskflow.common.config.ApiProperties;
import com.abhinav.taskflow.common.error.InvalidSortException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
public class PageableFactory {

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");
    private static final String TIEBREAKER = "id";

    private final ApiProperties apiProperties;

    public Pageable of(Integer page, Integer size, Sort sort, Set<String> allowedSortFields)
    {
        int p = (page == null || page < 0) ? 0 : page;
        int s = (size == null || size < 1) ? apiProperties.defaultPageSize() : Math.min(size, apiProperties.maxPageSize());
        return PageRequest.of(p, s, stabilize(sort, allowedSortFields));
    }

    private Sort stabilize(Sort sort, Set<String> allowedSortFields) {
        Sort effective = (sort == null || sort.isUnsorted()) ? DEFAULT_SORT : validate(sort, allowedSortFields);
        return effective.getOrderFor(TIEBREAKER) != null
                ? effective
                : effective.and(Sort.by(Sort.Direction.DESC, TIEBREAKER));
    }

    private Sort validate (Sort sort, Set<String> allowedSortFields)
    {
        for (Sort.Order order : sort)
        {
            if (!allowedSortFields.contains(order.getProperty())) {
                throw new InvalidSortException(order.getProperty() + " is not allowed");
            }
        }
        return sort;
    }
}