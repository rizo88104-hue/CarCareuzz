package uz.carcare.admin;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.carcare.admin.AdminDtos.PaymentDto;
import uz.carcare.admin.AdminDtos.PaymentRequest;
import uz.carcare.admin.DebtDtos.*;
import uz.carcare.config.AppProperties;
import uz.carcare.loyalty.LoyaltyService;
import uz.carcare.model.*;
import uz.carcare.repo.PaymentRepository;
import uz.carcare.repo.ServiceCenterRepository;
import uz.carcare.repo.ServiceRecordRepository;
import uz.carcare.telegram.TelegramClient;
import uz.carcare.web.ApiException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

import static uz.carcare.util.Formats.*;

/** Qarz daftari va to'lovlar. Faqat direktor va administrator ishlatadi. */
@Service
public class DebtService {

    private static final int LIST_LIMIT = 300;

    private final ServiceRecordRepository records;
    private final PaymentRepository payments;
    private final ServiceCenterRepository centers;
    private final AuditService audit;
    private final TelegramClient telegram;
    private final AppProperties app;
    private final LoyaltyService loyalty;

    public DebtService(ServiceRecordRepository records, PaymentRepository payments, ServiceCenterRepository centers,
                       AuditService audit, TelegramClient telegram, AppProperties app, LoyaltyService loyalty) {
        this.loyalty = loyalty;
        this.records = records;
        this.payments = payments;
        this.centers = centers;
        this.audit = audit;
        this.telegram = telegram;
        this.app = app;
    }

    // =====================================================================
    //  Qarz daftari
    // =====================================================================

