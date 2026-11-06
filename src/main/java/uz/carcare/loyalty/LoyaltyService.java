package uz.carcare.loyalty;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.carcare.admin.AdminPrincipal;
import uz.carcare.admin.AuditService;
import uz.carcare.config.AppProperties;
import uz.carcare.loyalty.LoyaltyDtos.*;
import uz.carcare.model.*;
import uz.carcare.repo.*;
import uz.carcare.telegram.TelegramClient;
import uz.carcare.web.ApiException;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static uz.carcare.util.Formats.*;

/**
 * Sadoqat dasturi: jamg'arma karta, bonus ballar (keshbek), darajalar va doimiy chegirma.
 *
 * <ul>
 *   <li>1 ball = 1 so'm. Ball faqat <b>haqiqiy pul</b> bilan to'langan summadan yoziladi
 *       (naqd, karta, o'tkazma). Qarzga qilingan ish uchun ball qarz to'langanda yoziladi.</li>
 *   <li>Daraja mijozning shu servisda pul bilan to'lagan jami summasiga qarab avtomatik oshadi.</li>
 *   <li>Ball bilan to'lov oddiy to'lov sifatida yoziladi (usuli «Bonus ball»), lekin tushumga
 *       qo'shilmaydi va undan keshbek berilmaydi.</li>
 *   <li>Qora ro'yxatdagi mijozga ball yozilmaydi va chegirma berilmaydi.</li>
 *   <li>Dastur o'chirilsa, ballar saqlanib qoladi (kuymaydi), lekin yozilmaydi va ishlatilmaydi.</li>
 * </ul>
 */
@Service
public class LoyaltyService {

    /** Ballar kuyishidan necha kun oldin ogohlantiriladi. */
    static final int WARN_DAYS = 7;
    private static final int LIST_LIMIT = 300;
    private static final int RECENT = 50;

    private final LoyaltyProgramRepository programs;
    private final LoyaltyAccountRepository accounts;
    private final LoyaltyTransactionRepository txs;
    private final PaymentRepository payments;
    private final CustomerNoteRepository notes;
    private final ServiceCenterRepository centers;
    private final ServiceRecordRepository records;
    private final CarRepository cars;
    private final TelegramClient telegram;
    private final AuditService audit;
    private final AppProperties app;
    private final SecureRandom random = new SecureRandom();

    public LoyaltyService(LoyaltyProgramRepository programs, LoyaltyAccountRepository accounts,
                          LoyaltyTransactionRepository txs, PaymentRepository payments, CustomerNoteRepository notes,
                          ServiceCenterRepository centers, ServiceRecordRepository records, CarRepository cars,
                          TelegramClient telegram, AuditService audit, AppProperties app) {
        this.programs = programs;
        this.accounts = accounts;
        this.txs = txs;
        this.payments = payments;
        this.notes = notes;
        this.centers = centers;
        this.records = records;
        this.cars = cars;
        this.telegram = telegram;
        this.audit = audit;
        this.app = app;
    }

    /** Keshbek natijasi (to'lov kvitansiyasiga qo'shiladi). */
    public record Earn(long points, long balance, LoyaltyLevel level, boolean levelUp, int cashbackPct) {
    }

    // =====================================================================
    //  Boshqa servislar chaqiradigan asosiy amallar (ish kiritish, to'lov)
    // =====================================================================

    /** Yoqilgan dastur yoki null. */
    public LoyaltyProgram activeProgram(ServiceCenter center) {
        return programs.findByCenter(center).filter(LoyaltyProgram::isEnabled).orElse(null);
    }

    public Optional<LoyaltyAccount> account(ServiceCenter center, Car car) {
        return car == null || car.getId() == null ? Optional.empty() : accounts.findByCenterAndCar(center, car);
    }

    /**
     * Tashrif (ish kiritildi): dastur yoqilgan bo'lsa va karta bo'lmasa — karta ochadi
     * (xush kelibsiz bonusi bilan). Faollik sanasini yangilaydi (ballar kuyish muddati shundan).
     */
    public LoyaltyAccount onVisit(ServiceCenter center, Car car, String actor) {
        LoyaltyProgram prog = activeProgram(center);
        LoyaltyAccount acc = account(center, car).orElse(null);
        if (acc == null && prog != null && !blacklisted(center, car)) {
            acc = open(prog, car, actor, true);
            audit.system(actor, "Sadoqat", center, prettyPlate(car.getPlateNumber()) + " uchun jamg'arma karta ochildi: "
                    + prettyCard(acc.getCardNumber()));
        }
        if (acc != null) activity(acc);
        return acc;
    }

