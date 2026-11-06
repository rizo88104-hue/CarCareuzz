package uz.carcare.admin;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.carcare.admin.AdminDtos.*;
import uz.carcare.config.AppProperties;
import uz.carcare.loyalty.LoyaltyService;
import uz.carcare.model.*;
import uz.carcare.model.StaffUser.Role;
import uz.carcare.reminder.ReminderService;
import uz.carcare.repo.*;
import uz.carcare.telegram.TelegramClient;
import uz.carcare.web.ApiException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static uz.carcare.util.Formats.*;

@Service
public class AdminService {

    /** Shuncha kun hech narsa kiritmagan servis "faol emas" deb belgilanadi. */
    private static final int INACTIVE_AFTER_DAYS = 14;
    /** Jurnaldan bir martada ko'rsatiladigan yozuvlar soni. */
    private static final int LOG_LIMIT = 300;

    private final CarRepository cars;
    private final ServiceRecordRepository records;
    private final ServiceCenterRepository centers;
    private final StaffUserRepository staff;
    private final AdminSessionRepository sessions;
    private final ActivityLogRepository logs;
    private final PaymentRepository payments;
    private final CustomerNoteRepository customerNotes;
    private final AdminAuthService auth;
    private final AuditService audit;
    private final ReminderService reminders;
    private final TelegramClient telegram;
    private final AppProperties app;
    private final LoyaltyService loyalty;

    public AdminService(CarRepository cars, ServiceRecordRepository records, ServiceCenterRepository centers,
                        StaffUserRepository staff, AdminSessionRepository sessions, ActivityLogRepository logs,
                        PaymentRepository payments, CustomerNoteRepository customerNotes,
                        AdminAuthService auth, AuditService audit, ReminderService reminders,
                        TelegramClient telegram, AppProperties app, LoyaltyService loyalty) {
        this.loyalty = loyalty;
        this.payments = payments;
        this.customerNotes = customerNotes;
        this.cars = cars;
        this.records = records;
        this.centers = centers;
        this.staff = staff;
        this.sessions = sessions;
        this.logs = logs;
        this.auth = auth;
        this.audit = audit;
        this.reminders = reminders;
        this.telegram = telegram;
        this.app = app;
    }

    // =====================================================================
    //  Servis xodimlari: ish kiritish
    // =====================================================================

    @Transactional(readOnly = true)
    public CarLookupDto lookup(AdminPrincipal p, String plateRaw) {
        String plate = normalizePlate(plateRaw);
        boolean showPhone = p.canContactCustomers();
        ServiceCenter center = center(p);
        return cars.findByPlateNumber(plate).map(car -> new CarLookupDto(true, plate, prettyPlate(plate),
                        car.getFirstName(), car.getLastName(),
                        showPhone ? car.getPhone() : null, showPhone ? prettyPhone(car.getPhone()) : null,
                        car.getTelegramChatId() != null,
                        records.findAllByCarAndActiveTrueOrderByNextDateAsc(car).stream()
                                .map(r -> r.displayName() + " — " + date(r.getNextDate())).toList(),
                        customerNotes.findByCenterAndCar(center, car)
                                .filter(CustomerNote::isBlacklisted).map(CustomerNote::getBlacklistReason).orElse(null),
                        showPhone ? loyalty.lookup(center, car) : null))
                .orElseGet(() -> new CarLookupDto(false, plate, prettyPlate(plate), null, null, null, null, false, List.of(), null,
                        showPhone ? loyalty.lookup(center, null) : null));
    }

