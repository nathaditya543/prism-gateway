package com.prism.gateway;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/** A clock tests can move forward. */
public final class MutableClock extends Clock {

    public final AtomicLong millis = new AtomicLong(1_000_000);

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
    @Override public long millis() { return millis.get(); }
}
