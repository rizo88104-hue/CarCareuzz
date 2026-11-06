package uz.carcare.admin;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.carcare.admin.CustomerDtos.*;
import uz.carcare.config.AppProperties;
import uz.carcare.config.TelegramProperties;
import uz.carcare.loyalty.LoyaltyService;
import uz.carcare.telegram.TelegramClient;
import uz.carcare.model.*;
import uz.carcare.repo.*;
import uz.carcare.web.ApiException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static uz.carcare.util.Formats.*;

/**
 * Mijozlar bazasi.
 * Servis faqat o'ziga kelgan mijozlarni (kamida bitta ish kiritgan mashinalarni) ko'radi.
 * Super admin butun tizimdagi mashinalarni qidira oladi.
 */
@Service
public class CustomerBaseService {

    /** Ro'yxatda bir martada ko'rsatiladigan mijozlar soni (qidiruv bilan toraytiriladi). */
    private static final int LIST_LIMIT = 300;
    private static final int NEW_DAYS = 30;
    /** Shuncha kun ichida kelgan mijoz "faol" hisoblanadi. */
    private static final int ACTIVE_DAYS = 180;

    private final CarRepository cars;
    private final ServiceRecordRepository records;
    private final ServiceCenterRepository centers;
    private final CustomerNoteRepository notes;
    private final PaymentRepository payments;
    private final DebtService debts;
    private final AuditService audit;
    private final TelegramClient telegram;
    private final TelegramProperties telegramProps;
    private final AppProperties app;
    private final LoyaltyService loyalty;
    private final LoyaltyAccountRepository loyaltyAccounts;

    public CustomerBaseService(CarRepository cars, ServiceRecordRepository records, ServiceCenterRepository centers,
                               CustomerNoteRepository notes, PaymentRepository payments, DebtService debts,
                               AuditService audit, TelegramClient telegram, TelegramProperties telegramProps,
                               AppProperties app, LoyaltyService loyalty, LoyaltyAccountRepository loyaltyAccounts) {
        this.loyalty = loyalty;
        this.loyaltyAccounts = loyaltyAccounts;
        this.payments = payments;
        this.debts = debts;
        this.telegram = telegram;
        this.telegramProps = telegramProps;
        this.cars = cars;
        this.records = records;
        this.centers = centers;
        this.notes = notes;
        this.audit = audit;
        this.app = app;
    }

    // =====================================================================
    //  Servis: ro'yxat, karta, tahrirlash, eksport
    // =====================================================================

    @Transactional(readOnly = true)
    public CustomerList list(AdminPrincipal p, String q, Filter filter) {
        ServiceCenter center = center(p);
        List<CustomerRow> all = buildRows(center);

        Map<Filter, Integer> counts = new EnumMap<>(Filter.class);
        for (Filter f : Filter.values()) counts.put(f, (int) all.stream().filter(r -> matches(r, f)).count());

        Filter f = filter == null ? Filter.ALL : filter;
        List<CustomerRow> filtered = all.stream()
                .filter(r -> matches(r, f))
                .filter(r -> matchesQuery(r, q))
                .toList();
        return new CustomerList(filtered.size(), counts, filtered.stream().limit(LIST_LIMIT).toList());
    }