    @Transactional
    public SaveResult addRecord(AdminPrincipal p, AdminRecordRequest req) {
        ServiceCenter center = center(p);
        LocalDate today = today();

        if (req.nextDate().isBefore(req.doneDate())) {
            throw ApiException.badRequest("Keyingi sana bajarilgan sanadan oldin bo'lishi mumkin emas");
        }
        if (req.doneDate().isAfter(today)) {
            throw ApiException.badRequest("Bajarilgan sana kelajakda bo'lishi mumkin emas");
        }
        if (req.type() == ServiceType.OTHER && (req.customName() == null || req.customName().isBlank())) {
            throw ApiException.badRequest("\"Boshqa\" turi uchun nomini yozing");
        }

        // Ishni kim qilgani: usta o'zi, direktor/administrator esa ustani tanlaydi
        String masterName = p.fullName();
        if (!p.isMaster() && req.masterId() != null) {
            StaffUser m = staff.findByIdAndCenter(req.masterId(), center)
                    .filter(s -> !s.isBlocked())
                    .orElseThrow(() -> ApiException.badRequest("Tanlangan usta topilmadi"));
            masterName = m.displayName();
        }

        String plate = normalizePlate(req.plateNumber());
        Car car = cars.findByPlateNumber(plate).orElse(null);

        // Narx va to'lov: faqat direktor va administrator kiritadi.
        // req.price — chegirmagacha narx; sadoqat chegirmasi bo'lsa, yozuvga chegirmadan keyingi narx yoziladi.
        boolean money = !p.isMaster() && req.price() != null && req.price() > 0;
        long listPrice = money ? req.price() : 0;
        int discountPct = money && !Boolean.FALSE.equals(req.applyDiscount()) ? loyalty.discountPct(center, car) : 0;
        long discount = LoyaltyService.percent(listPrice, discountPct);
        long price = listPrice - discount;
        long bonusUse = money && req.bonus() != null ? req.bonus() : 0;
        if (bonusUse > price) throw ApiException.badRequest("Ball bilan to'lov narxdan oshmasin");
        long paidNow = money ? (req.paid() == null ? price - bonusUse : req.paid()) : 0;
        if (money) {
            if (paidNow + bonusUse > price) {
                throw ApiException.badRequest(bonusUse > 0 ? "To'lov va ball birgalikda narxdan (" + money(price) + ") oshmasin"
                        : "To'langan summa narxdan oshmasin");
            }
            if (paidNow + bonusUse < price) {
                if (req.payDueDate() == null) throw ApiException.badRequest("Qarz qolyapti — to'lash muddatini kiriting");
                if (req.payDueDate().isBefore(today)) throw ApiException.badRequest("To'lash muddati o'tgan sana bo'lishi mumkin emas");
            }
        }

        boolean created = false;
        if (car == null) {
            if (req.firstName() == null || req.firstName().isBlank()) {
                throw ApiException.badRequest("Yangi mijoz: ismini kiriting");
            }
            car = new Car();
            car.setToken(newToken());
            car.setFirstName(trim(req.firstName()));
            car.setLastName(req.lastName() == null ? "" : trim(req.lastName()));
            car.setPhone(normalizePhone(req.phone()));
            car.setPlateNumber(plate);
            cars.save(car);
            created = true;
            audit.staff(p, center, "Yangi mijoz qo'shdi: " + car.getFullName().trim() + ", " + prettyPlate(plate));
        }

        if (req.type() != ServiceType.OTHER) {
            for (ServiceRecord old : records.findAllByCarAndTypeAndActiveTrue(car, req.type())) {
                old.markReplaced(center);
            }
        }

        ServiceRecord r = new ServiceRecord();
        r.setCar(car);
        r.setCenter(center);
        r.setType(req.type());
        r.setCustomName(req.type() == ServiceType.OTHER ? trim(req.customName()) : null);
        r.setDoneDate(req.doneDate());
        r.setNextDate(req.nextDate());
        r.setMileage(req.mileage());
        r.setNextMileage(req.nextMileage());
        r.setNote(trim(req.note()));
        r.setMasterName(masterName);
        if (money) {
            r.setPrice(price);
            r.setDiscount(discount);
            if (paidNow + bonusUse < price) {
                r.setCredit(true);
                r.setPayDueDate(req.payDueDate());
            }
        }
        records.save(r);

        // Sadoqat: tashrif (karta ochiladi), ball bilan to'lov, keyin pul to'lovi va keshbek
        loyalty.onVisit(center, car, p.fullName());
        if (bonusUse > 0) loyalty.redeem(center, r, bonusUse, p.fullName(), "Ish kiritilganda");
        Payment.Method method = req.paymentMethod() == null || req.paymentMethod() == Payment.Method.BONUS
                ? Payment.Method.CASH : req.paymentMethod();
        LoyaltyService.Earn earn = null;
        if (money && paidNow > 0) {
            payments.save(new Payment(r, center, paidNow, method, p.fullName(), "Ish kiritilganda"));
            r.addPaid(paidNow);
            earn = loyalty.onMoneyPayment(center, r, paidNow, p.fullName());
        }
        reminders.checkNow(r);
        audit.staff(p, center, prettyPlate(plate) + " — " + r.displayName() + " kiritdi (usta: " + masterName + ")"
                + (money ? ", narx " + money(price) : "")
                + (discount > 0 ? " (chegirma " + discountPct + "%: −" + money(discount) + ")" : "")
                + (bonusUse > 0 ? ", ball bilan " + money(bonusUse) : "")
                + (money && r.getRemaining() > 0 ? ", qarz " + money(r.getRemaining()) : ""));

        // Sadoqat ishtirok etgan bo'lsa — mijozga kvitansiya
        boolean loyaltyUsed = discount > 0 || bonusUse > 0 || (earn != null && earn.points() > 0);
        if (money && loyaltyUsed && car.getTelegramChatId() != null) {
            telegram.sendMessage(car.getTelegramChatId(),
                    "🧾 <b>To'lov</b>\n\n🚗 <b>" + html(prettyPlate(plate)) + "</b> · " + html(r.displayName()) + "\n"
                            + (discount > 0 ? "💰 Narx: " + money(listPrice) + " − chegirma " + discountPct + "% = <b>" + money(price) + "</b>\n"
                            : "💰 Narx: <b>" + money(price) + "</b>\n")
                            + (bonusUse > 0 ? "🎁 Ball bilan: <b>" + money(bonusUse) + "</b>\n" : "")
                            + (paidNow > 0 ? "💵 To'landi: <b>" + money(paidNow) + "</b> (" + method.label().toLowerCase() + ")\n" : "")
                            + (r.getRemaining() > 0 ? "📌 Qarz: <b>" + money(r.getRemaining()) + "</b>, muddat " + date(r.getPayDueDate()) + "\n" : "")
                            + LoyaltyService.receiptLine(earn)
                            + "\n\n📍 " + html(center.getName()));
        }

        Boolean delivered = null;
        String msg = created
                ? "Yangi mijoz qo'shildi. Eslatmalar kelishi uchun unga sahifa havolasini yuboring — u yerdan Telegramga ulanadi."
                : "Saqlandi. Mijoz sahifasida ko'rinadi.";
        if (earn != null && earn.points() > 0) {
            msg += " Keshbek: +" + points(earn.points()) + " (balans " + points(earn.balance()) + ")."
                    + (earn.levelUp() ? " Mijoz " + earn.level().label() + " darajaga ko'tarildi!" : "");
        }
        if (p.isMaster()) {
            msg = "Saqlandi. Mijozga xabarni administrator yuboradi.";
        } else if (req.notifyReady()) {
            delivered = deliverReady(r, center);
            if (delivered) audit.staff(p, center, prettyPlate(plate) + " egasiga «Tayyor, keling» yubordi");
            msg += delivered ? " \"Tayyor, keling\" xabari Telegramga yuborildi."
                    : " Mijoz Telegramga ulanmagan — qo'ng'iroq qiling: " + prettyPhone(car.getPhone());
        }
        return new SaveResult(toDto(r, today, p.canContactCustomers()), created, delivered, msg);
    }

