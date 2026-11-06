package uz.carcare.web;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.carcare.admin.AuditService;
import uz.carcare.config.AppProperties;
import uz.carcare.config.TelegramProperties;
import uz.carcare.dto.Dtos.*;
import uz.carcare.loyalty.LoyaltyService;
import uz.carcare.model.Car;
import uz.carcare.model.ServiceRecord;
import uz.carcare.model.ServiceType;
import uz.carcare.reminder.NotificationService;
import uz.carcare.reminder.ReminderService;
import uz.carcare.repo.CarRepository;
import uz.carcare.repo.ServiceRecordRepository;
import uz.carcare.sms.SmsService;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static uz.carcare.util.Formats.*;

@Service
public class CarService {

    private final CarRepository cars;
    private final ServiceRecordRepository records;
    private final ReminderService reminders;
    private final NotificationService notifications;
    private final SmsService sms;
    private final AppProperties app;
    private final TelegramProperties telegram;
    private final AuditService audit;
    private final LoyaltyService loyalty;

    public CarService(CarRepository cars, ServiceRecordRepository records, ReminderService reminders,
                      NotificationService notifications, SmsService sms,
                      AppProperties app, TelegramProperties telegram, AuditService audit, LoyaltyService loyalty) {
        this.loyalty = loyalty;
        this.audit = audit;
        this.cars = cars;
        this.records = records;
        this.reminders = reminders;
        this.notifications = notifications;
        this.sms = sms;
        this.app = app;
        this.telegram = telegram;
    }

    /**
     * Ro'yxatdan o'tkazish. Agar bu raqam shu telefon bilan oldin ro'yxatdan
     * o'tgan bo'lsa — mavjud sahifani qaytaradi (qayta kirish).
     */
    @Transactional
    public RegisterResponse register(RegisterRequest req) {
        String phone = normalizePhone(req.phone());
        String plate = normalizePlate(req.plateNumber());

        var existing = cars.findByPlateNumber(plate);
        if (existing.isPresent()) {
            Car car = existing.get();
            if (!car.getPhone().equals(phone)) {
                throw ApiException.conflict("Bu mashina raqami boshqa telefon raqam bilan ro'yxatdan o'tgan.");
            }
            car.setFirstName(trim(req.firstName()));
            car.setLastName(trim(req.lastName()));
            return new RegisterResponse(car.getToken(), app.carUrl(car.getToken()), true);
        }

        Car car = new Car();
        car.setToken(newToken());
        car.setFirstName(trim(req.firstName()));
        car.setLastName(trim(req.lastName()));
        car.setPhone(phone);
        car.setPlateNumber(plate);
        cars.save(car);
        audit.system(car.getFullName(), "Mijoz", null, "Saytda ro'yxatdan o'tdi: " + prettyPlate(plate));
        return new RegisterResponse(car.getToken(), app.carUrl(car.getToken()), false);
    }

    @Transactional(readOnly = true)
    public CarDto get(String token) {
        return toDto(find(token));
    }

    @Transactional
    public CarDto updateSettings(String token, SettingsRequest req) {
        Car car = find(token);
        if (req.smsEnabled() != null) car.setSmsEnabled(req.smsEnabled());
        return toDto(car);
    }

    @Transactional
    public CarDto addRecord(String token, RecordRequest req) {
        Car car = find(token);
        LocalDate today = LocalDate.now(app.zoneId());

        if (req.nextDate().isBefore(req.doneDate())) {
            throw ApiException.badRequest("Keyingi sana almashtirilgan sanadan oldin bo'lishi mumkin emas");
        }
        if (req.doneDate().isAfter(today)) {
            throw ApiException.badRequest("Almashtirilgan sana kelajakda bo'lishi mumkin emas");
        }
        if (req.type() == ServiceType.OTHER && (req.customName() == null || req.customName().isBlank())) {
            throw ApiException.badRequest("\"Boshqa\" turi uchun nomini yozing");
        }

        // Shu turdagi oldingi faol yozuvni arxivga o'tkazamiz ("Boshqa" turi bundan mustasno)
        if (req.type() != ServiceType.OTHER) {
            records.findAllByCarAndTypeAndActiveTrue(car, req.type()).forEach(old -> old.markReplaced(null));
        }

        ServiceRecord r = new ServiceRecord();
        r.setCar(car);
        r.setType(req.type());
        r.setCustomName(req.type() == ServiceType.OTHER ? trim(req.customName()) : null);
        r.setDoneDate(req.doneDate());
        r.setNextDate(req.nextDate());
        r.setMileage(req.mileage());
        r.setNextMileage(req.nextMileage());
        r.setNote(trim(req.note()));
        records.save(r);

        reminders.checkNow(r); // muddat yaqin bo'lsa darhol eslatadi
        return toDto(car);
    }

    @Transactional
    public CarDto deleteRecord(String token, Long id) {
        Car car = find(token);
        ServiceRecord r = records.findByIdAndCar(id, car)
                .orElseThrow(() -> ApiException.notFound("Yozuv topilmadi"));
        records.delete(r);
        return toDto(car);
    }

