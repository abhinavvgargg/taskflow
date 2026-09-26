package com.abhinav.taskflow.common.mail;

public record EmailMessage(String to, String subject, String body) {

    @Override
    public String toString() {
        return "EmailMessage{" +
                "to='" + to + '\'' +
                ", subject='" + subject + '\'' +
                '}';
    }
}