    @Transactional
    public ReadyResult sendReady(AdminPrincipal p, Long recordId) {
        ServiceCenter center = center(p);
        ServiceRecord r = records.findById(recordId)
                .filter(x -> x.getCenter() != null && x.getCenter().getId().equals(center.getId()))
                .orElseThrow(() -> ApiException.notFound("Yozuv topilmadi"));
        if (r.getReadyNotifiedAt() != null
                && Duration.between(r.getReadyNotifiedAt(), Instant.now()).toMinutes() < 2) {
            throw ApiException.badRequest("Xabar hozirgina yuborilgan. Biroz kuting.");
        }
        Car car = r.getCar();
        boolean ok = deliverReady(r, center);
        if (ok) audit.staff(p, center, prettyPlate(car.getPlateNumber()) + " egasiga «Tayyor, keling» yubordi");
        return new ReadyResult(ok, prettyPhone(car.getPhone()), car.getPhone(),
                ok ? "Xabar Telegramga yuborildi" : "Mijoz Telegramga ulanmagan. Qo'ng'iroq qiling.");
    }

    @Transactional(readOnly = true)
    public CenterDashboard dashboard(AdminPrincipal p) {
        ServiceCenter center = center(p);
        LocalDate today = today();
        List<ServiceRecord> all = records.findAllForCenter(center);

        if (p.isMaster()) {
            // Usta faqat o'zi kiritgan ishlarni ko'radi, telefon va statistikasiz
            List<AdminRecordDto> mine = all.stream()
                    .filter(r -> p.fullName().equals(r.getMasterName()))
                    .limit(30).map(r -> toDto(r, today, false)).toList();
            return new CenterDashboard(center.getName(), p.role().name(), null, List.of(), mine, List.of());
        }

        List<AdminRecordDto> callList = all.stream()
                .filter(r -> r.isActive() && !r.getNextDate().isAfter(today.plusDays(app.reminderDaysBefore())))
                .sorted(Comparator.comparing(ServiceRecord::getNextDate))
                .limit(100)
                .map(r -> toDto(r, today, true))
                .toList();
        List<AdminRecordDto> recent = all.stream().limit(30).map(r -> toDto(r, today, true)).toList();
        List<StaffBrief> masters = staff.findAllByCenterOrderByIdAsc(center).stream()
                .filter(s -> s.getRole() == Role.MASTER && !s.isBlocked())
                .map(s -> new StaffBrief(s.getId(), s.displayName()))
                .toList();
        return new CenterDashboard(center.getName(), p.role().name(), stats(center, all, today), callList, recent, masters);
    }