    /** Mijozning hozirgi chegirma foizi (dastur o'chiq yoki mijoz qora ro'yxatda bo'lsa 0). car=null — yangi mijoz. */
    public int discountPct(ServiceCenter center, Car car) {
        LoyaltyProgram prog = activeProgram(center);
        if (prog == null || blacklisted(center, car)) return 0;
        return prog.discountPct(levelOf(prog, center, car)); // yangi mijoz — Bronza (karta ish bilan birga ochiladi)
    }

    public static long percent(long amount, int pct) {
        return amount <= 0 || pct <= 0 ? 0 : Math.floorDiv(Math.multiplyExact(amount, (long) pct), 100L);
    }

    /** Shu ish uchun ball bilan to'lash mumkin bo'lgan eng ko'p summa. */
    public long redeemCap(LoyaltyProgram prog, LoyaltyAccount acc, ServiceRecord r) {
        if (prog == null || acc == null || r.getPrice() == null) return 0;
        long byRule = percent(r.getPrice(), prog.getMaxRedeemPct()) - r.getBonusPaid();
        return Math.max(0, Math.min(Math.min(acc.getBalance(), byRule), r.getRemaining()));
    }

    /**
     * Ball bilan to'lov: «Bonus ball» to'lovi yoziladi, kartadan ball yechiladi.
     * Chaqiruvchi tranzaksiya ichida bo'lishi kerak.
     */
    public void redeem(ServiceCenter center, ServiceRecord r, long amount, String actor, String note) {
        if (amount <= 0) return;
        LoyaltyProgram prog = activeProgram(center);
        if (prog == null) throw ApiException.badRequest("Sadoqat dasturi yoqilmagan — ball bilan to'lab bo'lmaydi");
        LoyaltyAccount acc = account(center, r.getCar())
                .orElseThrow(() -> ApiException.badRequest("Mijozda jamg'arma karta yo'q"));
        long cap = redeemCap(prog, acc, r);
        if (amount > cap) {
            throw ApiException.badRequest("Ball bilan ko'pi bilan " + money(cap) + " to'lash mumkin (balans "
                    + points(acc.getBalance()) + ", ish narxining " + prog.getMaxRedeemPct() + "% gacha)");
        }
        acc.change(-amount);
        acc.addRedeemed(amount);
        activity(acc);
        payments.save(new Payment(r, center, amount, Payment.Method.BONUS, actor, note));
        r.addPaid(amount);
        r.addBonusPaid(amount);
        txs.save(new LoyaltyTransaction(acc, LoyaltyTransaction.Type.REDEEM, -amount, r, r.displayName(), actor));
    }

    /**
     * Haqiqiy pul bilan to'lovdan keyin chaqiriladi (to'lov allaqachon saqlangan bo'lishi kerak):
     * keshbek yoziladi, daraja yangilanadi. Dastur o'chiq yoki mijoz qora ro'yxatda bo'lsa — null.
     */
    public Earn onMoneyPayment(ServiceCenter center, ServiceRecord r, long amount, String actor) {
        if (amount <= 0) return null;
        LoyaltyProgram prog = activeProgram(center);
        Car car = r.getCar();
        if (prog == null || blacklisted(center, car)) return null;
        LoyaltyAccount acc = account(center, car).orElse(null);
        if (acc == null) {
            acc = open(prog, car, actor, true); // jami xarajat to'lovlardan hisoblanadi — shu to'lov ham ichida
        } else {
            acc.addSpend(amount);
        }
        LoyaltyLevel before = acc.getLevel();
        int pct = prog.cashbackPct(before);
        long pts = percent(amount, pct);
        if (pts > 0) {
            credit(acc, LoyaltyTransaction.Type.CASHBACK, pts, r,
                    r.displayName() + ": " + money(amount) + " × " + pct + "%", actor);
        }
        LoyaltyLevel after = prog.levelFor(acc.getSpendTotal());
        boolean up = after.ordinal() > before.ordinal();
        acc.setLevel(after);
        activity(acc);
        if (up) notifyLevelUp(acc, prog);
        return new Earn(pts, acc.getBalance(), after, up, pct);
    }

    /** Kvitansiyadagi qator: keshbek va balans. */
    public static String receiptLine(Earn e) {
        if (e == null || e.points() <= 0) return "";
        return "\n🎁 Keshbek: <b>+" + points(e.points()) + "</b> (" + e.cashbackPct() + "%), balans: " + points(e.balance());
    }

