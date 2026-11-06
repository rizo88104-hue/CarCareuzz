package uz.carcare.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import uz.carcare.config.TelegramProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Telegram Bot API bilan ishlash (https://core.telegram.org/bots/api).
 *
 * Telegram limitlari: bot umumiy ~30 xabar/sekund, bitta chatga ~1 xabar/sekund.
 * Shu sababli yuborish tezligi cheklangan va 429 (Too Many Requests) javobida
 * Telegram aytgan vaqtcha kutib, qayta yuboriladi.
 */
@Component
public class TelegramClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramClient.class);
    /** Xabarlar orasidagi minimal oraliq: 40 ms ≈ 25 xabar/sekund (limitdan pastroq). */
    private static final long MIN_INTERVAL_MS = 40;

    private final TelegramProperties props;
    private final RestClient http;
    private final ObjectMapper json = new ObjectMapper();
    private long lastSendAt = 0;

    public TelegramClient(TelegramProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(45)); // long polling 25 s dan uzunroq bo'lishi kerak
        this.http = RestClient.builder()
                .baseUrl("https://api.telegram.org/bot" + props.botToken())
                .requestFactory(factory)
                .build();
    }

    public boolean enabled() {
        return props.enabled();
    }

    public boolean sendMessage(long chatId, String html) {
        return sendMessage(chatId, html, null);
    }

    /**
     * HTML formatdagi xabar yuboradi. replyMarkup — klaviatura (masalan "raqamni yuborish" tugmasi).
     * Muvaffaqiyatli bo'lsa true.
     */
    public boolean sendMessage(long chatId, String html, Map<String, Object> replyMarkup) {
        if (!enabled()) {
            log.info("[Telegram o'chiq] chat={} xabar:\n{}", chatId, html);
            return false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", html);
        body.put("parse_mode", "HTML");
        body.put("disable_web_page_preview", true);
        if (replyMarkup != null) body.put("reply_markup", replyMarkup);

        for (int attempt = 1; attempt <= 2; attempt++) {
            throttle();
            try {
                JsonNode res = http.post().uri("/sendMessage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
                return res != null && res.path("ok").asBoolean(false);
            } catch (HttpClientErrorException.TooManyRequests e) {
                long wait = retryAfterSeconds(e);
                log.warn("Telegram limiti (429), {} s kutamiz", wait);
                sleepQuietly(wait * 1000);
            } catch (RestClientException e) {
                // Masalan foydalanuvchi botni bloklagan bo'lsa (403)
                log.warn("Telegramga yuborib bo'lmadi (chat={}): {}", chatId, e.getMessage());
                return false;
            }
        }
        return false;
    }

    /** Yangi xabarlarni olish (long polling). */
    public JsonNode getUpdates(long offset, int timeoutSeconds) {
        return http.get()
                .uri(b -> b.path("/getUpdates")
                        .queryParam("offset", offset)
                        .queryParam("timeout", timeoutSeconds)
                        .build())
                .retrieve()
                .body(JsonNode.class);
    }

    /** Agar oldin webhook o'rnatilgan bo'lsa, long polling ishlashi uchun uni o'chiradi. */
    public void deleteWebhook() {
        try {
            http.post().uri("/deleteWebhook").retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("deleteWebhook xatosi: {}", e.getMessage());
        }
    }

    // ---------- Klaviaturalar ----------

    /** "📱 Raqamni yuborish" tugmasi (foydalanuvchi bir bosishda o'z raqamini yuboradi). */
    public static Map<String, Object> contactKeyboard() {
        return Map.of(
                "keyboard", new Object[][]{{Map.of("text", "📱 Raqamni yuborish", "request_contact", true)}},
                "resize_keyboard", true,
                "one_time_keyboard", true);
    }

    public static Map<String, Object> removeKeyboard() {
        return Map.of("remove_keyboard", true);
    }

    // ---------- Yordamchi ----------

    private synchronized void throttle() {
        long wait = lastSendAt + MIN_INTERVAL_MS - System.currentTimeMillis();
        if (wait > 0) sleepQuietly(wait);
        lastSendAt = System.currentTimeMillis();
    }

    private long retryAfterSeconds(HttpClientErrorException e) {
        try {
            long s = json.readTree(e.getResponseBodyAsString()).path("parameters").path("retry_after").asLong(5);
            return Math.min(Math.max(s, 1), 60);
        } catch (Exception ex) {
            return 5;
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
