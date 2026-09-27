package dev.duohome.hingelab;

/** Only a live UI heartbeat renews the lease; sensor samples never do. */
public final class ClientLease {
    public static final long TIMEOUT_MS = 5000;
    private volatile long renewed;
    public ClientLease(long now) { renewed=now; }
    public void renew(long now) { renewed=now; }
    public boolean valid(long now) { return now>=renewed && now-renewed<TIMEOUT_MS; }
}
