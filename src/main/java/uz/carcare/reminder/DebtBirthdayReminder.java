package uz.carcare.reminder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uz.carcare.admin.AuditService;
import uz.carcare.config.AppProperties;
import uz.carcare.loyalty.LoyaltyService;
import uz.carcare.model.Car;
import uz.carcare.model.ServiceCenter;
import uz.carcare.model.ServiceRecord;
import uz.carcare.repo.CarRepository;
import uz.carcare.repo.ServiceRecordRepository;
import uz.carcare.telegram.TelegramClient;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static uz.carcare.util.Formats.*;

/**
 * Har kuni 09:00 da (servis eslatmalari bilan bir vaqtda):
 *  - QARZ: to'lash muddatiga 1 kun qolganda; muddat o'tgach — darhol, keyin har 7 kunda.
 *    Bir mijozning bir servisdagi barcha qarzlari bitta xabarda yuboriladi.
 *  - TUG'ILGAN KUN: tabrik, yiliga bir marta (sadoqat dasturi bor servislar bonus ball qo'shadi).
 *  - SADOQAT: ballar kuyishidan 7 kun oldin ogohlantirish va muddati o'tgan ballarni kuydirish.
 * Faqat Telegram orqali (bepul). Mijoz botga ulanmagan bo'lsa, hech narsa yuborilmaydi.
 */
@Service
public class DebtBirthdayReminder {

    private static final Logger log = LoggerFactory.getLogger(DebtBirthdayReminder.class);
    /** Muddati o'tgan qarz haqida necha kunda bir eslatiladi. */
    private static final int OVERDUE_REPEAT_DAYS = 7;

    private final ServiceRecordRepository records;
    private final CarRepository cars;
    private final TelegramClient telegram;
    private final AuditService audit;
    private final AppProperties app;
    private final LoyaltyService loyalty;
    /** Har bir qism alohida tranzaksiyada: bittasidagi xato boshqasini bekor qilmaydi (startupda ham ishlaydi). */
    private final TransactionTemplate tx;

    public DebtBirthdayReminder(ServiceRecordRepository records, CarRepository cars, TelegramClient telegram,
                                AuditService audit, AppProperties app, LoyaltyService loyalty,
                                PlatformTransactionManager txManager) {
        this.loyalty = loyalty;
        this.tx = new TransactionTemplate(txManager);
        this.records = records;
        this.cars = cars;
        this.telegram = telegram;
        this.audit = audit;
        this.app = app;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        runDaily();
    }

    @Scheduled(cron = "${app.reminder-cron:0 0 9 * * *}", zone = "${app.zone:Asia/Tashkent}")
    public synchronized void runDaily() {
        LocalDate today = LocalDate.now(app.zoneId());
        int debts = 0, birthdays = 0;
        int[] expiry = {0, 0};
        try {
            debts = tx.execute(s -> remindDebts(today));
        } catch (Exception e) {
            log.error("Qarz eslatmalarida xato", e);
        }
        try {
            birthdays = tx.execute(s -> greetBirthdays(today));
        } catch (Exception e) {
            log.error("Tug'ilgan kun tabriklarida xato", e);
        }
        try {
            expiry = loyalty.expire(today);
        } catch (Exception e) {
            log.error("Sadoqat ballari kuyishini tekshirishda xato", e);
        }
        if (debts > 0 || birthdays > 0 || expiry[0] > 0 || expiry[1] > 0) {
            audit.system("Tizim", "Bot", null, "Qarz eslatmasi: " + debts + " ta mijozga, tug'ilgan kun tabrigi: " + birthdays
                    + " ta, ballar kuyishi haqida ogohlantirish: " + expiry[0] + " ta, ballari kuygan: " + expiry[1] + " ta");
        }
        log.info("Qarz eslatmalari: {}, tabriklar: {}, kuyish ogohlantirishi: {}, kuygan: {}", debts, birthdays, expiry[0], expiry[1]);
    }

    // ---------- Qarz ----------

