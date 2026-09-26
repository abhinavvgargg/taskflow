package com.abhinav.taskflow.common.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("dev")
@Component
@Slf4j
public class LoggingEmailSender implements EmailSender {

    // One deliberate place a token is allowed in a log.
    @Override
    public void send(EmailMessage emailMessage) {
        log.info("Email sent to {}, with subject {}, and body {}", emailMessage.to(), emailMessage.subject(), emailMessage.body());
    }
}