    @Transactional(readOnly = true)
    public DebtList list(AdminPrincipal p, DebtFilter filter, Period period, String q) {
        ServiceCenter center = center(p);
        LocalDate today = today();
        LocalDate from = periodStart(period == null ? Period.ALL : period, today);

        // Davr bo'yicha (ish sanasi) — kartalardagi raqamlar shu to'plamdan hisoblanadi
        List<DebtRow> inPeriod = records.findCreditForCenter(center).stream()
                .filter(r -> from == null || !r.getDoneDate().isBefore(from))
                .map(r -> row(r, today))
                .sorted(Comparator.comparing(DebtRow::remaining, Comparator.reverseOrder())
                        .thenComparing(DebtRow::payDueDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        Set<Long> debtors = new HashSet<>();
        int overdue = 0, unpaid = 0, partial = 0, paid = 0;
        long totalDebt = 0, totalPaid = 0, totalRemaining = 0;
        for (DebtRow r : inPeriod) {
            if (r.remaining() > 0) debtors.add(r.carId());
            if (r.overdueDays() > 0) overdue++;
            switch (r.status()) {
                case UNPAID -> unpaid++;
                case PARTIAL -> partial++;
                case PAID -> paid++;
            }
            totalDebt += r.price();
            totalPaid += r.paid();
            totalRemaining += r.remaining();
        }
        DebtSummary summary = new DebtSummary(debtors.size(), overdue, unpaid, partial, paid, totalDebt, totalPaid, totalRemaining);

        Map<DebtFilter, Integer> counts = new EnumMap<>(DebtFilter.class);
        for (DebtFilter f : DebtFilter.values()) counts.put(f, (int) inPeriod.stream().filter(r -> matches(r, f)).count());

        DebtFilter f = filter == null ? DebtFilter.ALL : filter;
        List<DebtRow> rows = inPeriod.stream().filter(r -> matches(r, f)).filter(r -> matchesQuery(r, q)).toList();
        return new DebtList(summary, counts, rows.size(), rows.stream().limit(LIST_LIMIT).toList());
    }

    // =====================================================================
    //  To'lovlar
    // =====================================================================

    /** Qarzni to'liq yoki qisman so'ndirish. Mijoz Telegramga ulangan bo'lsa, unga kvitansiya boradi. */
    @Transactional
    public DebtRow pay(AdminPrincipal p, Long recordId, PaymentRequest req) {
        ServiceCenter center = center(p);
        ServiceRecord r = record(center, recordId);
        long remaining = r.getRemaining();
        if (r.getPrice() == null) throw ApiException.badRequest("Bu ishga hali narx qo'yilmagan");
        if (remaining <= 0) throw ApiException.badRequest("Bu ish uchun qarz yo'q");
        if (req.amount() > remaining) throw ApiException.badRequest("Summa qarz qoldig'idan (" + money(remaining) + ") oshmasin");

        Payment.Method method = req.method() == null ? Payment.Method.CASH : req.method();
        LoyaltyService.Earn earn = null;
        if (method == Payment.Method.BONUS) {
            loyalty.redeem(center, r, req.amount(), p.fullName(), trim(req.note()));
        } else {
            payments.save(new Payment(r, center, req.amount(), method, p.fullName(), trim(req.note())));
            r.addPaid(req.amount());
            earn = loyalty.onMoneyPayment(center, r, req.amount(), p.fullName());
        }

        Car car = r.getCar();
        String plate = prettyPlate(car.getPlateNumber());
        audit.staff(p, center, plate + " — " + r.displayName() + ": " + money(req.amount()) + " to'lov qabul qildi ("
                + method.label().toLowerCase() + ")" + (r.getRemaining() == 0 ? ", qarz to'liq yopildi" : ", qoldiq " + money(r.getRemaining())));

        if (car.getTelegramChatId() != null) {
            telegram.sendMessage(car.getTelegramChatId(),
                    "🧾 <b>To'lov qabul qilindi</b>\n\n"
                            + "🚗 <b>" + html(plate) + "</b> · " + html(r.displayName()) + "\n"
                            + "💵 To'landi: <b>" + money(req.amount()) + "</b> (" + method.label().toLowerCase() + ")\n"
                            + (r.getRemaining() == 0 ? "✅ Qarz to'liq yopildi. Rahmat!" : "📌 Qoldiq: <b>" + money(r.getRemaining()) + "</b>, muddat " + date(r.getPayDueDate()))
                            + LoyaltyService.receiptLine(earn)
                            + "\n\n📍 " + html(center.getName()));
        }
        return row(r, today());
    }

    @Transactional(readOnly = true)
    public List<PaymentDto> paymentsOf(AdminPrincipal p, Long recordId) {
        ServiceRecord r = record(center(p), recordId);
        return payments.findAllByRecordOrderByCreatedAtDesc(r).stream().map(DebtService::paymentDto).toList();
    }

    /** Narx qo'yish yoki o'zgartirish (masalan, usta kiritgan ishga). */
    @Transactional
    public DebtRow setPrice(AdminPrincipal p, Long recordId, PriceRequest req) {
        ServiceCenter center = center(p);
        ServiceRecord r = record(center, recordId);
        // Sadoqat chegirmasi (agar qo'llansa) — req.price chegirmagacha narx
        int pct = Boolean.FALSE.equals(req.applyDiscount()) ? 0 : loyalty.discountPct(center, r.getCar());
        long discount = LoyaltyService.percent(req.price(), pct);
        long finalPrice = req.price() - discount;
        if (finalPrice < r.getPaid()) {
            throw ApiException.badRequest("Narx allaqachon to'langan summadan (" + money(r.getPaid()) + ") kam bo'lishi mumkin emas");
        }
        Long old = r.getPrice();
        r.setPrice(finalPrice);
        r.setDiscount(discount);
        if (r.getRemaining() > 0) {
            LocalDate due = req.payDueDate() != null ? req.payDueDate() : r.getPayDueDate();
            if (due == null) throw ApiException.badRequest("Qarz qolyapti — to'lash muddatini kiriting");
            r.setPayDueDate(due);
            r.setCredit(true);
        }
        audit.staff(p, center, prettyPlate(r.getCar().getPlateNumber()) + " — " + r.displayName() + ": narx "
                + (old == null ? "qo'ydi " : money(old) + " → ") + money(finalPrice)
                + (discount > 0 ? " (chegirma " + pct + "%: −" + money(discount) + ")" : ""));
        return row(r, today());
    }

    // =====================================================================
    //  Yordamchi
    // =====================================================================

    static PaymentDto paymentDto(Payment pay) {
        return new PaymentDto(pay.getId(), pay.getAmount(), pay.getMethod().name(), pay.getMethod().label(),
                pay.getReceivedBy(), pay.getNote(), pay.getCreatedAt());
    }

    DebtRow row(ServiceRecord r, LocalDate today) {
        Car car = r.getCar();
        long price = r.getPrice() == null ? 0 : r.getPrice();
        long remaining = r.getRemaining();
        DebtStatus status = remaining == 0 ? DebtStatus.PAID : r.getPaid() > 0 ? DebtStatus.PARTIAL : DebtStatus.UNPAID;
        long overdue = remaining > 0 && r.getPayDueDate() != null && r.getPayDueDate().isBefore(today)
                ? ChronoUnit.DAYS.between(r.getPayDueDate(), today) : 0;
        return new DebtRow(r.getId(), car.getId(), prettyPlate(car.getPlateNumber()), car.getFullName().trim(),
                car.getPhone(), prettyPhone(car.getPhone()), car.getTelegramChatId() != null,
                r.displayName(), r.getDoneDate(), r.getMasterName(),
                price, r.getPaid(), remaining, r.getPayDueDate(), status, overdue, r.getDiscount(), r.getBonusPaid());
    }

    private static boolean matches(DebtRow r, DebtFilter f) {
        return switch (f) {
            case ALL -> true;
            case OVERDUE -> r.overdueDays() > 0;
            case UNPAID -> r.status() == DebtStatus.UNPAID;
            case PARTIAL -> r.status() == DebtStatus.PARTIAL;
            case PAID -> r.status() == DebtStatus.PAID;
        };
    }

    private static boolean matchesQuery(DebtRow r, String q) {
        if (q == null || q.isBlank()) return true;
        String plate = q.toUpperCase().replaceAll("[^A-Z0-9]", "");
        String digits = q.replaceAll("\\D", "");
        String text = q.trim().toLowerCase();
        return (!plate.isEmpty() && r.plateDisplay().replace(" ", "").contains(plate))
                || r.ownerName().toLowerCase().contains(text)
                || (digits.length() >= 3 && r.phone().contains(digits));
    }

    private static LocalDate periodStart(Period period, LocalDate today) {
        return switch (period) {
            case ALL -> null;
            case YEAR -> today.withDayOfYear(1);
            case MONTH -> today.withDayOfMonth(1);
            case WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case TODAY -> today;
        };
    }

    private ServiceRecord record(ServiceCenter center, Long id) {
        return records.findByIdAndCenter(id, center).orElseThrow(() -> ApiException.notFound("Ish topilmadi"));
    }

    private ServiceCenter center(AdminPrincipal p) {
        ServiceCenter c = centers.findById(p.centerId())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "Servis topilmadi"));
        if (!c.isActive()) throw new ApiException(HttpStatus.FORBIDDEN, "Bu avtoservis o'chirilgan");
        return c;
    }

    private LocalDate today() {
        return LocalDate.now(app.zoneId());
    }
}