    /** Ish formasi uchun qisqa ma'lumot. Usta uchun chaqirilmaydi. */
    @Transactional(readOnly = true)
    public LookupLoyalty lookup(ServiceCenter center, Car car) {
        LoyaltyProgram prog = programs.findByCenter(center).orElse(null);
        boolean enabled = prog != null && prog.isEnabled();
        LoyaltyAccount acc = account(center, car).orElse(null);
        if (!enabled && acc == null) return null;
        boolean black = car != null && car.getId() != null && blacklisted(center, car);
        LoyaltyLevel level = acc != null ? acc.getLevel()
                : enabled ? levelOf(prog, center, car) : LoyaltyLevel.BRONZE;
        boolean willOpen = enabled && acc == null && !black;
        return new LookupLoyalty(enabled, black, willOpen,
                acc == null ? null : prettyCard(acc.getCardNumber()),
                level.name(), level.label(), level.icon(),
                acc == null ? 0 : acc.getBalance(),
                enabled && !black ? prog.discountPct(level) : 0,
                enabled && !black ? prog.cashbackPct(level) : 0,
                enabled ? prog.getMaxRedeemPct() : 0,
                willOpen ? prog.getWelcomeBonus() : 0);
    }

    // =====================================================================
    //  Sozlamalar (direktor)
    // =====================================================================

    @Transactional(readOnly = true)
    public ProgramDto settings(AdminPrincipal p) {
        return programDto(programOrDefault(center(p)));
    }

    @Transactional
    public ProgramDto updateSettings(AdminPrincipal p, ProgramRequest req) {
        ServiceCenter center = center(p);
        Map<LoyaltyLevel, LevelInput> byLevel = new EnumMap<>(LoyaltyLevel.class);
        for (LevelInput l : req.levels()) {
            if (byLevel.put(l.level(), l) != null) throw ApiException.badRequest("Daraja ikki marta ko'rsatilgan: " + l.level().label());
        }
        if (byLevel.size() != LoyaltyLevel.values().length) throw ApiException.badRequest("Barcha 4 ta darajani kiriting");
        long prevFrom = 0;
        int prevCash = -1, prevDisc = -1;
        for (LoyaltyLevel l : LoyaltyLevel.values()) {
            LevelInput in = byLevel.get(l);
            long from = l == LoyaltyLevel.BRONZE ? 0 : in.from();
            if (l != LoyaltyLevel.BRONZE && from <= prevFrom) {
                throw ApiException.badRequest(l.label() + " chegarasi oldingi darajadan katta bo'lsin");
            }
            if (in.cashbackPct() < prevCash) throw ApiException.badRequest(l.label() + " darajasida keshbek oldingi darajadan kam bo'lmasin");
            if (in.discountPct() < prevDisc) throw ApiException.badRequest(l.label() + " darajasida chegirma oldingi darajadan kam bo'lmasin");
            if (in.cashbackPct() + in.discountPct() > 60) {
                throw ApiException.badRequest(l.label() + ": keshbek va chegirma birgalikda 60% dan oshmasin");
            }
            prevFrom = from;
            prevCash = in.cashbackPct();
            prevDisc = in.discountPct();
        }
        if (req.expiryDays() > 0 && req.expiryDays() < 30) throw ApiException.badRequest("Kuyish muddati kamida 30 kun bo'lsin (yoki 0 — kuymaydi)");

        LoyaltyProgram prog = programs.findByCenter(center).orElseGet(() -> new LoyaltyProgram(center));
        boolean wasEnabled = prog.getId() != null && prog.isEnabled();
        for (LoyaltyLevel l : LoyaltyLevel.values()) {
            LevelInput in = byLevel.get(l);
            prog.setLevel(l, l == LoyaltyLevel.BRONZE ? 0 : in.from(), in.cashbackPct(), in.discountPct());
        }
        prog.setMaxRedeemPct(req.maxRedeemPct());
        prog.setWelcomeBonus(req.welcomeBonus());
        prog.setBirthdayBonus(req.birthdayBonus());
        prog.setExpiryDays(req.expiryDays());
        prog.setEnabled(req.enabled());
        prog.touch(p.fullName());
        programs.save(prog);

        // Darajalarni qayta hisoblash (chegaralar o'zgargan yoki dastur qayta yoqilgan bo'lishi mumkin)
        int changed = recalcLevels(prog);

        String action = !wasEnabled && req.enabled() ? "Sadoqat dasturini yoqdi"
                : wasEnabled && !req.enabled() ? "Sadoqat dasturini to'xtatdi"
                : "Sadoqat dasturi sozlamalarini o'zgartirdi";
        audit.staff(p, center, action + " (keshbek " + prog.cashbackPct(LoyaltyLevel.BRONZE) + "–"
                + prog.cashbackPct(LoyaltyLevel.PLATINUM) + "%, chegirma " + prog.discountPct(LoyaltyLevel.BRONZE) + "–"
                + prog.discountPct(LoyaltyLevel.PLATINUM) + "%, ball bilan " + prog.getMaxRedeemPct() + "% gacha"
                + (changed > 0 ? ", " + changed + " ta mijoz darajasi yangilandi" : "") + ")");
        return programDto(prog);
    }

