package com.company.msisdn.blacklist;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class BlacklistStore {

    private final AtomicReference<BlacklistSnapshot> current = new AtomicReference<>();
    private final AtomicReference<BlacklistSnapshot> previous = new AtomicReference<>();

    public void publish(BlacklistSnapshot snapshot) {
        BlacklistSnapshot prior = current.getAndSet(snapshot);
        if (prior != null) {
            previous.set(prior);
        }
    }

    public BlacklistSnapshot current() {
        return current.get();
    }

    public BlacklistSnapshot previous() {
        return previous.get();
    }
}
