package com.abhinav.taskflow.common.mail;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The test "inbox": records every email instead of sending it, so integration tests can read a verification
 * link exactly the way a real user would.
 *
 * <p>Thread-safe on purpose: with a RANDOM_PORT test, the app sends on a Tomcat request thread while the
 * test reads on its own thread.
 *
 * <p>It's a singleton in a cached, shared application context, so messages from one test are still here in
 * the next. Call {@link #clear()} in {@code @BeforeEach}.
 */
public class CapturingEmailSender implements EmailSender {

    private final List<EmailMessage> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(EmailMessage emailMessage) {
        sent.add(emailMessage);
    }

    /** Every message sent since the last {@link #clear()}, oldest first. */
    public List<EmailMessage> sentMessages() {
        return List.copyOf(sent);
    }

    /** The most recent message sent to this address, if any. */
    public Optional<EmailMessage> latestTo(String to) {
        for (int i = sent.size() - 1; i >= 0; i--) {
            if (sent.get(i).to().equals(to)) {
                return Optional.of(sent.get(i));
            }
        }
        return Optional.empty();
    }

    public void clear() {
        sent.clear();
    }
}