    /** Sozlamalarni tekshirish uchun sinov xabari. */
    @Transactional(readOnly = true)
    public boolean sendTest(String token) {
        Car car = find(token);
        if (car.getTelegramChatId() == null && !(car.isSmsEnabled() && sms.enabled())) {
            throw ApiException.badRequest("Avval Telegram botga ulaning yoki SMS ni yoqing");
        }
        boolean ok = notifications.notify(car,
                "✅ Sinov xabari. Eslatmalar shu yerga keladi.\n🚗 " + html(prettyPlate(car.getPlateNumber())),
                "Sinov xabari: eslatmalar shu raqamga keladi.");
        if (!ok) throw ApiException.badRequest("Xabar yuborilmadi. Sozlamalarni tekshiring.");
        return true;
    }

    // ---------- Telegram bot uchun ----------

    /** Token bo'yicha mashina raqami (yo'q bo'lsa null). */
    @Transactional(readOnly = true)
    public String plateByToken(String token) {
        return cars.findByToken(token).map(Car::getPlateNumber).orElse(null);
    }

    /**
     * Telegram'dan kelgan raqam ro'yxatdan o'tgan raqamga mos kelsa,
     * mashinani shu chatga bog'laydi. Bu SMS kodsiz, bepul tasdiqlash.
     */
    @Transactional
    public Car linkTelegram(String token, long chatId, String telegramPhone) {
        Car car = find(token);
        String digits = telegramPhone == null ? "" : telegramPhone.replaceAll("\\D", "");
        if (!digits.equals(car.getPhone())) {
            throw ApiException.badRequest("Telegram raqamingiz saytda ro'yxatdan o'tgan raqamga mos kelmadi ("
                    + prettyPhone(car.getPhone()) + "). Saytda to'g'ri raqam bilan ro'yxatdan o'ting.");
        }
        car.setTelegramChatId(chatId);
        audit.system(car.getFullName(), "Mijoz", null, prettyPlate(car.getPlateNumber()) + " egasi Telegram botga ulandi");
        return car;
    }

    /** /holat — shu chatga ulangan mashinalar bo'yicha qisqa hisobot. */
    @Transactional(readOnly = true)
    public String statusText(long chatId) {
        List<Car> list = cars.findAllByTelegramChatId(chatId);
        if (list.isEmpty()) {
            return "Sizda ulangan mashina yo'q.\nSaytda ro'yxatdan o'ting va mashina sahifasidagi "
                    + "\"Telegramga ulash\" tugmasini bosing:\n" + app.baseUrl();
        }
        LocalDate today = LocalDate.now(app.zoneId());
        StringBuilder sb = new StringBuilder();
        for (Car car : list) {
            sb.append("🚗 <b>").append(html(prettyPlate(car.getPlateNumber()))).append("</b>\n");
            List<ServiceRecord> active = records.findAllByCarAndActiveTrueOrderByNextDateAsc(car);
            if (active.isEmpty()) {
                sb.append("   Hali servis yozuvi yo'q\n");
            }
            for (ServiceRecord r : active) {
                long d = ChronoUnit.DAYS.between(today, r.getNextDate());
                String left = d > 0 ? d + " kun qoldi" : d == 0 ? "bugun!" : (-d) + " kun o'tdi!";
                String icon = d <= 0 ? "🔴" : d <= app.reminderDaysBefore() ? "🟡" : "🟢";
                sb.append("   ").append(icon).append(" ").append(html(r.displayName()))
                        .append(" — ").append(date(r.getNextDate())).append(" (").append(left).append(")\n");
            }
            sb.append("   ").append(app.carUrl(car.getToken())).append("\n\n");
        }
        return sb.toString().trim();
    }

    /** /ball — shu chatga ulangan mashinalarning jamg'arma kartalari. */
    @Transactional(readOnly = true)
    public String loyaltyText(long chatId) {
        List<Car> list = cars.findAllByTelegramChatId(chatId);
        if (list.isEmpty()) {
            return "Sizda ulangan mashina yo'q.\nSaytda ro'yxatdan o'ting va mashina sahifasidagi "
                    + "\"Telegramga ulash\" tugmasini bosing:\n" + app.baseUrl();
        }
        return loyalty.botText(list);
    }

    // ---------- Yordamchi ----------

    private Car find(String token) {
        return cars.findByToken(token).orElseThrow(() -> ApiException.notFound("Mashina topilmadi"));
    }

    private CarDto toDto(Car car) {
        LocalDate today = LocalDate.now(app.zoneId());
        List<RecordDto> list = records.findAllByCarOrderByActiveDescNextDateAscIdDesc(car).stream()
                .map(r -> new RecordDto(
                        r.getId(), r.getType(), r.displayName(),
                        r.getDoneDate(), r.getNextDate(),
                        ChronoUnit.DAYS.between(today, r.getNextDate()),
                        r.getMileage(), r.getNextMileage(), r.getNote(), r.isActive()))
                .toList();
        return new CarDto(
                car.getToken(), car.getFirstName(), car.getLastName(),
                prettyPhone(car.getPhone()), car.getPlateNumber(), prettyPlate(car.getPlateNumber()),
                car.getTelegramChatId() != null, telegram.deepLink(car.getToken()),
                car.isSmsEnabled(), sms.enabled(), app.reminderDaysBefore(), list, loyalty.publicCards(car));
    }
}