    int remindDebts(LocalDate today) {
        // Mijoz + servis bo'yicha guruhlaymiz
        Map<String, List<ServiceRecord>> groups = new LinkedHashMap<>();
        for (ServiceRecord r : records.findDebtsDueBy(today.plusDays(1))) {
            if (r.getRemaining() <= 0 || r.getCar().getTelegramChatId() == null || r.getCenter() == null) continue;
            if (!shouldRemind(r, today)) continue;
            groups.computeIfAbsent(r.getCar().getId() + ":" + r.getCenter().getId(), k -> new ArrayList<>()).add(r);
        }
        int sent = 0;
        for (List<ServiceRecord> list : groups.values()) {
            Car car = list.get(0).getCar();
            ServiceCenter center = list.get(0).getCenter();
            StringBuilder sb = new StringBuilder("💳 <b>Qarz eslatmasi</b>\n\n")
                    .append("📍 <b>").append(html(center.getName())).append("</b>\n")
                    .append("🚗 ").append(html(prettyPlate(car.getPlateNumber()))).append("\n\n");
            long total = 0;
            for (ServiceRecord r : list) {
                long d = ChronoUnit.DAYS.between(today, r.getPayDueDate());
                String when = d == 1 ? "ertaga" : d == 0 ? "bugun" : (-d) + " kun o'tdi";
                sb.append("• ").append(html(r.displayName())).append(": <b>").append(money(r.getRemaining()))
                        .append("</b> — muddat ").append(date(r.getPayDueDate())).append(" (").append(when).append(")\n");
                total += r.getRemaining();
            }
            if (list.size() > 1) sb.append("\nJami: <b>").append(money(total)).append("</b>\n");
            if (center.getPhone() != null) sb.append("\n📞 ").append(prettyPhone(center.getPhone()));
            if (telegram.sendMessage(car.getTelegramChatId(), sb.toString())) {
                list.forEach(r -> r.setDebtRemindedOn(today));
                sent++;
            }
        }
        return sent;
    }

    /** Ertaga muddat — bir marta; muddat o'tgan — birinchi marta darhol, keyin har 7 kunda. */
    static boolean shouldRemind(ServiceRecord r, LocalDate today) {
        LocalDate due = r.getPayDueDate(), last = r.getDebtRemindedOn();
        if (last != null && last.equals(today)) return false;
        long d = ChronoUnit.DAYS.between(today, due);
        if (d == 1) return last == null || last.isBefore(today.minusDays(1));
        if (d <= 0) return last == null || last.isBefore(due) || ChronoUnit.DAYS.between(last, today) >= OVERDUE_REPEAT_DAYS;
        return false;
    }

    // ---------- Tug'ilgan kun ----------

    int greetBirthdays(LocalDate today) {
        // Avval sadoqat bonuslari (yiliga bir marta), keyin tabrik — sovg'alar tabrikka qo'shiladi
        Map<Long, List<LoyaltyService.Gift>> gifts = new LinkedHashMap<>(loyalty.birthdayBonuses(today));
        int sent = 0;
        for (Car car : cars.findAllWithBirthdayAndTelegram()) {
            if (!isBirthday(car.getBirthDate(), today)) continue;
            List<LoyaltyService.Gift> mine = gifts.remove(car.getId());
            if (Integer.valueOf(today.getYear()).equals(car.getBirthdayGreetedYear())) {
                if (mine != null) sendGifts(car.getTelegramChatId(), mine); // tabrik avval ketgan, sovg'a yangi
                continue;
            }
            StringBuilder text = new StringBuilder("🎉 <b>Tug'ilgan kuningiz bilan, ").append(html(car.getFirstName())).append("!</b>\n\n")
                    .append("Sizga sihat-salomatlik, oilangizga tinchlik va yo'llaringizda omad tilaymiz. 🚗💨\n");
            if (mine != null) {
                text.append("\n");
                mine.forEach(g -> text.append(g.line()).append("\n"));
            }
            text.append("\nHurmat bilan, CarCare jamoasi");
            if (telegram.sendMessage(car.getTelegramChatId(), text.toString())) {
                car.setBirthdayGreetedYear(today.getYear());
                cars.save(car);
                sent++;
            }
        }
        return sent;
    }

    private void sendGifts(Long chatId, List<LoyaltyService.Gift> list) {
        if (chatId == null) return;
        StringBuilder sb = new StringBuilder("🎂 <b>Tug'ilgan kun sovg'asi</b>\n\n");
        list.forEach(g -> sb.append(g.line()).append("\n"));
        telegram.sendMessage(chatId, sb.toString().trim());
    }
}
