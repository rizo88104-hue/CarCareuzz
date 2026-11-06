package uz.carcare.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * SMS sozlamalari ("sms.*"). Eskiz.uz orqali yuboriladi.
 * enabled=false bo'lsa SMS yuborilmaydi, faqat logga yoziladi.
 */
@ConfigurationProperties("sms")
public record SmsProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("https://notify.eskiz.uz/api") String eskizUrl,
        @DefaultValue("") String eskizEmail,
        @DefaultValue("") String eskizPassword,
        @DefaultValue("4546") String from
) {
}