    /** Barcha kartalar darajasini to'lovlardan qayta hisoblaydi (xabar yuborilmaydi). */
    int recalcLevels(LoyaltyProgram prog) {
        Map<Long, Long> spend = new HashMap<>();
        for (Object[] row : payments.sumMoneyByCar(prog.getCenter(), Payment.Method.BONUS)) {
            spend.put((Long) row[0], ((Number) row[1]).longValue());
        }
        int changed = 0;
        for (LoyaltyAccount a : accounts.findAllForCenter(prog.getCenter())) {
            long s = spend.getOrDefault(a.getCar().getId(), 0L);
            LoyaltyLevel l = prog.levelFor(s);
            if (a.getSpendTotal() != s) a.setSpendTotal(s);
            if (a.getLevel() != l) {
                a.setLevel(l);
                changed++;
            }
        }
        return changed;
    }

    // =====================================================================
    //  Sadoqat bo'limi (direktor va administrator)
    // =====================================================================

    @Transactional(readOnly = true)
    public Overview overview(AdminPrincipal p, String q, LoyaltyLevel level) {
        ServiceCenter center = center(p);
        LoyaltyProgram prog = programOrDefault(center);
        LocalDate today = today();
        LocalDate monthStart = today.withDayOfMonth(1);
        List<LoyaltyAccount> all = accounts.findAllForCenter(center);

        Map<String, Integer> levelCounts = new LinkedHashMap<>();
        for (LoyaltyLevel l : LoyaltyLevel.values()) levelCounts.put(l.name(), 0);
        long balance = 0;
        int withBalance = 0, newMonth = 0;
        for (LoyaltyAccount a : all) {
            levelCounts.merge(a.getLevel().name(), 1, Integer::sum);
            balance += a.getBalance();
            if (a.getBalance() > 0) withBalance++;
            if (!LocalDate.ofInstant(a.getCreatedAt(), app.zoneId()).isBefore(monthStart)) newMonth++;
        }
        long earned = 0, redeemed = 0, expired = 0;
        for (LoyaltyTransaction t : txs.findAllByCenterAndCreatedAtGreaterThanEqual(center,
                monthStart.atStartOfDay(app.zoneId()).toInstant())) {
            switch (t.getType()) {
                case REDEEM -> redeemed += -t.getPoints();
                case EXPIRE -> expired += -t.getPoints();
                case MANUAL_SUB -> { }
                default -> earned += t.getPoints();
            }
        }
        Stats stats = new Stats(all.size(), withBalance, balance, earned, redeemed, expired, newMonth);

        List<CardDto> filtered = all.stream()
                .filter(a -> level == null || a.getLevel() == level)
                .filter(a -> matches(a, q))
                .map(a -> card(a, prog, today))
                .toList();
        List<TxDto> recent = txs.findRecent(center, PageRequest.of(0, RECENT)).stream().map(LoyaltyService::txDto).toList();
        return new Overview(programDto(prog), stats, levelCounts, filtered.size(),
                filtered.stream().limit(LIST_LIMIT).toList(), recent);
    }

    /** Mijoz profilidagi blok. */
    @Transactional(readOnly = true)
    public CustomerLoyalty customer(AdminPrincipal p, Long carId) {
        ServiceCenter center = center(p);
        Car car = carOfCenter(center, carId);
        LoyaltyProgram prog = programOrDefault(center);
        LoyaltyAccount acc = account(center, car).orElse(null);
        List<TxDto> list = acc == null ? List.of()
                : txs.findAllByAccountOrderByCreatedAtDescIdDesc(acc).stream().map(LoyaltyService::txDto).toList();
        return new CustomerLoyalty(prog.isEnabled(), blacklisted(center, car), prog.getMaxRedeemPct(),
                acc == null ? null : card(acc, prog, today()), list);
    }