    @Transactional(readOnly = true)
    public CustomerDetail detail(AdminPrincipal p, Long carId) {
        ServiceCenter center = center(p);
        Car car = carOfCenter(center, carId);
        LocalDate today = today();

        List<ServiceRecord> carRecords = records.findAllByCarOrderByActiveDescNextDateAscIdDesc(car);
        List<ServiceRecord> here = carRecords.stream().filter(r -> isHere(r, center)).toList();
        CustomerNote note = notes.findByCenterAndCar(center, car).orElse(null);

        List<HistoryItem> history = here.stream()
                .sorted(Comparator.comparing(ServiceRecord::getDoneDate).reversed().thenComparing(ServiceRecord::getId, Comparator.reverseOrder()))
                .map(r -> new HistoryItem(r.getId(), r.displayName(), r.getDoneDate(), r.getNextDate(), r.getMileage(),
                        r.getMasterName(), r.getNote(), r.isActive(), r.getPrice(), r.getPaid(), r.getRemaining(),
                        r.getDiscount(), r.getBonusPaid()))
                .toList();

        // Qarzlar va to'lovlar (faqat shu servisdagi)
        List<DebtDtos.DebtRow> debtRows = here.stream().filter(ServiceRecord::isCredit)
                .map(r -> debts.row(r, today))
                .sorted(Comparator.comparing(DebtDtos.DebtRow::remaining, Comparator.reverseOrder())
                        .thenComparing(DebtDtos.DebtRow::doneDate, Comparator.reverseOrder()))
                .toList();
        long debtTotal = 0, debtPaid = 0, debtLeft = 0, maxOverdue = 0;
        int open = 0;
        for (DebtDtos.DebtRow d : debtRows) {
            debtTotal += d.price();
            debtPaid += d.paid();
            debtLeft += d.remaining();
            maxOverdue = Math.max(maxOverdue, d.overdueDays());
            if (d.remaining() > 0) open++;
        }
        List<ServiceRecord> priced = here.stream().filter(r -> r.getPrice() != null).toList();
        long spent = priced.stream().mapToLong(ServiceRecord::getPrice).sum();
        SpendTotals spend = new SpendTotals(spent, priced.isEmpty() ? 0 : spent / priced.size(), priced.size());
        List<PaymentRow> paymentRows = payments.findAllForCar(center, car).stream()
                .map(pay -> new PaymentRow(pay.getId(), pay.getCreatedAt(), pay.getAmount(), pay.getMethod().label(),
                        pay.getRecord().displayName(), pay.getReceivedBy(), pay.getNote()))
                .toList();
        String tgLink = telegramProps.deepLink(car.getToken());
        List<ActiveService> active = carRecords.stream().filter(ServiceRecord::isActive)
                .sorted(Comparator.comparing(ServiceRecord::getNextDate))
                .map(r -> new ActiveService(r.displayName(), r.getNextDate(),
                        ChronoUnit.DAYS.between(today, r.getNextDate()), isHere(r, center)))
                .toList();
        int elsewhere = (int) carRecords.stream().filter(r -> !isHere(r, center)).count();

        return new CustomerDetail(row(car, here, note, loyaltyAccounts.findByCenterAndCar(center, car).orElse(null), center, today), app.carUrl(car.getToken()), car.getCreatedAt(),
                note == null ? null : note.getUpdatedBy(), note == null ? null : note.getUpdatedAt(),
                history, active, elsewhere,
                car.getFirstName(), car.getLastName(), car.getBirthDate(),
                car.getGender() == null ? null : car.getGender().name(), car.getSource(),
                tgLink != null ? tgLink : app.carUrl(car.getToken()),
                new DebtTotals(debtTotal, debtPaid, debtLeft, maxOverdue, open), spend, debtRows, paymentRows,
                note != null && note.isBlacklisted() ? note.getBlacklistedBy() : null,
                note != null && note.isBlacklisted() ? note.getBlacklistedAt() : null);
    }

    // =====================================================================
    //  Qo'lda qo'shish, qora ro'yxat, Telegram xabar
    // =====================================================================