    // =====================================================================
    //  Direktor: xodimlar va jurnal
    // =====================================================================

    @Transactional(readOnly = true)
    public List<StaffDto> listStaff(AdminPrincipal p) {
        return staff.findAllByCenterOrderByIdAsc(center(p)).stream().map(this::staffDto).toList();
    }

    @Transactional
    public StaffDto addStaff(AdminPrincipal p, StaffRequest req) {
        ServiceCenter center = center(p);
        if (req.role() != Role.ADMINISTRATOR && req.role() != Role.MASTER) {
            throw ApiException.badRequest("Faqat administrator yoki usta qo'shish mumkin");
        }
        String username = req.username().trim().toLowerCase();
        if (staff.existsByUsername(username)) throw ApiException.conflict("Bu login band. Boshqasini tanlang.");
        AdminAuthService.validatePassword(req.password());

        StaffUser u = new StaffUser();
        u.setUsername(username);
        u.setFullName(trim(req.fullName()));
        u.setPasswordHash(auth.hash(req.password()));
        u.setRole(req.role());
        u.setCenter(center);
        staff.save(u);
        audit.staff(p, center, "Yangi xodim qo'shdi: " + u.displayName() + " (" + req.role().label().toLowerCase() + ")");
        return staffDto(u);
    }

    @Transactional
    public StaffDto updateStaff(AdminPrincipal p, Long id, StaffUpdateRequest req) {
        ServiceCenter center = center(p);
        StaffUser u = staff.findByIdAndCenter(id, center).orElseThrow(() -> ApiException.notFound("Xodim topilmadi"));
        if (u.getId().equals(p.staffId())) throw ApiException.badRequest("O'zingizni o'zgartira olmaysiz. Parolni «Parol» tugmasi orqali almashtiring.");
        if (u.getRole() == Role.DIRECTOR) throw ApiException.badRequest("Direktorni faqat super admin o'zgartira oladi");

        if (req.blocked() != null && req.blocked() != u.isBlocked()) {
            u.setBlocked(req.blocked());
            if (req.blocked()) sessions.deleteAllByStaff(u); // darhol barcha qurilmalardan chiqariladi
            audit.staff(p, center, u.displayName() + (req.blocked() ? "ni blokladi" : "ni faollashtirdi"));
        }
        if (req.newPassword() != null && !req.newPassword().isBlank()) {
            AdminAuthService.validatePassword(req.newPassword());
            u.setPasswordHash(auth.hash(req.newPassword()));
            sessions.deleteAllByStaff(u);
            audit.staff(p, center, u.displayName() + " parolini tikladi");
        }
        return staffDto(u);
    }

