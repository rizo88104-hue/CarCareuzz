package uz.carcare.reminder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.carcare.admin.AuditService;
import uz.carcare.config.AppProperties;
import uz.carcare.model.Car;
import uz.carcare.model.ServiceCenter;
import uz.carcare.model.ServiceRecord;
import uz.carcare.repo.ServiceRecordRepository;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static uz.carcare.util.Formats.*;

/**
 * Eslatmalar:
 *  - muddatga 7 kun qolganda (app.reminder-days-before) — "1 haftadan keyin almashtiring";
 *  - muddat kelgan kuni — "bugun almashtirish kuni".
 * Har kuni soat 09:00 da (Toshkent vaqti) tekshiriladi, dastur ishga tushganda ham bir marta.
 */
@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    private final ServiceRecordRepository records;
    private final NotificationService notifications;
    private final AppProperties app;
    private final AuditService audit;

    public ReminderService(ServiceRecordRepository records, NotificationService notifications, AppProperties app,
                           AuditService audit) {
        this.audit = audit;
        this.records = records;
        this.notifications = notifications;
        this.app = app;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        runDaily();
    }

    @Scheduled(cron = "${app.reminder-cron:0 0 9 * * *}", zone = "${app.zone:Asia/Tashkent}")
    @Transactional
    public void runDaily() {
        LocalDate today = LocalDate.now(app.zoneId());
        List<ServiceRecord> pending = records.findPendingReminders(today, today.plusDays(app.reminderDaysBefore()));
        int sent = 0;
        for (ServiceRecord r : pending) {
            try {
                if (process(r, today)) sent++;
            } catch (Exception e) {
                log.error("Eslatma yuborishda xato (record={})", r.getId(), e);
            }
        }
        records.saveAll(pending);
        log.info("Eslatmalar tekshirildi: {} ta navbatda, {} ta yuborildi", pending.size(), sent);
        if (!pending.isEmpty()) {
            int failed = pending.size() - sent;
            audit.system("Tizim", "Bot", null, sent + " ta eslatma yuborildi"
                    + (failed > 0 ? ", " + failed + " tasi yetkazilmadi (Telegram ulanmagan yoki bot bloklangan)" : ""));
        }
    }

    /** Yangi yozuv qo'shilganda darhol tekshirish (masalan muddatga 3 kun qolgan bo'lsa). */
    public void checkNow(ServiceRecord r) {
        process(r, LocalDate.now(app.zoneId()));
    }

    /** @return xabar yuborilgan bo'lsa true. Yetkazilmasa keyingi kuni yana urinadi. */
    private boolean process(ServiceRecord r, LocalDate today) {
        long days = ChronoUnit.DAYS.between(today, r.getNextDate());
        Car car = r.getCar();

        if (days <= 0 && !r.isDueReminderSent()) {
            if (notifications.notify(car, dueTelegram(r, days), dueSms(r, days))) {
                r.setDueReminderSent(true);
                r.setWeekReminderSent(true);
                return true;
            }
        } else if (days > 0 && days <= app.reminderDaysBefore() && !r.isWeekReminderSent()) {
            if (notifications.notify(car, weekTelegram(r, days), weekSms(r, days))) {
                r.setWeekReminderSent(true);
                return true;
            }
        }
        return false;
    }

    // ---------- Xabar matnlari ----------

    private String weekTelegram(ServiceRecord r, long days) {
        Car car = r.getCar();
        return "🔔 <b>Eslatma</b>\n\n"
                + "🚗 Mashina: <b>" + html(prettyPlate(car.getPlateNumber())) + "</b>\n"
                + "🔧 Servis: <b>" + html(r.displayName()) + "</b>\n"
                + "📅 Almashtirish sanasi: <b>" + date(r.getNextDate()) + "</b>\n"
                + "⏳ <b>" + days + " kun</b> qoldi\n"
                + mileageLine(r)
                + "\nOldindan servisga yozilib qo'ying 👍\n"
                + "Sahifa: " + app.carUrl(car.getToken());
    }

    private String dueTelegram(ServiceRecord r, long days) {
        Car car = r.getCar();
        String when = days == 0
                ? "⚠️ <b>Bugun almashtirish kuni!</b>"
                : "❗️ Muddati <b>" + (-days) + " kun</b> o'tib ketdi!";
        return when + "\n\n"
                + "🚗 Mashina: <b>" + html(prettyPlate(car.getPlateNumber())) + "</b>\n"
                + "🔧 Servis: <b>" + html(r.displayName()) + "</b>\n"
                + "📅 Belgilangan sana: <b>" + date(r.getNextDate()) + "</b>\n"
                + mileageLine(r)
                + "\nAlmashtirgandan keyin sahifada yangi sanani belgilang:\n"
                + app.carUrl(car.getToken());
    }

    private String mileageLine(ServiceRecord r) {
        String s = r.getNextMileage() == null ? "" : "🛣 Probeg bo'yicha: <b>" + r.getNextMileage() + " km</b>\n";
        ServiceCenter c = r.getCenter();
        if (c != null) {
            s += "📍 Servis: <b>" + html(c.getName()) + "</b>"
                    + (c.getPhone() == null ? "" : ", 📞 " + prettyPhone(c.getPhone())) + "\n";
        }
        return s;
    }

    private String weekSms(ServiceRecord r, long days) {
        return "Eslatma: " + prettyPlate(r.getCar().getPlateNumber()) + " mashinangizda "
                + r.displayName() + " almashtirishga " + days + " kun qoldi (" + date(r.getNextDate()) + ").";
    }

    private String dueSms(ServiceRecord r, long days) {
        String when = days == 0 ? "bugun almashtirish kuni" : "almashtirish muddati " + (-days) + " kun o'tdi";
        return "Eslatma: " + prettyPlate(r.getCar().getPlateNumber()) + " mashinangizda "
                + r.displayName() + " " + when + " (" + date(r.getNextDate()) + ").";
    }
}