    /** Karta ochish (qo'lda). */
    @Transactional
    public CustomerLoyalty issue(AdminPrincipal p, Long carId) {
        ServiceCenter center = center(p);
        Car car = carOfCenter(center, carId);
        LoyaltyProgram prog = requireEnabled(center);
        if (account(center, car).isPresent()) throw ApiException.badRequest("Bu mijozda karta allaqachon bor");
        if (blacklisted(center, car)) throw ApiException.badRequest("Mijoz qora ro'yxatda — karta ochib bo'lmaydi");
        LoyaltyAccount acc = open(prog, car, p.fullName(), true);
        audit.staff(p, center, prettyPlate(car.getPlateNumber()) + " uchun jamg'arma karta ochdi: " + prettyCard(acc.getCardNumber()));
        return customer(p, carId);
    }

    /** Kartasi yo'q barcha mijozlarga karta ochish (dastur yangi yoqilganda). */
    @Transactional
    public IssueAllResult issueAll(AdminPrincipal p, IssueAllRequest req) {
        ServiceCenter center = center(p);
        LoyaltyProgram prog = requireEnabled(center);
        Set<Long> has = new HashSet<>();
        for (LoyaltyAccount a : accounts.findAllForCenter(center)) has.add(a.getCar().getId());
        Set<Long> black = new HashSet<>();
        Map<Long, Car> customers = new LinkedHashMap<>();
        for (CustomerNote n : notes.findAllForCenter(center)) {
            if (n.isBlacklisted()) black.add(n.getCar().getId());
            customers.putIfAbsent(n.getCar().getId(), n.getCar());
        }
        for (ServiceRecord r : records.findAllForCenter(center)) customers.putIfAbsent(r.getCar().getId(), r.getCar());
        int created = 0;
        for (Car car : customers.values()) {
            if (has.contains(car.getId()) || black.contains(car.getId())) continue;
            open(prog, car, p.fullName(), req.withWelcome());
            created++;
        }
        audit.staff(p, center, "Mavjud mijozlarga jamg'arma karta ochdi: " + created + " ta"
                + (req.withWelcome() && prog.getWelcomeBonus() > 0 ? " (har biriga " + points(prog.getWelcomeBonus()) + ")" : ""));
        return new IssueAllResult(created, created == 0 ? "Barcha mijozlarda karta bor"
                : created + " ta mijozga karta ochildi. Telegramga ulanganlarga xabar yuborildi.");
    }

    /** Ballarni qo'lda qo'shish yoki ayirish (direktor). */
    @Transactional
    public CustomerLoyalty adjust(AdminPrincipal p, Long carId, AdjustRequest req) {
        ServiceCenter center = center(p);
        Car car = carOfCenter(center, carId);
        LoyaltyAccount acc = account(center, car).orElseThrow(() -> ApiException.badRequest("Mijozda jamg'arma karta yo'q"));
        long pts = req.points();
        if (pts == 0) throw ApiException.badRequest("Ball miqdori 0 bo'lmasin");
        if (pts < 0 && -pts > acc.getBalance()) throw ApiException.badRequest("Balansda faqat " + points(acc.getBalance()) + " bor");
        String note = req.note().trim();
        if (pts > 0) {
            credit(acc, LoyaltyTransaction.Type.MANUAL_ADD, pts, null, note, p.fullName());
        } else {
            acc.change(pts);
            txs.save(new LoyaltyTransaction(acc, LoyaltyTransaction.Type.MANUAL_SUB, pts, null, note, p.fullName()));
        }
        audit.staff(p, center, prettyPlate(car.getPlateNumber()) + " kartasiga " + (pts > 0 ? "+" : "−") + points(Math.abs(pts))
                + " (" + note + "), balans " + points(acc.getBalance()));
        send(acc, (pts > 0 ? "🎁 Kartangizga <b>+" + points(pts) + "</b> qo'shildi" : "➖ Kartangizdan <b>" + points(-pts) + "</b> ayirildi")
                + "\nSabab: " + html(note) + "\n\n💳 Balans: <b>" + points(acc.getBalance()) + "</b>\n📍 " + html(center.getName()));
        return customer(p, carId);
    }

    // =====================================================================
    //  Har kungi ishlar (09:00): tug'ilgan kun bonusi, kuyish ogohlantirishi va kuyish
    // =====================================================================

    public record Gift(Long chatId, String line) {
    }

