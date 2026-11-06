package uz.carcare.util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Oddiy "sliding window" cheklovchi: har bir kalit (chat yoki IP) uchun
 * windowMs ichida ko'pi bilan max ta so'rovga ruxsat beradi.
 */
public class RateLimiter {

    private final int max;
    private final long windowMs;
    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public RateLimiter(int max, long windowMs) {
        this.max = max;
        this.windowMs = windowMs;
    }

    /** Ruxsat bo'lsa true va so'rovni hisobga oladi; limit oshgan bo'lsa false. */
    public boolean tryAcquire(String key) {
        long now = System.currentTimeMillis();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > windowMs) q.pollFirst();
            if (q.size() >= max) return false;
            q.addLast(now);
            return true;
        }
    }

    /** Eskirgan kalitlarni xotiradan tozalaydi (vaqti-vaqti bilan chaqiriladi). */
    public void cleanup() {
        long now = System.currentTimeMillis();
        hits.entrySet().removeIf(e -> {
            Deque<Long> q = e.getValue();
            synchronized (q) {
                return q.isEmpty() || now - q.peekLast() > windowMs;
            }
        });
    }
}
