package uz.carcare.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Telegram bot sozlamalari ("telegram.*"). Token bo'sh bo'lsa bot o'chiq turadi. */
@ConfigurationProperties("telegram")
public record TelegramProperties(
        @DefaultValue("") String botToken,
        @DefaultValue("") String botUsername
) {
    public boolean enabled() {
        return botToken != null && !botToken.isBlank();
    }

    /** Mashinani botga ulash uchun havola: https://t.me/bot?start=TOKEN */
    public String deepLink(String carToken) {
        if (botUsername == null || botUsername.isBlank()) return null;
        String name = botUsername.startsWith("@") ? botUsername.substring(1) : botUsername;
        return "https://t.me/" + name + "?start=" + carToken;
    }
}
