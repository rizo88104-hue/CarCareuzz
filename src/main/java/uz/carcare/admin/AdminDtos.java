package uz.carcare.admin;

import jakarta.validation.constraints.*;
import uz.carcare.model.Payment;
import uz.carcare.model.ServiceType;
import uz.carcare.model.StaffUser;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class AdminDtos {

    private AdminDtos() {
    }

    // ---------- So'rovlar ----------

    public record LoginRequest(String username, String password) {
    }

    public record PasswordRequest(String oldPassword, String newPassword) {
    }

    /** Super admin yangi servis qo'shadi: servis + uning direktori. */
    public record CenterRequest(
            @NotBlank(message = "Servis nomini kiriting") @Size(max = 100) String name,
            @Size(max = 200) String address,
            String phone,
            @NotBlank(message = "Direktor ismini kiriting") @Size(max = 80, message = "Ism juda uzun") String directorName,
            @NotBlank(message = "Login kiriting") @Pattern(regexp = "^[a-zA-Z0-9_.]{3,40}$",
                    message = "Login 3-40 ta lotin harfi, raqam, _ yoki . dan iborat bo'lsin") String username,
            String password
    ) {
    }

    public record CenterUpdateRequest(Boolean active, String newPassword, String name, String address, String phone) {
    }

    /** Direktor yangi xodim qo'shadi. */
    public record StaffRequest(
            @NotBlank(message = "Xodimning ismini kiriting") @Size(max = 80, message = "Ism juda uzun") String fullName,
            @NotBlank(message = "Login kiriting") @Pattern(regexp = "^[a-zA-Z0-9_.]{3,40}$",
                    message = "Login 3-40 ta lotin harfi, raqam, _ yoki . dan iborat bo'lsin") String username,
            String password,
            @NotNull(message = "Rolni tanlang") StaffUser.Role role
    ) {
    }

    public record StaffUpdateRequest(Boolean blocked, String newPassword) {
    }

    /** Servis xodimi ish kiritadi. Mashina ro'yxatda bo'lmasa, egasining ma'lumotlari ham kerak. */
    public record AdminRecordRequest(
            @NotBlank(message = "Mashina raqamini kiriting") String plateNumber,
            @Size(max = 60) String firstName,
            @Size(max = 60) String lastName,
            String phone,
            @NotNull(message = "Servis turini tanlang") ServiceType type,
            @Size(max = 80) String customName,
            @NotNull(message = "Bajarilgan sanani kiriting") LocalDate doneDate,
            @NotNull(message = "Keyingi almashtirish sanasini kiriting") LocalDate nextDate,
            @PositiveOrZero Integer mileage,
            @PositiveOrZero Integer nextMileage,
            @Size(max = 300) String note,
            /** Ishni qilgan usta (direktor/administrator tanlaydi). Usta uchun e'tiborga olinmaydi. */
            Long masterId,
            boolean notifyReady,
            /** Narx, so'mda. Usta uchun e'tiborga olinmaydi. */
            @PositiveOrZero(message = "Narx manfiy bo'lmasin") Long price,
            /** Hozir to'langan summa. Narxdan kam bo'lsa, qolgani qarzga yoziladi. */
            @PositiveOrZero(message = "To'lov manfiy bo'lmasin") Long paid,
            Payment.Method paymentMethod,
            /** Qarzni to'lash muddati (qarz bo'lsa majburiy). */
            LocalDate payDueDate,
            /** Sadoqat chegirmasini qo'llash (null — ha). price — chegirmagacha bo'lgan narx. */
            Boolean applyDiscount,
            /** Bonus ball bilan to'lanadigan summa. */
            @PositiveOrZero(message = "Ball manfiy bo'lmasin") Long bonus
    ) {
    }

    /** Qarzni so'ndirish (to'lov qabul qilish). */
    public record PaymentRequest(
            @NotNull(message = "Summani kiriting") @Positive(message = "Summa noldan katta bo'lsin") Long amount,
            Payment.Method method,
            @Size(max = 200) String note
    ) {
    }

    public record PaymentDto(Long id, long amount, String method, String methodLabel, String receivedBy,
                             String note, Instant createdAt) {
    }

    // ---------- Javoblar ----------

    public record MeDto(String username, String fullName, String role, String roleLabel, String centerName) {
    }

    /** Telefon raqami faqat direktor va administratorga ko'rsatiladi (ustaga null). */
    public record CarLookupDto(
            boolean exists, String plateNumber, String plateDisplay,
            String firstName, String lastName, String phone, String phoneDisplay,
            boolean telegramConnected, List<String> activeServices,
            /** Mijoz shu servisning qora ro'yxatida bo'lsa — sababi, aks holda null. */
            String blacklistReason,
            /** Sadoqat kartasi (dastur yo'q yoki usta uchun null). */
            uz.carcare.loyalty.LoyaltyDtos.LookupLoyalty loyalty
    ) {
    }

    public record AdminRecordDto(
            Long id, String plateDisplay, String ownerName, String phone, String phoneDisplay,
            boolean telegramConnected, String serviceName, LocalDate doneDate, LocalDate nextDate,
            long daysLeft, Integer mileage, String note, String masterName, boolean active,
            Instant readyNotifiedAt, String carUrl,
            /** Narx, to'langan va qoldiq (so'mda). Ustaga null. */
            Long price, Long paid, Long remaining, LocalDate payDueDate,
            /** Sadoqat chegirmasi va ball bilan to'langan summa. Ustaga null. */
            Long discount, Long bonusPaid,
            /** Mijoz (mashina) id — profil va sadoqat kartasi uchun. Ustaga null. */
            Long carId
    ) {
    }

    public record SaveResult(AdminRecordDto record, boolean carCreated, Boolean readyDelivered, String message) {
    }

    public record ReadyResult(boolean delivered, String phoneDisplay, String phone, String message) {
    }

    public record StaffBrief(Long id, String fullName) {
    }

    /**
     * Servis paneli ma'lumotlari. Ustaga faqat o'z ishlari beriladi:
     * stats = null, callList bo'sh, telefonlar yashirilgan.
     */
    public record CenterDashboard(
            String centerName, String role, CenterStats stats,
            List<AdminRecordDto> callList, List<AdminRecordDto> recent, List<StaffBrief> masters
    ) {
    }

    /**
     * Avtoservis statistikasi.
     * due       — kelish vaqti kelgan yozuvlar (muddati o'tgan yoki allaqachon yangilangan);
     * returned  — shu servisga qaytib kelgan;
     * elsewhere — boshqa joyda qildirgan (boshqa servis yoki egasi o'zi kiritgan);
     * missing   — muddati o'tgan, hali hech qayerda qilinmagan.
     */
    public record CenterStats(
            int totalRecords, int customers, int last30Days,
            int due, int returned, int elsewhere, int missing,
            int telegramConnected, Integer returnRatePct, LocalDate lastActivity
    ) {
    }

    public record CenterDto(
            Long id, String name, String address, String phoneDisplay,
            String directorName, String directorUsername, long staffCount,
            boolean active, String status, Integer inactiveDays, CenterStats stats
    ) {
    }

    public record SuperOverview(int centers, int activeCenters, int totalRecords, Integer returnRatePct,
                                List<CenterDto> list) {
    }

    /** Qaytib kelmagan mijoz va sababi. */
    public record MissedDto(
            String plateDisplay, String ownerName, String phone, String phoneDisplay,
            String serviceName, LocalDate nextDate, long daysOverdue, String reason, String hint
    ) {
    }

    public record StaffDto(Long id, String fullName, String username, String role, String roleLabel,
                           boolean blocked, Instant createdAt) {
    }

    public record LogDto(Instant at, String actorName, String actorRole, String centerName, String action) {
    }
}