    @Transactional(readOnly = true)
    public List<LogDto> centerLog(AdminPrincipal p) {
        return logs.findRecentForCenter(center(p), PageRequest.of(0, LOG_LIMIT)).stream().map(this::logDto).toList();
    }

    // =====================================================================
    //  Super admin
    // =====================================================================

    @Transactional(readOnly = true)
    public SuperOverview overview() {
        LocalDate today = today();
        List<CenterDto> list = new ArrayList<>();
        int total = 0, due = 0, returned = 0, active = 0;
        for (ServiceCenter c : centers.findAllByOrderByNameAsc()) {
            CenterDto dto = centerDto(c, records.findAllForCenter(c), today);
            list.add(dto);
            total += dto.stats().totalRecords();
            due += dto.stats().due();
            returned += dto.stats().returned();
            if ("Faol".equals(dto.status())) active++;
        }
        list.sort(Comparator.comparing((CenterDto d) -> statusOrder(d.status())).thenComparing(CenterDto::name));
        return new SuperOverview(list.size(), active, total, due > 0 ? Math.round(returned * 100f / due) : null, list);
    }

    @Transactional
    public CenterDto createCenter(AdminPrincipal p, CenterRequest req) {
        String username = req.username().trim().toLowerCase();
        if (staff.existsByUsername(username)) throw ApiException.conflict("Bu login band. Boshqasini tanlang.");
        AdminAuthService.validatePassword(req.password());

        ServiceCenter c = new ServiceCenter();
        c.setName(trim(req.name()));
        c.setAddress(trim(req.address()));
        c.setPhone(req.phone() == null || req.phone().isBlank() ? null : normalizePhone(req.phone()));
        centers.save(c);

        StaffUser u = new StaffUser();
        u.setUsername(username);
        u.setFullName(trim(req.directorName()));
        u.setPasswordHash(auth.hash(req.password()));
        u.setRole(Role.DIRECTOR);
        u.setCenter(c);
        staff.save(u);
        audit.staff(p, c, "Yangi servis qo'shdi: «" + c.getName() + "», direktor " + u.displayName() + " (" + username + ")");
        return centerDto(c, List.of(), today());
    }

    @Transactional
    public CenterDto updateCenter(AdminPrincipal p, Long id, CenterUpdateRequest req) {
        ServiceCenter c = centers.findById(id).orElseThrow(() -> ApiException.notFound("Servis topilmadi"));
        if (req.name() != null && !req.name().isBlank()) c.setName(trim(req.name()));
        if (req.address() != null) c.setAddress(trim(req.address()));
        if (req.phone() != null) c.setPhone(req.phone().isBlank() ? null : normalizePhone(req.phone()));
        if (req.active() != null && req.active() != c.isActive()) {
            c.setActive(req.active());
            if (!req.active()) staff.findAllByCenterOrderByIdAsc(c).forEach(sessions::deleteAllByStaff); // hammasi chiqariladi
            audit.staff(p, c, "«" + c.getName() + "» servisini " + (req.active() ? "yoqdi" : "o'chirdi"));
        }
        if (req.newPassword() != null && !req.newPassword().isBlank()) {
            AdminAuthService.validatePassword(req.newPassword());
            StaffUser director = staff.findFirstByCenterAndRoleOrderByIdAsc(c, Role.DIRECTOR)
                    .orElseThrow(() -> ApiException.notFound("Servis direktori topilmadi"));
            director.setPasswordHash(auth.hash(req.newPassword()));
            sessions.deleteAllByStaff(director);
            audit.staff(p, c, "«" + c.getName() + "» direktori parolini tikladi");
        }
        return centerDto(c, records.findAllForCenter(c), today());
    }

