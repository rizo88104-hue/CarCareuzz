package uz.carcare.reminder;

import org.springframework.stereotype.Service;
import uz.carcare.model.Car;
import uz.carcare.sms.SmsService;
import uz.carcare.telegram.TelegramClient;

/** Mashina egasiga Telegram va (yoqilgan bo'lsa) SMS orqali xabar yuboradi. */
@Service
public class NotificationService {

    private final TelegramClient telegram;
    private final SmsService sms;

    public NotificationService(TelegramClient telegram, SmsService sms) {
        this.telegram = telegram;
        this.sms = sms;
    }

    /** @return kamida bitta kanal orqali yetkazilgan bo'lsa true */
    public boolean notify(Car car, String telegramHtml, String smsText) {
        boolean delivered = false;
        if (car.getTelegramChatId() != null) {
            delivered |= telegram.sendMessage(car.getTelegramChatId(), telegramHtml);
        }
        if (car.isSmsEnabled() && sms.enabled()) {
            delivered |= sms.send(car.getPhone(), smsText);
        }
        return delivered;
    }
}