    /** Mijozni bazaga qo'lda qo'shish. Mashina tizimda bor bo'lsa, faqat shu servis bazasiga bog'lanadi. */
    @Transactional
    public CustomerDetail create(AdminPrincipal p, NewCustomerRequest req) {
        ServiceCenter center = center(p);
        String plate = normalizePlate(req.plateNumber());
        String phone = normalizePhone(req.phone());
        Car car = cars.findByPlateNumber(plate).orElse(null);
        boolean existed = car != null;
        if (car == null) {
            car = new Car();
            car.setToken(newToken());
            car.setFirstName(trim(req.firstName()));
            car.setLastName(req.lastName() == null ? "" : trim(req.lastName()));
            car.setPhone(phone);
            car.setPlateNumber(plate);
            car.setBirthDate(req.birthDate());
            car.setSource(req.source() == null || req.source().isBlank() ? null : req.source().trim());
            if (req.gender() != null && !req.gender().isBlank()) {
                try {
                    car.setGender(Car.Gender.valueOf(req.gender()));
                } catch (IllegalArgumentException e) {
                    throw ApiException.badRequest("Jinsi noto'g'ri tanlangan");
                }
            }
            cars.save(car);
        } else if (records.existsByCarAndCenter(car, center) || notes.findByCenterAndCar(center, car).isPresent()) {
            throw ApiException.conflict("Bu mashina allaqachon bazangizda bor");
        }
        CustomerNote note = new CustomerNote(center, car);
        if (req.note() != null && !req.note().isBlank()) note.update(req.note().trim(), p.fullName());
        notes.save(note);
        audit.staff(p, center, "Mijozni bazaga qo'shdi: " + car.getFullName().trim() + ", " + prettyPlate(plate)
                + (existed ? " (tizimda oldindan bor edi)" : ""));
        return detail(p, car.getId());
    }

    @Transactional
    public CustomerDetail setBlacklist(AdminPrincipal p, Long carId, BlacklistRequest req) {
        ServiceCenter center = center(p);
        Car car = carOfCenter(center, carId);
        CustomerNote note = notes.findByCenterAndCar(center, car).orElseGet(() -> new CustomerNote(center, car));
        String plate = prettyPlate(car.getPlateNumber());
        if (req.blacklisted()) {
            if (req.reason() == null || req.reason().isBlank()) throw ApiException.badRequest("Qora ro'yxatga qo'shish sababini yozing");
            note.blacklist(req.reason().trim(), p.fullName());
            audit.staff(p, center, "Qora ro'yxatga qo'shdi: " + plate + " — " + req.reason().trim());
        } else if (note.isBlacklisted()) {
            note.unblacklist();
            audit.staff(p, center, "Qora ro'yxatdan chiqardi: " + plate);
        }
        notes.save(note);
        return detail(p, carId);
    }

    /** Mijozga bot orqali xabar yuborish (masalan, aksiya yoki eslatma). */
    @Transactional(readOnly = true)
    public boolean sendMessage(AdminPrincipal p, Long carId, MessageRequest req) {
        ServiceCenter center = center(p);
        Car car = carOfCenter(center, carId);
        if (car.getTelegramChatId() == null) throw ApiException.badRequest("Mijoz Telegram botga ulanmagan. Qo'ng'iroq qiling: " + prettyPhone(car.getPhone()));
        boolean ok = telegram.sendMessage(car.getTelegramChatId(),
                "💬 <b>" + html(center.getName()) + "</b>\n\n" + html(req.text().trim())
                        + (center.getPhone() == null ? "" : "\n\n📞 " + prettyPhone(center.getPhone())));
        if (!ok) throw ApiException.badRequest("Xabar yetkazilmadi. Mijoz botni bloklagan bo'lishi mumkin.");
        audit.staff(p, center, prettyPlate(car.getPlateNumber()) + " egasiga Telegramda xabar yubordi");
        return true;
    }