    /** Tug'ilgan kun bonuslarini yozadi. Natija: mashina id → tabrikka qo'shiladigan qatorlar. */
    @Transactional
    public Map<Long, List<Gift>> birthdayBonuses(LocalDate today) {
        Map<Long, List<Gift>> out = new HashMap<>();
        for (LoyaltyProgram prog : programs.findAllEnabled()) {
            if (prog.getBirthdayBonus() <= 0 || !prog.getCenter().isActive()) continue;
            for (LoyaltyAccount a : accounts.findWithBirthDate(prog.getCenter())) {
                Car car = a.getCar();
                if (!isBirthday(car.getBirthDate(), today)) continue;
                if (Integer.valueOf(today.getYear()).equals(a.getBirthdayBonusYear())) continue;
                if (blacklisted(prog.getCenter(), car)) continue;
                a.setBirthdayBonusYear(today.getYear());
                credit(a, LoyaltyTransaction.Type.BIRTHDAY, prog.getBirthdayBonus(), null, "Tug'ilgan kun sovg'asi", "Tizim");
                out.computeIfAbsent(car.getId(), k -> new ArrayList<>()).add(new Gift(car.getTelegramChatId(),
                        "🎁 <b>" + html(prog.getCenter().getName()) + "</b> sizga <b>" + points(prog.getBirthdayBonus())
                                + "</b> sovg'a qildi (balans: " + points(a.getBalance()) + ")"));
            }
        }
        return out;
    }

    /** Kuyishi yaqin ballar haqida ogohlantiradi va muddati o'tganlarini kuydiradi. [ogohlantirildi, kuydi] */
    @Transactional
    public int[] expire(LocalDate today) {
        int warned = 0, burned = 0;
        for (LoyaltyProgram prog : programs.findAllEnabled()) {
            if (prog.getExpiryDays() <= 0 || !prog.getCenter().isActive()) continue;
            for (LoyaltyAccount a : accounts.findWithBalance(prog.getCenter())) {
                LocalDate expires = a.getLastActivity().plusDays(prog.getExpiryDays());
                long left = ChronoUnit.DAYS.between(today, expires);
                if (left <= 0) {
                    long amount = a.getBalance();
                    a.change(-amount);
                    a.addExpired(amount);
                    txs.save(new LoyaltyTransaction(a, LoyaltyTransaction.Type.EXPIRE, -amount, null,
                            prog.getExpiryDays() + " kun faollik bo'lmadi", "Tizim"));
                    send(a, "🔥 <b>" + html(prog.getCenter().getName()) + "</b> kartangizdagi <b>" + points(amount)
                            + "</b> kuydi — " + prog.getExpiryDays() + " kun davomida tashrif bo'lmadi.\n\nKeyingi tashrifda yana ball yig'ishingiz mumkin.");
                    burned++;
                } else if (left <= WARN_DAYS && prog.getExpiryDays() > WARN_DAYS && a.getExpiryWarnedOn() == null) {
                    a.setExpiryWarnedOn(today);
                    send(a, "⏳ <b>" + html(prog.getCenter().getName()) + "</b> kartangizdagi <b>" + points(a.getBalance())
                            + "</b> " + date(expires) + " da kuyadi (" + left + " kun qoldi).\n\n"
                            + "Ballarni saqlab qolish uchun shu kungacha servisga keling — ballarni to'lovda ishlatishingiz mumkin."
                            + (prog.getCenter().getPhone() != null ? "\n📞 " + prettyPhone(prog.getCenter().getPhone()) : ""));
                    warned++;
                }
            }
        }
        return new int[]{warned, burned};
    }

    // =====================================================================
    //  Mijoz sahifasi va bot
    // =====================================================================

    @Transactional(readOnly = true)
    public List<PublicCard> publicCards(Car car) {
        LocalDate today = today();
        List<PublicCard> out = new ArrayList<>();
        for (LoyaltyAccount a : accounts.findAllForCar(car)) {
            LoyaltyProgram prog = programOrDefault(a.getCenter());
            CardDto c = card(a, prog, today);
            out.add(new PublicCard(a.getCenter().getName(), c.cardDisplay(), c.levelLabel(), c.levelIcon(), c.balance(),
                    prog.isEnabled() ? c.cashbackPct() : 0, prog.isEnabled() ? c.discountPct() : 0,
                    c.nextLevelLabel(), c.toNextLevel(), c.expiresOn(), prog.isEnabled() && a.getCenter().isActive()));
        }
        return out;
    }

