package uz.carcare.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.carcare.config.AppProperties;
import uz.carcare.model.Car;
import uz.carcare.util.RateLimiter;
import uz.carcare.web.ApiException;
import uz.carcare.web.CarService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static uz.carcare.util.Formats.html;
import static uz.carcare.util.Formats.prettyPlate;

/**
 * Bitta bot hamma foydalanuvchilarga xizmat qiladi (long polling).
 *
 * Spamdan himoya:
 *  - har bir foydalanuvchi 1 daqiqada ko'pi bilan 15 ta xabar yubora oladi,
 *    undan ortig'i javobsiz tashlab yuboriladi (bot ishlashda davom etadi);
 *  - ogohlantirish bir daqiqada faqat bir marta yuboriladi;
 *  - guruh va kanallardagi xabarlar e'tiborsiz qoldiriladi.
 *
 * Mashinani ulash (bepul tasdiqlash, SMS shart emas):
 *   1. Sahifadagi tugma → /start TOKEN
 *   2. Bot "📱 Raqamni yuborish" tugmasini ko'rsatadi
 *   3. Telegram'dagi raqam ro'yxatdan o'tgan raqamga mos kelsa — ulanadi
 */
@Component
public class TelegramBotPoller {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotPoller.class);

    private final TelegramClient telegram;
    private final CarService cars;
    private final AppProperties app;

    private final RateLimiter perUser = new RateLimiter(15, 60_000);
    private final RateLimiter spamWarning = new RateLimiter(1, 60_000);
    /** chatId → raqam tasdiqlanishini kutayotgan mashina tokeni */
    private final Map<Long, String> pendingLinks = new ConcurrentHashMap<>();

    private long offset = 0;
    private boolean initialized = false;

    public TelegramBotPoller(TelegramClient telegram, CarService cars, AppProperties app) {
        this.telegram = telegram;
        this.cars = cars;
        this.app = app;
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 3000)
    public void poll() {
        if (!telegram.enabled()) return;
        if (!initialized) {
            telegram.deleteWebhook();
            initialized = true;
            log.info("Telegram bot ishga tushdi");
        }
        try {
            JsonNode res = telegram.getUpdates(offset, 25);
            if (res == null || !res.path("ok").asBoolean(false)) return;
            for (JsonNode upd : res.path("result")) {
                offset = upd.path("update_id").asLong() + 1;
                try {
                    handle(upd.path("message"));
                } catch (Exception e) {
                    log.error("Bot xabarini qayta ishlashda xato", e);
                }
            }
        } catch (Exception e) {
            log.warn("getUpdates xatosi: {}", e.getMessage());
            sleepQuietly(5000);
        }
    }

    @Scheduled(fixedDelay = 300_000)
    public void cleanup() {
        perUser.cleanup();
        spamWarning.cleanup();
        if (pendingLinks.size() > 10_000) pendingLinks.clear(); // xotira to'lib ketmasligi uchun
    }

    private void handle(JsonNode msg) {
        if (msg.isMissingNode()) return;
        if (!"private".equals(msg.path("chat").path("type").asText())) return; // guruhlar e'tiborsiz

        long chatId = msg.path("chat").path("id").asLong();

        // ---- Spam himoyasi ----
        if (!perUser.tryAcquire(String.valueOf(chatId))) {
            if (spamWarning.tryAcquire(String.valueOf(chatId))) {
                telegram.sendMessage(chatId, "⏳ Juda ko'p xabar yuboryapsiz. Bir daqiqadan keyin urinib ko'ring.");
            }
            return;
        }

        if (msg.has("contact")) {
            handleContact(chatId, msg);
            return;
        }
        if (!msg.has("text")) return;

        String text = msg.path("text").asText("").trim();
        String name = msg.path("from").path("first_name").asText("");

        if (text.startsWith("/start")) {
            String token = text.length() > 6 ? text.substring(6).trim() : "";
            if (token.isEmpty()) {
                telegram.sendMessage(chatId, welcome(name));
                return;
            }
            String plate = cars.plateByToken(token);
            if (plate == null) {
                telegram.sendMessage(chatId, "❌ Havola noto'g'ri yoki eskirgan. Mashina sahifasidan qayta urinib ko'ring.");
                return;
            }
            pendingLinks.put(chatId, token);
            telegram.sendMessage(chatId,
                    "🚗 <b>" + html(prettyPlate(plate)) + "</b> mashinasini ulash uchun telefon raqamingizni tasdiqlang.\n\n"
                            + "Pastdagi <b>📱 Raqamni yuborish</b> tugmasini bosing.",
                    TelegramClient.contactKeyboard());
        } else if (text.startsWith("/holat") || text.startsWith("/status")) {
            telegram.sendMessage(chatId, cars.statusText(chatId));
        } else if (text.startsWith("/ball") || text.startsWith("/bonus") || text.startsWith("/karta")) {
            telegram.sendMessage(chatId, cars.loyaltyText(chatId));
        } else {
            telegram.sendMessage(chatId, welcome(name));
        }
    }

    private void handleContact(long chatId, JsonNode msg) {
        String token = pendingLinks.get(chatId);
        if (token == null) {
            telegram.sendMessage(chatId, "Avval mashina sahifasidagi <b>\"Telegramga ulash\"</b> tugmasini bosing.",
                    TelegramClient.removeKeyboard());
            return;
        }
        JsonNode contact = msg.path("contact");
        // Faqat o'z raqamini yuborishi kerak (boshqa odamning kontaktini emas)
        if (contact.path("user_id").asLong() != msg.path("from").path("id").asLong()) {
            telegram.sendMessage(chatId, "❌ Faqat o'zingizning raqamingizni yuboring — pastdagi tugmani bosing.",
                    TelegramClient.contactKeyboard());
            return;
        }
        try {
            Car car = cars.linkTelegram(token, chatId, contact.path("phone_number").asText(""));
            pendingLinks.remove(chatId);
            telegram.sendMessage(chatId,
                    "✅ <b>" + html(prettyPlate(car.getPlateNumber())) + "</b> mashinasi ulandi!\n\n"
                            + "Servis muddatiga " + app.reminderDaysBefore()
                            + " kun qolganda va muddat kelgan kuni shu yerga eslatma keladi.\n\n"
                            + cars.statusText(chatId),
                    TelegramClient.removeKeyboard());
        } catch (ApiException e) {
            pendingLinks.remove(chatId);
            telegram.sendMessage(chatId, "❌ " + html(e.getMessage()), TelegramClient.removeKeyboard());
        }
    }

    private String welcome(String name) {
        return "Assalomu alaykum" + (name.isBlank() ? "" : ", " + html(name)) + "! 👋\n\n"
                + "Men mashinangiz servislari (moy, filtrlar, sug'urta va h.k.) muddatini eslatib turaman.\n\n"
                + "1️⃣ Saytda ro'yxatdan o'ting: " + app.baseUrl() + "\n"
                + "2️⃣ Mashina sahifasida <b>\"Telegramga ulash\"</b> tugmasini bosing\n\n"
                + "Buyruqlar:\n/holat — mashinalarim va yaqin servislar\n/ball — jamg'arma kartalarim va bonus ballarim";
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