    /** Super admin jurnali: hammasi yoki bitta servis bo'yicha. */
    @Transactional(readOnly = true)
    public List<LogDto> superLog(Long centerId) {
        if (centerId == null) {
            return logs.findRecent(PageRequest.of(0, LOG_LIMIT)).stream().map(this::logDto).toList();
        }
        ServiceCenter c = centers.findById(centerId).orElseThrow(() -> ApiException.notFound("Servis topilmadi"));
        return logs.findRecentForCenter(c, PageRequest.of(0, LOG_LIMIT)).stream().map(this::logDto).toList();
    }

    /** Shu servisdan keyin qaytib kelmagan mijozlar va sababi. */
    @Transactional(readOnly = true)
    public List<MissedDto> missed(Long centerId) {
        ServiceCenter c = centers.findById(centerId).orElseThrow(() -> ApiException.notFound("Servis topilmadi"));
        LocalDate today = today();
        List<MissedDto> out = new ArrayList<>();
        for (ServiceRecord r : records.findAllForCenter(c)) {
            Car car = r.getCar();
            long overdue = ChronoUnit.DAYS.between(r.getNextDate(), today);
            String reason, hint;
            if (r.getReplacedAt() != null) {
                ServiceCenter by = r.getReplacedByCenter();
                if (by != null && by.getId().equals(c.getId())) continue; // qaytib kelgan
                if (by != null) {
                    reason = "Boshqa servisga borgan";
                    hint = "Keyingi servisni «" + by.getName() + "» qilgan";
                } else {
                    reason = "Egasining o'zi kiritgan";
                    hint = "Boshqa joyda yoki o'zi qildirgan bo'lishi mumkin";
                }
            } else if (r.isActive() && overdue > 0) {
                if (car.getTelegramChatId() == null) {
                    reason = "Telegram ulanmagan";
                    hint = "Eslatma bormagan — qo'ng'iroq qiling";
                } else if (r.isDueReminderSent()) {
                    reason = "Eslatma borgan, lekin kelmagan";
                    hint = "Qo'ng'iroq qilib sababini so'rang";
                } else {
                    reason = "Eslatma yetkazilmagan";
                    hint = "Mijoz botni bloklagan bo'lishi mumkin";
                }
            } else {
                continue; // muddati hali kelmagan
            }
            out.add(new MissedDto(prettyPlate(car.getPlateNumber()), car.getFullName().trim(),
                    car.getPhone(), prettyPhone(car.getPhone()), r.displayName(), r.getNextDate(),
                    Math.max(overdue, 0), reason, hint));
        }
        out.sort(Comparator.comparingLong(MissedDto::daysOverdue).reversed());
        return out;
    }

    // =====================================================================
    //  Yordamchi
    // =====================================================================

    private boolean deliverReady(ServiceRecord r, ServiceCenter center) {
        Car car = r.getCar();
        if (car.getTelegramChatId() == null) return false;
        String text = "✅ <b>Mashinangiz tayyor!</b>\n\n"
                + "🚗 <b>" + html(prettyPlate(car.getPlateNumber())) + "</b>\n"
                + "🔧 Bajarildi: <b>" + html(r.displayName()) + "</b>\n"
                + "📅 Keyingi almashtirish: <b>" + date(r.getNextDate()) + "</b>\n\n"
                + "📍 <b>" + html(center.getName()) + "</b>\n"
                + (center.getAddress() == null || center.getAddress().isBlank() ? "" : html(center.getAddress()) + "\n")
                + (center.getPhone() == null ? "" : "📞 " + prettyPhone(center.getPhone()) + "\n")
                + "\nKelib olib ketishingiz mumkin. Rahmat! 🙏";
        boolean ok = telegram.sendMessage(car.getTelegramChatId(), text);
        if (ok) r.setReadyNotifiedAt(Instant.now());
        return ok;
    }