    /** Bot: /ball buyrug'i. */
    @Transactional(readOnly = true)
    public String botText(List<Car> list) {
        StringBuilder sb = new StringBuilder();
        for (Car car : list) {
            List<PublicCard> cards = publicCards(car);
            if (cards.isEmpty()) continue;
            sb.append("🚗 <b>").append(html(prettyPlate(car.getPlateNumber()))).append("</b>\n");
            for (PublicCard c : cards) {
                sb.append("\n💳 <b>").append(html(c.centerName())).append("</b> · karta ").append(c.cardDisplay()).append("\n")
                        .append("   ").append(c.levelIcon()).append(" ").append(c.levelLabel())
                        .append(" · balans <b>").append(points(c.balance())).append("</b>\n");
                if (!c.active()) {
                    sb.append("   Dastur hozir to'xtatilgan\n");
                    continue;
                }
                sb.append("   Keshbek ").append(c.cashbackPct()).append("%")
                        .append(c.discountPct() > 0 ? ", chegirma " + c.discountPct() + "%" : "").append("\n");
                if (c.nextLevelLabel() != null) {
                    sb.append("   ").append(c.nextLevelLabel()).append(" darajagacha: ").append(money(c.toNextLevel())).append("\n");
                }
                if (c.expiresOn() != null && c.balance() > 0) {
                    sb.append("   Ballar ").append(date(c.expiresOn())).append(" gacha amal qiladi\n");
                }
            }
            sb.append("\n");
        }
        return sb.isEmpty()
                ? "Sizda hali jamg'arma karta yo'q.\nSadoqat dasturi bor avtoservisda ish qildirsangiz, karta avtomatik ochiladi."
                : "🎁 <b>Jamg'arma kartalarim</b>\n\n" + sb.toString().trim() + "\n\n1 ball = 1 so'm. Ballarni servisda to'lovda ishlatish mumkin.";
    }

    // =====================================================================
    //  Yordamchi
    // =====================================================================

    private LoyaltyAccount open(LoyaltyProgram prog, Car car, String actor, boolean welcome) {
        ServiceCenter center = prog.getCenter();
        LoyaltyAccount acc = new LoyaltyAccount(center, car, newCardNumber(), today(), actor);
        long spend = payments.findAllForCar(center, car).stream()
                .filter(x -> x.getMethod().isMoney()).mapToLong(Payment::getAmount).sum();
        acc.setSpendTotal(spend);
        acc.setLevel(prog.levelFor(spend));
        accounts.save(acc);
        long bonus = welcome ? prog.getWelcomeBonus() : 0;
        if (bonus > 0) credit(acc, LoyaltyTransaction.Type.WELCOME, bonus, null, "Karta ochildi", actor);
        LoyaltyLevel l = acc.getLevel();
        send(acc, "💳 <b>" + html(center.getName()) + "</b> jamg'arma kartangiz ochildi!\n\n"
                + "Karta raqami: <b>" + prettyCard(acc.getCardNumber()) + "</b>\n"
                + "Daraja: " + l.icon() + " " + l.label() + "\n"
                + (bonus > 0 ? "🎁 Sovg'a: <b>" + points(bonus) + "</b>\n" : "")
                + "\nHar to'lovdan " + prog.cashbackPct(l) + "% ball qaytadi"
                + (prog.discountPct(l) > 0 ? ", doimiy chegirma " + prog.discountPct(l) + "%" : "") + ".\n"
                + "1 ball = 1 so'm — keyingi ishlarda to'lovga ishlatiladi.\n\nBalansni ko'rish: /ball");
        return acc;
    }

    private void credit(LoyaltyAccount acc, LoyaltyTransaction.Type type, long pts, ServiceRecord r, String note, String actor) {
        acc.change(pts);
        acc.addEarned(pts);
        txs.save(new LoyaltyTransaction(acc, type, pts, r, note, actor));
    }

    private void activity(LoyaltyAccount acc) {
        acc.setLastActivity(today());
        acc.setExpiryWarnedOn(null);
    }

    private void notifyLevelUp(LoyaltyAccount acc, LoyaltyProgram prog) {
        LoyaltyLevel l = acc.getLevel();
        send(acc, "🎉 <b>Tabriklaymiz!</b> Siz <b>" + html(prog.getCenter().getName()) + "</b> da "
                + l.icon() + " <b>" + l.label() + "</b> darajaga ko'tarildingiz.\n\n"
                + "Endi har to'lovdan <b>" + prog.cashbackPct(l) + "%</b> ball qaytadi"
                + (prog.discountPct(l) > 0 ? " va barcha ishlarga <b>" + prog.discountPct(l) + "%</b> chegirma" : "") + ".");
    }

    private void send(LoyaltyAccount acc, String text) {
        Long chat = acc.getCar().getTelegramChatId();
        if (chat != null) telegram.sendMessage(chat, text);
    }

    private LoyaltyLevel levelOf(LoyaltyProgram prog, ServiceCenter center, Car car) {
        if (car == null || car.getId() == null) return LoyaltyLevel.BRONZE;
        LoyaltyAccount acc = account(center, car).orElse(null);
        if (acc != null) return acc.getLevel();
        long spend = payments.findAllForCar(center, car).stream()
                .filter(x -> x.getMethod().isMoney()).mapToLong(Payment::getAmount).sum();
        return prog.levelFor(spend);
    }

