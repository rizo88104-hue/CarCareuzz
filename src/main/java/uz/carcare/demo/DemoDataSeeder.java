package uz.carcare.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uz.carcare.config.AppProperties;
import uz.carcare.model.*;
import uz.carcare.model.StaffUser.Role;
import uz.carcare.repo.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * DEMO ma'lumotlar: bo'sh bazani demo sahifadagidek to'ldiradi (servis, xodimlar, mijozlar, ishlar,
 * qarzlar, to'lovlar, qora ro'yxat, sadoqat kartalari, jurnal).
 *
 * Faqat DEMO_DATA=true bo'lsa va bazada hali birorta servis bo'lmasa ishlaydi — mavjud ma'lumotlarga tegmaydi.
 * Mijozlarga Telegram ulanmaydi (soxta chat id bilan begona odamlarga xabar ketmasligi uchun).
 * Haqiqiy ishga topshirishda DEMO_DATA ni yoqmang.
 */
@Component
@Order(100)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final boolean enabled;
    private final String password;
    private final ServiceCenterRepository centers;
    private final StaffUserRepository staff;
    private final CarRepository cars;
    private final ServiceRecordRepository records;
    private final PaymentRepository payments;
    private final CustomerNoteRepository notes;
    private final ActivityLogRepository logs;
    private final LoyaltyProgramRepository programs;
    private final LoyaltyAccountRepository accounts;
    private final LoyaltyTransactionRepository txs;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ZoneId zone;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /** Yaratilish vaqtini orqaga surish kerak bo'lgan yozuvlar: jadval, id, vaqt. */
    private final List<Object[]> backdate = new ArrayList<>();
    private LocalDate today;
    private int tokenSeq = 0;

    public DemoDataSeeder(@Value("${app.demo-data:false}") boolean enabled,
                          @Value("${app.demo-password:demo12345}") String password,
                          ServiceCenterRepository centers, StaffUserRepository staff, CarRepository cars,
                          ServiceRecordRepository records, PaymentRepository payments, CustomerNoteRepository notes,
                          ActivityLogRepository logs, LoyaltyProgramRepository programs, LoyaltyAccountRepository accounts,
                          LoyaltyTransactionRepository txs, JdbcTemplate jdbc, PlatformTransactionManager txManager,
                          AppProperties app) {
        this.enabled = enabled;
        this.password = password;
        this.centers = centers;
        this.staff = staff;
        this.cars = cars;
        this.records = records;
        this.payments = payments;
        this.notes = notes;
        this.logs = logs;
        this.programs = programs;
        this.accounts = accounts;
        this.txs = txs;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.zone = app.zoneId();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        if (centers.count() > 0) {
            log.info("DEMO_DATA: bazada servis bor — demo ma'lumotlar qo'shilmadi");
            return;
        }
        if (password == null || password.length() < 8) {
            log.warn("DEMO_DATA: DEMO_PASSWORD kamida 8 belgi bo'lsin — demo ma'lumotlar qo'shilmadi");
            return;
        }
        today = LocalDate.now(zone);
        tx.executeWithoutResult(s -> seed());
        // Sanalarni orqaga surish (to'lovlar grafigi, "shu oy yangi", jurnal vaqtlari)
        for (Object[] b : backdate) {
            jdbc.update("update " + b[0] + " set created_at = ? where id = ?", Timestamp.from((Instant) b[2]), b[1]);
        }
        log.warn("""

                ==================================================
                 DEMO ma'lumotlar qo'shildi («Avto Lider» servisi)
                 Direktor:      sardor   / {}
                 Administrator: dilnoza  / {}
                 Usta:          akmal    / {}
                 Endi DEMO_DATA ni o'chirib qo'yishingiz mumkin.
                ==================================================""", password, password, password);
    }

    // =====================================================================

    private void seed() {
        // ---------- Servislar va xodimlar ----------
        ServiceCenter lider = center("Avto Lider", "Toshkent, Chilonzor 9-kvartal", "998712001122");
        ServiceCenter shina = center("Shina Plus", "Toshkent, Yunusobod 4-kvartal", "998712334455");
        center("Motor Servis 24", "Samarqand, Registon ko'chasi", "998662100011");
        person("sardor", "Sardor Xasanov", Role.DIRECTOR, lider);
        person("dilnoza", "Dilnoza Saidova", Role.ADMINISTRATOR, lider);
        person("akmal", "Akmal Yo'ldoshev", Role.MASTER, lider);
        person("bobur", "Bobur Rahimov", Role.MASTER, lider);
        person("shinaplus", "Anvar Toshev", Role.DIRECTOR, shina);
        person("motor24", "Jamshid Karimov", Role.DIRECTOR, centers.findAll().stream().filter(c -> c.getName().equals("Motor Servis 24")).findFirst().orElseThrow());

        // ---------- Mijozlar ----------
        LocalDate bdayToday = LocalDate.of(1990, today.getMonthValue(), Math.min(today.getDayOfMonth(), 28));
        LocalDate bdaySoon = today.plusDays(9).withYear(1995);
        Car jasur = car("01A777BC", "Jasur", "Karimov", "998901234567", bdayToday, Car.Gender.MALE, "Instagram", -260);
        Car aziza = car("40H555HH", "Aziza", "Nazarova", "998998887766", null, Car.Gender.FEMALE, "Tanish tavsiyasi", -180);
        Car malika = car("01X120KA", "Malika", "Yusupova", "998977778899", bdaySoon, Car.Gender.FEMALE, "Google / Xarita", -150);
        Car bekzod = car("30C222DD", "Bekzod", "Aliyev", "998914442211", null, Car.Gender.MALE, null, -300);
        Car otabek = car("10K404AA", "Otabek", "Mirzayev", "998935556677", LocalDate.of(1987, 3, 14), Car.Gender.MALE, "Reklama", -40);
        Car kamola = car("01M808MM", "Kamola", "Rasulova", "998903332211", null, Car.Gender.FEMALE, "Instagram", -12);
        Car sherzod = car("95B321CA", "Sherzod", "Qodirov", "998974440000", null, Car.Gender.MALE, "Yo'ldan ko'rib kelgan", -90);
        Car nodira = car("01T456TT", "Nodira", "Ahmedova", "998946667788", null, Car.Gender.FEMALE, "Instagram", -6);
        Car ulugbek = car("10R777RA", "Ulug'bek", "Sobirov", "998933210099", null, Car.Gender.MALE, "Tanish tavsiyasi", -9);
        Car dilshod = car("40P909PA", "Dilshod", "Ergashev", "998915553344", null, Car.Gender.MALE, "Google / Xarita", -14);

        // ---------- Ishlar va to'lovlar (Avto Lider) ----------
        String akmal = "Akmal Yo'ldoshev", bobur = "Bobur Rahimov";
        ServiceRecord jOld = job(lider, jasur, ServiceType.OIL, null, -180, -2, bobur, 41900, null, 280000L, 280000, Payment.Method.CASH, null);
        jOld.markReplaced(lider);
        job(lider, jasur, ServiceType.OTHER, "Dvigatel ta'miri", -320, 45, akmal, 36500, "Kafolat tekshiruvi", 3570000L, 3570000, Payment.Method.TRANSFER, null);
        job(lider, jasur, ServiceType.OIL, null, -2, 178, akmal, 48200, "5W-30, 4 litr", 300000L, 300000, Payment.Method.CASH, null);
        job(lider, jasur, ServiceType.TIRES, null, -4, 360, akmal, null, "205/55 R16", 1200000L, 100000, Payment.Method.CASH, 9);
        job(lider, aziza, ServiceType.BRAKE_PADS, null, -17, 175, bobur, null, null, 450000L, 0, null, -10);
        job(lider, aziza, ServiceType.OIL, null, -150, 4, akmal, null, null, 250000L, 250000, Payment.Method.CARD, null);
        job(lider, malika, ServiceType.OIL_FILTER, null, -175, 3, bobur, null, null, 90000L, 90000, Payment.Method.CASH, null);
        ServiceRecord bTires = job(lider, bekzod, ServiceType.TIRES, null, -220, -40, akmal, null, null, 800000L, 800000, Payment.Method.CASH, null);
        ServiceRecord bBig = job(lider, bekzod, ServiceType.OTHER, "Kapital ta'mir", -260, -60, akmal, null, null, 11000000L, 11000000, Payment.Method.TRANSFER, null);
        bBig.setActive(false);
        ServiceRecord o1 = job(lider, otabek, ServiceType.OIL, null, 0, 182, akmal, 61200, null, 320000L, 320000, Payment.Method.CARD, null);
        o1.setReadyNotifiedAt(Instant.now());
        job(lider, otabek, ServiceType.AIR_FILTER, null, 0, 365, akmal, null, null, 120000L, 120000, Payment.Method.CARD, null);
        job(lider, kamola, ServiceType.INSPECTION, null, -1, 364, bobur, null, null, null, 0, null, null);
        job(lider, sherzod, ServiceType.ANTIFREEZE, null, -70, 290, akmal, null, null, 600000L, 100000, Payment.Method.CASH, -48);
        job(lider, nodira, ServiceType.OIL, null, -5, 175, bobur, null, null, 350000L, 350000, Payment.Method.CARD, null);
        job(lider, ulugbek, ServiceType.CABIN_FILTER, null, -8, 357, akmal, null, null, 180000L, 180000, Payment.Method.CASH, null);
        job(lider, ulugbek, ServiceType.SPARK_PLUGS, null, -8, 357, akmal, null, null, 420000L, 420000, Payment.Method.CASH, null);
        job(lider, dilshod, ServiceType.TRANSMISSION_OIL, null, -14, 716, bobur, null, null, 760000L, 760000, Payment.Method.TRANSFER, null);

        // Bekzod keyingi ballonni boshqa servisda qildirgan — super admin "kelmagan mijozlar" sababini ko'radi
        bTires.markReplaced(shina);
        job(shina, bekzod, ServiceType.TIRES, null, -38, 330, "Shina Plus ustasi", null, null, 950000L, 950000, Payment.Method.CASH, null);

        // ---------- Izoh va qora ro'yxat ----------
        CustomerNote n = new CustomerNote(lider, bekzod);
        n.update("Boshqa servisga o'tib ketgan, chegirma taklif qilish kerak", "Dilnoza Saidova");
        notes.save(n);
        CustomerNote b = new CustomerNote(lider, sherzod);
        b.blacklist("Qarzini 2 oydan beri to'lamayapti, qo'pol muomala", "Sardor Xasanov");
        notes.save(b);

        // ---------- Sadoqat dasturi ----------
        LoyaltyProgram prog = new LoyaltyProgram(lider);
        prog.setEnabled(true);
        prog.touch("Sardor Xasanov");
        programs.save(prog);
        LoyaltyAccount aJ = account(lider, jasur, "40213377", prog, -2);
        tx(aJ, LoyaltyTransaction.Type.WELCOME, 10000, "Karta ochildi", -60);
        tx(aJ, LoyaltyTransaction.Type.CASHBACK, 17500, "Dvigatel ta'miri (dastur ochilishidan oldingi xarid hisobga olinmadi)", -45);
        tx(aJ, LoyaltyTransaction.Type.REDEEM, -20000, "Motor moyi", -30);
        tx(aJ, LoyaltyTransaction.Type.CASHBACK, 15000, "Motor moyi: 300 000 so'm × 5%", -2);
        tx(aJ, LoyaltyTransaction.Type.CASHBACK, 5000, "Ballon (shina): 100 000 so'm × 5%", -4);
        tx(aJ, LoyaltyTransaction.Type.MANUAL_ADD, 15000, "Doimiy mijozga sovg'a", -1);
        LoyaltyAccount aA = account(lider, aziza, "51870264", prog, -17);
        tx(aA, LoyaltyTransaction.Type.WELCOME, 10000, "Karta ochildi", -150);
        tx(aA, LoyaltyTransaction.Type.CASHBACK, 7500, "Motor moyi: 250 000 so'm × 3%", -150);
        LoyaltyAccount aB = account(lider, bekzod, "73015548", prog, -220);
        tx(aB, LoyaltyTransaction.Type.WELCOME, 10000, "Karta ochildi", -260);
        tx(aB, LoyaltyTransaction.Type.CASHBACK, 56000, "Ballon (shina): 800 000 so'm × 7%", -220);
        LoyaltyAccount aO = account(lider, otabek, "28466093", prog, 0);
        tx(aO, LoyaltyTransaction.Type.WELCOME, 10000, "Karta ochildi", 0);
        tx(aO, LoyaltyTransaction.Type.CASHBACK, 13200, "Motor moyi va havo filtri: 440 000 so'm × 3%", 0);
        LoyaltyAccount aN = account(lider, nodira, "61309925", prog, -5);
        tx(aN, LoyaltyTransaction.Type.WELCOME, 10000, "Karta ochildi", -5);
        tx(aN, LoyaltyTransaction.Type.CASHBACK, 10500, "Motor moyi: 350 000 so'm × 3%", -5);

        // ---------- Jurnal ----------
        logEntry(null, "Tizim", "Bot", null, "Kunlik eslatmalar tekshirildi", 0);
        logEntry(null, "Dilnoza Saidova", "Administrator", lider, "10 R 777 RA — Salon filtri kiritdi (usta: Akmal Yo'ldoshev), narx 180 000 so'm", -8);
        logEntry(null, "Sardor Xasanov", "Direktor", lider, "Sadoqat dasturini yoqdi (keshbek 3–10%, chegirma 0–10%, ball bilan 50% gacha)", -60);
        logEntry(null, "Sardor Xasanov", "Direktor", lider, "95 B 321 CA egasini qora ro'yxatga qo'shdi: Qarzini 2 oydan beri to'lamayapti", -15);
        logEntry(null, "Dilnoza Saidova", "Administrator", lider, "10 K 404 AA — Motor moyi kiritdi (usta: Akmal Yo'ldoshev), narx 320 000 so'm", 0);
        logEntry(null, "Dilnoza Saidova", "Administrator", lider, "10 K 404 AA egasiga «Tayyor, keling» yubordi", 0);
    }

    // =====================================================================
    //  Yordamchilar
    // =====================================================================

    private Instant at(int days, int hour) {
        return today.plusDays(days).atTime(hour, 15).atZone(zone).toInstant();
    }

    private ServiceCenter center(String name, String address, String phone) {
        ServiceCenter c = new ServiceCenter();
        c.setName(name);
        c.setAddress(address);
        c.setPhone(phone);
        return centers.save(c);
    }

    private void person(String username, String fullName, Role role, ServiceCenter c) {
        if (staff.existsByUsername(username)) return;
        StaffUser u = new StaffUser();
        u.setUsername(username);
        u.setFullName(fullName);
        u.setPasswordHash(encoder.encode(password));
        u.setRole(role);
        u.setCenter(c);
        staff.save(u);
    }

    private Car car(String plate, String first, String last, String phone, LocalDate birth, Car.Gender g, String source, int createdDays) {
        Car c = cars.findByPlateNumber(plate).orElse(null);
        if (c != null) return c;
        c = new Car();
        c.setToken("demo" + plate.toLowerCase() + (++tokenSeq));
        c.setFirstName(first);
        c.setLastName(last);
        c.setPhone(phone);
        c.setPlateNumber(plate);
        c.setBirthDate(birth);
        c.setGender(g);
        c.setSource(source);
        c.setSmsEnabled(false);
        cars.save(c);
        backdate.add(new Object[]{"cars", c.getId(), at(createdDays, 10)});
        return c;
    }

    /** dueDays != null — qarz: to'lash muddati bugundan shuncha kun keyin (manfiy — o'tgan). */
    private ServiceRecord job(ServiceCenter center, Car car, ServiceType type, String customName, int done, int next,
                              String master, Integer mileage, String note, Long price, long paid,
                              Payment.Method method, Integer dueDays) {
        ServiceRecord r = new ServiceRecord();
        r.setCar(car);
        r.setCenter(center);
        r.setType(type);
        r.setCustomName(customName);
        r.setDoneDate(today.plusDays(done));
        r.setNextDate(today.plusDays(next));
        r.setMileage(mileage);
        r.setNote(note);
        r.setMasterName(master);
        // Demo: eski eslatmalar qayta yuborilmasin
        r.setWeekReminderSent(next <= 7);
        r.setDueReminderSent(next <= 0);
        if (price != null) {
            r.setPrice(price);
            if (dueDays != null) {
                r.setCredit(true);
                r.setPayDueDate(today.plusDays(dueDays));
                r.setDebtRemindedOn(today);
            }
        }
        records.save(r);
        if (paid > 0) {
            Payment p = payments.save(new Payment(r, center, paid, method, "Dilnoza Saidova", "Ish kiritilganda"));
            r.addPaid(paid);
            backdate.add(new Object[]{"payments", p.getId(), at(done, 11)});
        }
        return r;
    }

    private LoyaltyAccount account(ServiceCenter center, Car car, String card, LoyaltyProgram prog, int lastActivityDays) {
        LoyaltyAccount a = new LoyaltyAccount(center, car, card, today.plusDays(lastActivityDays), "Dilnoza Saidova");
        long spend = payments.findAllForCar(center, car).stream()
                .filter(p -> p.getMethod().isMoney()).mapToLong(Payment::getAmount).sum();
        a.setSpendTotal(spend);
        a.setLevel(prog.levelFor(spend));
        return accounts.save(a);
    }

    private void tx(LoyaltyAccount a, LoyaltyTransaction.Type type, long points, String note, int days) {
        a.change(points);
        if (points > 0) a.addEarned(points);
        if (type == LoyaltyTransaction.Type.REDEEM) a.addRedeemed(-points);
        LoyaltyTransaction t = txs.save(new LoyaltyTransaction(a, type, points, null, note,
                type == LoyaltyTransaction.Type.MANUAL_ADD ? "Sardor Xasanov" : "Dilnoza Saidova"));
        backdate.add(new Object[]{"loyalty_transactions", t.getId(), at(days, 12)});
    }

    private void logEntry(Long staffId, String name, String role, ServiceCenter c, String action, int days) {
        ActivityLog l = logs.save(new ActivityLog(staffId, name, role, c, action));
        backdate.add(new Object[]{"activity_log", l.getId(), at(days, days == 0 ? 11 : 15)});
    }
}
