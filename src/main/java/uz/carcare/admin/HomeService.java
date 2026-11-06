package uz.carcare.admin;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.carcare.config.AppProperties;
import uz.carcare.model.*;
import uz.carcare.repo.CustomerNoteRepository;
import uz.carcare.repo.PaymentRepository;
import uz.carcare.repo.ServiceCenterRepository;
import uz.carcare.repo.ServiceRecordRepository;
import uz.carcare.web.ApiException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static uz.carcare.util.Formats.prettyPlate;

/**
 * Servis bosh sahifasi (direktor va administrator): bugungi va oylik ko'rsatkichlar,
 * kunlik tushum grafigi, eng katta qarzdorlar va shu oy tug'ilgan kunlar.
 */
@Service
public class HomeService {

    public record DayRevenue(LocalDate date, long amount) {
    }

    public record TopDebtor(Long carId, String ownerName, String plateDisplay, long remaining, long overdueDays) {
    }

    public record Birthday(Long carId, String ownerName, String plateDisplay, LocalDate birthDate, boolean today,
                           boolean telegramConnected) {
    }

    public record Home(
            String centerName, LocalDate today,
            int jobsToday, int jobsMonth, long revenueToday, long revenueMonth,
            long debtRemaining, int debtors, int overdueDebtors,
            int customersTotal, int customersNewMonth, int upcoming7,
            List<DayRevenue> days, List<TopDebtor> topDebtors, List<Birthday> birthdays
    ) {
    }

    private final ServiceRecordRepository records;
    private final PaymentRepository payments;
    private final CustomerNoteRepository notes;
    private final ServiceCenterRepository centers;
    private final AppProperties app;

    public HomeService(ServiceRecordRepository records, PaymentRepository payments, CustomerNoteRepository notes,
                       ServiceCenterRepository centers, AppProperties app) {
        this.records = records;
        this.payments = payments;
        this.notes = notes;
        this.centers = centers;
        this.app = app;
    }

    @Transactional(readOnly = true)
    public Home home(AdminPrincipal p) {
        ServiceCenter center = centers.findById(p.centerId())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "Servis topilmadi"));
        LocalDate today = LocalDate.now(app.zoneId());
        LocalDate monthStart = today.withDayOfMonth(1);

        List<ServiceRecord> all = records.findAllForCenter(center);
        Map<Long, Car> customers = new HashMap<>();
        Map<Long, LocalDate> firstVisit = new HashMap<>();
        Map<Long, long[]> debtByCar = new HashMap<>(); // [qoldiq, kechikish]
        int jobsToday = 0, jobsMonth = 0, upcoming = 0;
        for (ServiceRecord r : all) {
            Car car = r.getCar();
            customers.put(car.getId(), car);
            firstVisit.merge(car.getId(), r.getDoneDate(), (a, b) -> a.isBefore(b) ? a : b);
            if (r.getDoneDate().equals(today)) jobsToday++;
            if (!r.getDoneDate().isBefore(monthStart)) jobsMonth++;
            if (r.isActive() && !r.getNextDate().isBefore(today) && !r.getNextDate().isAfter(today.plusDays(app.reminderDaysBefore()))) upcoming++;
            if (r.getRemaining() > 0) {
                long overdue = r.getPayDueDate() != null && r.getPayDueDate().isBefore(today)
                        ? ChronoUnit.DAYS.between(r.getPayDueDate(), today) : 0;
                debtByCar.merge(car.getId(), new long[]{r.getRemaining(), overdue},
                        (a, b) -> new long[]{a[0] + b[0], Math.max(a[1], b[1])});
            }
        }
        for (CustomerNote n : notes.findAllForCenter(center)) customers.putIfAbsent(n.getCar().getId(), n.getCar());

        // Tushum: shu oydagi haqiqiy pul to'lovlari, kunlar bo'yicha
        long[] perDay = new long[today.getDayOfMonth()];
        for (Payment pay : payments.findAllByCenterAndCreatedAtGreaterThanEqual(center,
                monthStart.atStartOfDay(app.zoneId()).toInstant())) {
            if (!pay.getMethod().isMoney()) continue; // bonus ball bilan to'lov — tushum emas
            LocalDate d = LocalDate.ofInstant(pay.getCreatedAt(), app.zoneId());
            if (!d.isAfter(today)) perDay[d.getDayOfMonth() - 1] += pay.getAmount();
        }
        List<DayRevenue> days = new ArrayList<>();
        long revenueMonth = 0;
        for (int i = 0; i < perDay.length; i++) {
            days.add(new DayRevenue(monthStart.plusDays(i), perDay[i]));
            revenueMonth += perDay[i];
        }

        long debtTotal = debtByCar.values().stream().mapToLong(v -> v[0]).sum();
        int overdueDebtors = (int) debtByCar.values().stream().filter(v -> v[1] > 0).count();
        List<TopDebtor> top = debtByCar.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(5)
                .map(e -> {
                    Car c = customers.get(e.getKey());
                    return new TopDebtor(c.getId(), c.getFullName().trim(), prettyPlate(c.getPlateNumber()), e.getValue()[0], e.getValue()[1]);
                })
                .toList();

        List<Birthday> birthdays = customers.values().stream()
                .filter(c -> c.getBirthDate() != null && c.getBirthDate().getMonth() == today.getMonth())
                .sorted(Comparator.comparingInt(c -> c.getBirthDate().getDayOfMonth()))
                .map(c -> new Birthday(c.getId(), c.getFullName().trim(), prettyPlate(c.getPlateNumber()), c.getBirthDate(),
                        c.getBirthDate().getDayOfMonth() == today.getDayOfMonth(), c.getTelegramChatId() != null))
                .toList();

        int newMonth = (int) firstVisit.values().stream().filter(d -> !d.isBefore(monthStart)).count();
        return new Home(center.getName(), today, jobsToday, jobsMonth, perDay[perDay.length - 1], revenueMonth,
                debtTotal, debtByCar.size(), overdueDebtors, customers.size(), newMonth, upcoming,
                days, top, birthdays);
    }
}