    /**
     * «Qarzni so'ndirish»: summa mijozning shu servisdagi ochiq qarzlariga taqsimlanadi —
     * avval muddati eng erta bo'lganiga. Har bir ish uchun alohida to'lov yoziladi.
     */
    @Transactional
    public CustomerDetail payDebts(AdminPrincipal p, Long carId, AdminDtos.PaymentRequest req) {
        ServiceCenter center = center(p);
        Car car = carOfCenter(center, carId);
        List<ServiceRecord> open = records.findAllByCarOrderByActiveDescNextDateAscIdDesc(car).stream()
                .filter(r -> isHere(r, center) && r.getRemaining() > 0)
                .sorted(Comparator.comparing(ServiceRecord::getPayDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(ServiceRecord::getDoneDate))
                .toList();
        long total = open.stream().mapToLong(ServiceRecord::getRemaining).sum();
        if (total == 0) throw ApiException.badRequest("Bu mijozda qarz yo'q");
        if (req.amount() > total) throw ApiException.badRequest("Summa umumiy qarzdan (" + money(total) + ") oshmasin");

        Payment.Method method = req.method() == null ? Payment.Method.CASH : req.method();
        long left = req.amount();
        int closed = 0;
        LoyaltyService.Earn earn = null;
        if (method == Payment.Method.BONUS) {
            // Ball bilan: har bir ishda ko'pi bilan narxning belgilangan foizi
            LoyaltyProgram prog = loyalty.activeProgram(center);
            LoyaltyAccount acc = loyalty.account(center, car).orElse(null);
            if (prog == null) throw ApiException.badRequest("Sadoqat dasturi yoqilmagan — ball bilan to'lab bo'lmaydi");
            if (acc == null) throw ApiException.badRequest("Mijozda jamg'arma karta yo'q");
            long capTotal = open.stream().mapToLong(r -> Math.max(0, Math.min(r.getRemaining(),
                    LoyaltyService.percent(r.getPrice(), prog.getMaxRedeemPct()) - r.getBonusPaid()))).sum();
            long allowed = Math.min(acc.getBalance(), capTotal);
            if (req.amount() > allowed) {
                throw ApiException.badRequest("Ball bilan ko'pi bilan " + money(allowed) + " to'lash mumkin (balans "
                        + points(acc.getBalance()) + ", har bir ish narxining " + prog.getMaxRedeemPct() + "% gacha)");
            }
            for (ServiceRecord r : open) {
                if (left == 0) break;
                long part = Math.min(left, loyalty.redeemCap(prog, acc, r));
                if (part <= 0) continue;
                loyalty.redeem(center, r, part, p.fullName(), trim(req.note()));
                left -= part;
                if (r.getRemaining() == 0) closed++;
            }
        } else {
            long earned = 0;
            for (ServiceRecord r : open) {
                if (left == 0) break;
                long part = Math.min(left, r.getRemaining());
                payments.save(new Payment(r, center, part, method, p.fullName(), trim(req.note())));
                r.addPaid(part);
                LoyaltyService.Earn e = loyalty.onMoneyPayment(center, r, part, p.fullName());
                if (e != null) {
                    earned += e.points();
                    earn = new LoyaltyService.Earn(earned, e.balance(), e.level(), e.levelUp() || (earn != null && earn.levelUp()), e.cashbackPct());
                }
                left -= part;
                if (r.getRemaining() == 0) closed++;
            }
        }
        long rest = total - req.amount();
        String plate = prettyPlate(car.getPlateNumber());
        audit.staff(p, center, plate + " — qarzni so'ndirish: " + money(req.amount()) + " (" + method.label().toLowerCase() + ")"
                + (closed > 0 ? ", " + closed + " ta qarz yopildi" : "") + (rest > 0 ? ", qoldiq " + money(rest) : ", qarz to'liq yopildi"));
        if (car.getTelegramChatId() != null) {
            telegram.sendMessage(car.getTelegramChatId(),
                    "🧾 <b>To'lov qabul qilindi</b>\n\n🚗 <b>" + html(plate) + "</b>\n"
                            + "💵 To'landi: <b>" + money(req.amount()) + "</b> (" + method.label().toLowerCase() + ")\n"
                            + (rest == 0 ? "✅ Qarz to'liq yopildi. Rahmat!" : "📌 Qolgan qarz: <b>" + money(rest) + "</b>")
                            + LoyaltyService.receiptLine(earn)
                            + "\n\n📍 " + html(center.getName()));
        }
        return detail(p, carId);
    }

    @Transactional
    public CustomerDetail update(AdminPrincipal p, Long carId, CustomerUpdate req) {
        ServiceCenter center = center(p);
        Car car = carOfCenter(center, carId);
        String plate = prettyPlate(car.getPlateNumber());
        List<String> changes = new ArrayList<>();

        if (req.firstName() != null && !req.firstName().isBlank() && !req.firstName().trim().equals(car.getFirstName())) {
            car.setFirstName(trim(req.firstName()));
            changes.add("ism");
        }
        if (req.lastName() != null && !req.lastName().trim().equals(car.getLastName())) {
            car.setLastName(trim(req.lastName()));
            changes.add("familiya");
        }
        if (req.phone() != null && !req.phone().isBlank()) {
            String phone = normalizePhone(req.phone());
            if (!phone.equals(car.getPhone())) {
                car.setPhone(phone);
                changes.add("telefon");
            }
        }
        if (req.profileEdit()) {
            if (!Objects.equals(req.birthDate(), car.getBirthDate())) {
                if (req.birthDate() != null && req.birthDate().isAfter(today())) throw ApiException.badRequest("Tug'ilgan kun kelajakda bo'lishi mumkin emas");
                car.setBirthDate(req.birthDate());
                changes.add("tug'ilgan kun");
            }
            Car.Gender g;
            try {
                g = req.gender() == null || req.gender().isBlank() ? null : Car.Gender.valueOf(req.gender());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("Jinsi noto'g'ri tanlangan");
            }
            if (g != car.getGender()) {
                car.setGender(g);
                changes.add("jinsi");
            }
            String src = req.source() == null || req.source().isBlank() ? null : req.source().trim();
            if (!Objects.equals(src, car.getSource())) {
                car.setSource(src);
                changes.add("qayerdan kelgan");
            }
        }
        if (!changes.isEmpty()) {
            audit.staff(p, center, "Mijoz ma'lumotini o'zgartirdi (" + String.join(", ", changes) + "): " + plate);
        }

        if (req.note() != null) {
            String text = req.note().trim();
            CustomerNote note = notes.findByCenterAndCar(center, car).orElseGet(() -> new CustomerNote(center, car));
            if (!text.equals(note.getText() == null ? "" : note.getText())) {
                note.update(text.isEmpty() ? null : text, p.fullName());
                notes.save(note);
                audit.staff(p, center, (text.isEmpty() ? "Mijoz izohini o'chirdi: " : "Mijozga izoh yozdi: ") + plate);
            }
        }
        return detail(p, carId);
    }

    /** Excel ochadigan CSV: UTF-8 BOM bilan, ";" ajratgich (rus/o'zbek Excel sozlamalari uchun). */
    @Transactional(readOnly = true)
    public byte[] exportCsv(AdminPrincipal p) {
        ServiceCenter center = center(p);
        List<CustomerRow> rows = buildRows(center);
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("Mashina raqami;Egasi;Telefon;Telegram;Tashriflar;Ishlar;Birinchi tashrif;Oxirgi tashrif;Keyingi servis;Keyingi sana;Holat;Tug'ilgan kun;Sadoqat darajasi;Ballar;Qora ro'yxat;Izoh\n");
        for (CustomerRow r : rows) {
            sb.append(csv(r.plateDisplay())).append(';')
              .append(csv(r.ownerName())).append(';')
              .append(csv(r.phoneDisplay())).append(';')
              .append(r.telegramConnected() ? "Ha" : "Yo'q").append(';')
              .append(r.visits()).append(';')
              .append(r.jobs()).append(';')
              .append(date(r.firstVisit())).append(';')
              .append(date(r.lastVisit())).append(';')
              .append(csv(r.nextService())).append(';')
              .append(date(r.nextDate())).append(';')
              .append(statusLabel(r.status())).append(';')
              .append(date(r.birthDate())).append(';')
              .append(r.loyaltyLevel() == null ? "" : uz.carcare.model.LoyaltyLevel.valueOf(r.loyaltyLevel()).label()).append(';')
              .append(r.points() == null ? "" : String.valueOf(r.points())).append(';')
              .append(r.blacklisted() ? csv("Ha: " + r.blacklistReason()) : "").append(';')
              .append(csv(r.note())).append('\n');
        }
        audit.staff(p, center, "Mijozlar bazasini yuklab oldi (" + rows.size() + " ta)");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // =====================================================================
    //  Super admin: butun tizim bo'yicha qidiruv
    // =====================================================================

    @Transactional(readOnly = true)
    public GlobalCustomerList globalSearch(String q) {
        PageRequest page = PageRequest.of(0, 200);
        List<Car> found;
        if (q == null || q.isBlank()) {
            found = cars.findLatest(page);
        } else {
            String plate = "%" + q.toUpperCase().replaceAll("[^A-Z0-9]", "") + "%";
            String name = "%" + q.trim().toLowerCase() + "%";
            String digits = q.replaceAll("\\D", "");
            String phone = digits.length() >= 3 ? "%" + digits + "%" : "\u0000"; // juda qisqa raqam bo'yicha qidirmaymiz
            found = cars.search(plate.equals("%%") ? "\u0000" : plate, name, phone, page);
        }
        Map<Long, List<String>> centersByCar = new HashMap<>();
        if (!found.isEmpty()) {
            for (Object[] row : records.findCenterNamesForCars(found)) {
                centersByCar.computeIfAbsent((Long) row[0], k -> new ArrayList<>()).add((String) row[1]);
            }
        }
        List<GlobalCustomerRow> rows = found.stream().map(c -> new GlobalCustomerRow(
                c.getId(), prettyPlate(c.getPlateNumber()), c.getFullName().trim(), c.getPhone(), prettyPhone(c.getPhone()),
                c.getTelegramChatId() != null, c.getCreatedAt(),
                centersByCar.getOrDefault(c.getId(), List.of()).stream().sorted().toList()
        )).toList();
        return new GlobalCustomerList(cars.count(), cars.countByTelegramChatIdIsNotNull(), rows);
    }

    // =====================================================================
    //  Yordamchi
    // =====================================================================

    /** Servisga kelgan har bir mashina uchun bitta qator. Oxirgi kelganlar birinchi. */
    private List<CustomerRow> buildRows(ServiceCenter center) {
        LocalDate today = today();
        Map<Long, List<ServiceRecord>> byCar = new LinkedHashMap<>();
        Map<Long, Car> carById = new HashMap<>();
        for (ServiceRecord r : records.findAllForCenter(center)) {
            byCar.computeIfAbsent(r.getCar().getId(), k -> new ArrayList<>()).add(r);
            carById.putIfAbsent(r.getCar().getId(), r.getCar());
        }
        Map<Long, CustomerNote> noteByCar = notes.findAllForCenter(center).stream()
                .collect(Collectors.toMap(n -> n.getCar().getId(), n -> n, (a, b) -> a));
        // Qo'lda qo'shilgan (hali ish kiritilmagan) mijozlar
        for (CustomerNote n : noteByCar.values()) {
            if (!byCar.containsKey(n.getCar().getId())) {
                byCar.put(n.getCar().getId(), new ArrayList<>());
                carById.put(n.getCar().getId(), n.getCar());
            }
        }
        Map<Long, LoyaltyAccount> cardByCar = loyaltyAccounts.findAllForCenter(center).stream()
                .collect(Collectors.toMap(a -> a.getCar().getId(), a -> a, (a, b) -> a));
        return byCar.entrySet().stream()
                .map(e -> row(carById.get(e.getKey()), e.getValue(), noteByCar.get(e.getKey()), cardByCar.get(e.getKey()), center, today))
                .sorted(Comparator.comparing(CustomerRow::lastVisit, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /** here — shu servis kiritgan yozuvlar. */
    private CustomerRow row(Car car, List<ServiceRecord> here, CustomerNote note, LoyaltyAccount card,
                            ServiceCenter center, LocalDate today) {
        LocalDate first = here.stream().map(ServiceRecord::getDoneDate).min(Comparator.naturalOrder()).orElse(null);
        LocalDate last = here.stream().map(ServiceRecord::getDoneDate).max(Comparator.naturalOrder()).orElse(null);
        int visits = (int) here.stream().map(ServiceRecord::getDoneDate).distinct().count(); // bir kunda bir nechta ish = 1 tashrif

        Optional<ServiceRecord> next = here.stream().filter(ServiceRecord::isActive)
                .min(Comparator.comparing(ServiceRecord::getNextDate));
        Long daysLeft = next.map(r -> ChronoUnit.DAYS.between(today, r.getNextDate())).orElse(null);

        Status status;
        if (daysLeft != null && daysLeft < 0) status = Status.OVERDUE;
        else if (daysLeft != null && daysLeft <= app.reminderDaysBefore()) status = Status.SOON;
        else if (next.isEmpty() && here.stream().anyMatch(r -> r.getReplacedAt() != null && !isReplacedHere(r, center))) status = Status.LOST;
        else status = Status.OK;

        return new CustomerRow(car.getId(), car.getPlateNumber(), prettyPlate(car.getPlateNumber()), car.getFullName().trim(),
                car.getPhone(), prettyPhone(car.getPhone()), car.getTelegramChatId() != null,
                visits, here.size(), first, last,
                next.map(ServiceRecord::displayName).orElse(null), next.map(ServiceRecord::getNextDate).orElse(null),
                daysLeft, status, note == null ? null : note.getText(),
                car.getBirthDate(), note != null && note.isBlacklisted(), note != null && note.isBlacklisted() ? note.getBlacklistReason() : null,
                card == null ? null : card.getLevel().name(), card == null ? null : card.getLevel().icon(),
                card == null ? null : card.getBalance());
    }

    private boolean matches(CustomerRow r, Filter f) {
        return switch (f) {
            case ALL -> true;
            case OVERDUE -> r.status() == Status.OVERDUE;
            case SOON -> r.status() == Status.SOON;
            case LOST -> r.status() == Status.LOST;
            case NO_TELEGRAM -> !r.telegramConnected();
            case NEW -> r.firstVisit() != null && !r.firstVisit().isBefore(today().minusDays(NEW_DAYS));
            case ACTIVE -> r.lastVisit() != null && !r.lastVisit().isBefore(today().minusDays(ACTIVE_DAYS));
            case INACTIVE -> r.lastVisit() == null || r.lastVisit().isBefore(today().minusDays(ACTIVE_DAYS));
            case REPEAT -> r.visits() >= 2;
            case BLACKLIST -> r.blacklisted();
            case BIRTHDAY -> r.birthDate() != null && r.birthDate().getMonth() == today().getMonth();
        };
    }

    private static boolean matchesQuery(CustomerRow r, String q) {
        if (q == null || q.isBlank()) return true;
        String plate = q.toUpperCase().replaceAll("[^A-Z0-9]", "");
        String digits = q.replaceAll("\\D", "");
        String text = q.trim().toLowerCase();
        return (!plate.isEmpty() && r.plate().contains(plate))
                || r.ownerName().toLowerCase().contains(text)
                || (digits.length() >= 3 && r.phone().contains(digits))
                || (r.note() != null && r.note().toLowerCase().contains(text));
    }

    private static boolean isHere(ServiceRecord r, ServiceCenter center) {
        return r.getCenter() != null && r.getCenter().getId().equals(center.getId());
    }

    private static boolean isReplacedHere(ServiceRecord r, ServiceCenter center) {
        return r.getReplacedByCenter() != null && r.getReplacedByCenter().getId().equals(center.getId());
    }

    private static String statusLabel(Status s) {
        return switch (s) {
            case OVERDUE -> "Muddati o'tgan";
            case SOON -> "Tez orada keladi";
            case LOST -> "Boshqa joyga ketgan";
            case OK -> "Faol";
        };
    }

    private static String csv(String s) {
        if (s == null) return "";
        String v = s.replace("\r", " ").replace("\n", " ");
        // Excel formulasi sifatida ochilmasligi uchun (=, +, -, @ bilan boshlansa)
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        return v.contains(";") || v.contains("\"") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    /** Mashina shu servisning mijozi bo'lishi shart (kamida bitta ish kiritilgan). */
    private Car carOfCenter(ServiceCenter center, Long carId) {
        Car car = cars.findById(carId).orElseThrow(() -> ApiException.notFound("Mijoz topilmadi"));
        if (!records.existsByCarAndCenter(car, center) && notes.findByCenterAndCar(center, car).isEmpty()) {
            throw ApiException.notFound("Mijoz topilmadi");
        }
        return car;
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