    private CenterStats stats(ServiceCenter c, List<ServiceRecord> all, LocalDate today) {
        Instant monthAgo = Instant.now().minus(Duration.ofDays(30));
        Set<Long> customers = new HashSet<>(), withTelegram = new HashSet<>();
        int last30 = 0, returned = 0, elsewhere = 0, missing = 0;
        Instant last = null;
        for (ServiceRecord r : all) {
            customers.add(r.getCar().getId());
            if (r.getCar().getTelegramChatId() != null) withTelegram.add(r.getCar().getId());
            if (r.getCreatedAt().isAfter(monthAgo)) last30++;
            if (last == null || r.getCreatedAt().isAfter(last)) last = r.getCreatedAt();
            if (r.getReplacedAt() != null) {
                ServiceCenter by = r.getReplacedByCenter();
                if (by != null && by.getId().equals(c.getId())) returned++;
                else elsewhere++;
            } else if (r.isActive() && r.getNextDate().isBefore(today)) {
                missing++;
            }
        }
        int due = returned + elsewhere + missing;
        return new CenterStats(all.size(), customers.size(), last30, due, returned, elsewhere, missing,
                withTelegram.size(), due > 0 ? Math.round(returned * 100f / due) : null,
                last == null ? null : LocalDate.ofInstant(last, app.zoneId()));
    }

    private CenterDto centerDto(ServiceCenter c, List<ServiceRecord> all, LocalDate today) {
        CenterStats s = stats(c, all, today);
        LocalDate since = s.lastActivity() != null ? s.lastActivity() : LocalDate.ofInstant(c.getCreatedAt(), app.zoneId());
        int idle = (int) ChronoUnit.DAYS.between(since, today);
        String status;
        if (!c.isActive()) status = "O'chirilgan";
        else if (s.lastActivity() == null) status = idle > 3 ? "Ishlatmayapti" : "Yangi";
        else if (idle > INACTIVE_AFTER_DAYS) status = "Faol emas";
        else status = "Faol";
        Optional<StaffUser> director = staff.findFirstByCenterAndRoleOrderByIdAsc(c, Role.DIRECTOR);
        return new CenterDto(c.getId(), c.getName(), c.getAddress(), prettyPhone(c.getPhone()),
                director.map(StaffUser::displayName).orElse(null), director.map(StaffUser::getUsername).orElse(null),
                staff.countByCenter(c), c.isActive(), status, idle, s);
    }

    private static int statusOrder(String status) {
        return switch (status) {
            case "Ishlatmayapti", "Faol emas" -> 0;
            case "Yangi" -> 1;
            case "Faol" -> 2;
            default -> 3;
        };
    }

    private AdminRecordDto toDto(ServiceRecord r, LocalDate today, boolean withPhone) {
        Car car = r.getCar();
        return new AdminRecordDto(r.getId(), prettyPlate(car.getPlateNumber()), car.getFullName().trim(),
                withPhone ? car.getPhone() : null, withPhone ? prettyPhone(car.getPhone()) : null,
                car.getTelegramChatId() != null, r.displayName(), r.getDoneDate(), r.getNextDate(),
                ChronoUnit.DAYS.between(today, r.getNextDate()), r.getMileage(), r.getNote(), r.getMasterName(),
                r.isActive(), r.getReadyNotifiedAt(), withPhone ? app.carUrl(car.getToken()) : null,
                withPhone ? r.getPrice() : null, withPhone && r.getPrice() != null ? r.getPaid() : null,
                withPhone && r.getPrice() != null ? r.getRemaining() : null, withPhone ? r.getPayDueDate() : null,
                withPhone ? r.getDiscount() : null, withPhone ? r.getBonusPaid() : null, withPhone ? car.getId() : null);
    }

    private StaffDto staffDto(StaffUser u) {
        return new StaffDto(u.getId(), u.displayName(), u.getUsername(), u.getRole().name(), u.getRole().label(),
                u.isBlocked(), u.getCreatedAt());
    }

    private LogDto logDto(ActivityLog l) {
        return new LogDto(l.getCreatedAt(), l.getActorName(), l.getActorRole(),
                l.getCenter() == null ? null : l.getCenter().getName(), l.getAction());
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
