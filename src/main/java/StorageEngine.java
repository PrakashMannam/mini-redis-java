import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * StorageEngine manages all in-memory data, expiration, and atomic math.
 * Thread-safe: Uses ConcurrentHashMap to support multiple simultaneous client threads.
 */
public class StorageEngine {

    private static final StorageEngine INSTANCE = new StorageEngine();

    // Primary key-value memory store
    private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

    // Expiration timestamp map (Key -> System.currentTimeMillis() when it should die)
    private final ConcurrentHashMap<String, Long> expiryMap = new ConcurrentHashMap<>();

    // Background active expiration worker
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();

    private StorageEngine() {
        // Active TTL cleanup: wakes up every 1 second and removes expired keys
        cleaner.scheduleAtFixedRate(this::purgeExpiredKeys, 1, 1, TimeUnit.SECONDS);
    }

    public static StorageEngine getInstance() {
        return INSTANCE;
    }

    public void set(String key, String value) {
        store.put(key, value);
        expiryMap.remove(key); // Clear any previous TTL
    }

    public void set(String key, String value, int expireSeconds) {
        store.put(key, value);
        expiryMap.put(key, System.currentTimeMillis() + (expireSeconds * 1000L));
    }

    public String get(String key) {
        // Lazy expiration check
        Long expiresAt = expiryMap.get(key);
        if (expiresAt != null && System.currentTimeMillis() > expiresAt) {
            delete(key);
            return null; // Key expired
        }
        return store.get(key);
    }

    public boolean delete(String key) {
        expiryMap.remove(key);
        return store.remove(key) != null;
    }

    public boolean exists(String key) {
        // Calling get() first ensures lazy expiry triggers if key died
        return get(key) != null;
    }

    public Set<String> keys() {
        return store.keySet();
    }

    public int incr(String key) throws NumberFormatException {
        String val = get(key);
        int num = (val == null) ? 0 : Integer.parseInt(val);
        num++;
        store.put(key, String.valueOf(num));
        return num;
    }

    public int decr(String key) throws NumberFormatException {
        String val = get(key);
        int num = (val == null) ? 0 : Integer.parseInt(val);
        num--;
        store.put(key, String.valueOf(num));
        return num;
    }

    public long ttl(String key) {
        if (!store.containsKey(key)) {
            return -2; // Key does not exist
        }
        Long expiresAt = expiryMap.get(key);
        if (expiresAt == null) {
            return -1; // Exists, but lives forever (no TTL)
        }
        long remaining = (expiresAt - System.currentTimeMillis()) / 1000;
        return Math.max(0, remaining);
    }

    private void purgeExpiredKeys() {
        long now = System.currentTimeMillis();
        for (String key : expiryMap.keySet()) {
            Long expiresAt = expiryMap.get(key);
            if (expiresAt != null && now > expiresAt) {
                store.remove(key);
                expiryMap.remove(key);
                System.out.println("Key expired and removed: " + key);
            }
        }
    }
}