    private boolean blacklisted(ServiceCenter center, Car car) {
        return car != null && car.getId() != null
                && notes.findByCenterAndCar(center, car).map(CustomerNote::isBlacklisted).orElse(false);
    }

    private String newCardNumber() {
        for (int i = 0; i < 50; i++) {
            String n = String.valueOf(10_000_000 + random.nextInt(90_000_000));
            if (!accounts.existsByCardNumber(n)) return n;
        }
        throw new IllegalStateException("Karta raqami yaratib bo'lmadi");
    }

    private static boolean matches(LoyaltyAccount a, String q) {
        if (q == null || q.isBlank()) return true;
        Car c = a.getCar();
        String text = q.trim().toLowerCase(Locale.ROOT);
        String plate = q.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        String digits = q.replaceAll("\\D", "");
        return c.getFullName().toLowerCase(Locale.ROOT).contains(text)
                || (!plate.isEmpty() && c.getPlateNumber().contains(plate))
                || (digits.length() >= 3 && (a.getCardNumber().contains(digits) || c.getPhone().contains(digits)));
    }

    CardDto card(LoyaltyAccount a, LoyaltyProgram prog, LocalDate today) {
        Car car = a.getCar();
        LoyaltyLevel l = a.getLevel();
        LoyaltyLevel next = l.next();
        Long toNext = null;
        int progress = 100;
        if (next != null) {
            long from = prog.levelFrom(l), to = prog.levelFrom(next);
            toNext = Math.max(0, to - a.getSpendTotal());
            progress = to <= from ? 100 : (int) Math.max(0, Math.min(100, (a.getSpendTotal() - from) * 100 / (to - from)));
        }
        LocalDate expires = prog.getExpiryDays() > 0 && a.getBalance() > 0 ? a.getLastActivity().plusDays(prog.getExpiryDays()) : null;
        return new CardDto(a.getId(), car.getId(), a.getCardNumber(), prettyCard(a.getCardNumber()),
                car.getFullName().trim(), prettyPlate(car.getPlateNumber()), prettyPhone(car.getPhone()),
                car.getTelegramChatId() != null,
                l.name(), l.label(), l.icon(), prog.cashbackPct(l), prog.discountPct(l),
                a.getBalance(), a.getEarnedTotal(), a.getRedeemedTotal(), a.getExpiredTotal(), a.getSpendTotal(),
                next == null ? null : next.label(), toNext, progress,
                a.getLastActivity(), expires, a.getCreatedAt());
    }

    static TxDto txDto(LoyaltyTransaction t) {
        Car car = t.getAccount().getCar();
        return new TxDto(t.getId(), t.getCreatedAt(), t.getType().name(), t.getType().label(), t.getPoints(), t.getBalanceAfter(),
                t.getRecord() == null ? null : t.getRecord().displayName(), t.getNote(), t.getActorName(),
                car.getId(), car.getFullName().trim(), prettyPlate(car.getPlateNumber()));
    }

    private ProgramDto programDto(LoyaltyProgram prog) {
        List<LevelDto> levels = Arrays.stream(LoyaltyLevel.values())
                .map(l -> new LevelDto(l.name(), l.label(), l.icon(), prog.levelFrom(l), prog.cashbackPct(l), prog.discountPct(l)))
                .toList();
        return new ProgramDto(prog.isEnabled(), levels, prog.getMaxRedeemPct(), prog.getWelcomeBonus(),
                prog.getBirthdayBonus(), prog.getExpiryDays(), prog.getUpdatedBy(), prog.getUpdatedAt());
    }

    private LoyaltyProgram programOrDefault(ServiceCenter center) {
        return programs.findByCenter(center).orElseGet(() -> new LoyaltyProgram(center));
    }

    private LoyaltyProgram requireEnabled(ServiceCenter center) {
        LoyaltyProgram prog = activeProgram(center);
        if (prog == null) throw ApiException.badRequest("Avval sadoqat dasturini yoqing (Sadoqat bo'limi)");
        return prog;
    }

    /** Mijoz shu servisniki: ish kiritilgan, qo'lda qo'shilgan yoki kartasi bor. */
    private Car carOfCenter(ServiceCenter center, Long carId) {
        Car car = cars.findById(carId).orElseThrow(() -> ApiException.notFound("Mijoz topilmadi"));
        if (records.existsByCarAndCenter(car, center) || notes.findByCenterAndCar(center, car).isPresent()
                || accounts.findByCenterAndCar(center, car).isPresent()) {
            return car;
        }
        throw ApiException.notFound("Mijoz topilmadi");
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
