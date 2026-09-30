package com.abhinav.taskflow.user;

public record AccountContact(Long accountId, String email) {

    @Override
    public String toString() {
        return "AccountContact{}";
    }
}
