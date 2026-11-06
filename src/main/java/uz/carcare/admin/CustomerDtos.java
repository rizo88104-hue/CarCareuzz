package uz.carcare.admin;

import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Mijozlar bazasi uchun so'rov va javoblar. */
public final class CustomerDtos {

    private CustomerDtos() {
    }

    /**
     * Mijoz holati (servis nuqtai nazaridan):
     *   OVERDUE — muddati o'tgan, hali kelmagan;
     *   SOON    — 7 kun ichida kelishi kerak;
     *   LOST    — keyingi ishni boshqa joyda qildirgan;
     *   OK      — hammasi joyida.
     */
    public enum Status { OVERDUE, SOON, LOST, OK }

    /**
     * Ro'yxatdagi filtrlar.
     * ACTIVE — oxirgi 180 kunda kelgan, INACTIVE — 180 kundan beri kelmagan yoki hali kelmagan,
     * REPEAT — 2 va undan ko'p marta kelgan, BIRTHDAY — tug'ilgan kuni shu oyda.
     */
    public enum Filter { ALL, ACTIVE, INACTIVE, REPEAT, OVERDUE, SOON, NO_TELEGRAM, LOST, NEW, BLACKLIST, BIRTHDAY }

    public record CustomerRow(
            Long id, String plate, String plateDisplay, String ownerName, String phone, String phoneDisplay,
            boolean telegramConnected, int visits, int jobs, LocalDate firstVisit, LocalDate lastVisit,
            String nextService, LocalDate nextDate, Long daysLeft, Status status, String note,
            LocalDate birthDate, boolean blacklisted, String blacklistReason,
            /** Sadoqat kartasi: daraja (BRONZE...), belgisi va ball balansi. Karta yo'q bo'lsa null. */
            String loyaltyLevel, String loyaltyIcon, Long points
    ) {
    }

    /** Mijozni qo'lda qo'shish. */
    public record NewCustomerRequest(
            @jakarta.validation.constraints.NotBlank(message = "Mashina raqamini kiriting") String plateNumber,
            @jakarta.validation.constraints.NotBlank(message = "Ismni kiriting") @Size(max = 60) String firstName,
            @Size(max = 60) String lastName,
            @jakarta.validation.constraints.NotBlank(message = "Telefonni kiriting") String phone,
            LocalDate birthDate,
            String gender,
            @Size(max = 60) String source,
            @Size(max = 500) String note
    ) {
    }

    public record BlacklistRequest(boolean blacklisted, @Size(max = 200, message = "Sabab 200 belgidan oshmasin") String reason) {
    }

    public record MessageRequest(
            @jakarta.validation.constraints.NotBlank(message = "Xabar matnini yozing")
            @Size(max = 1000, message = "Xabar 1000 belgidan oshmasin") String text
    ) {
    }

    /** total — filtrdan keyin nechta; counts — har bir filtr bo'yicha soni (tugmalardagi raqamlar). */
    public record CustomerList(int total, Map<Filter, Integer> counts, List<CustomerRow> rows) {
    }

    public record HistoryItem(
            Long id, String serviceName, LocalDate doneDate, LocalDate nextDate, Integer mileage,
            String masterName, String note, boolean active,
            /** Narx (null — kiritilmagan), to'langan va qoldiq, so'mda. */
            Long price, long paid, long remaining,
            /** Sadoqat chegirmasi va ball bilan to'langan qism. */
            long discount, long bonusPaid
    ) {
    }

    /** Profildagi qarz kartalari. */
    public record DebtTotals(long totalDebt, long paid, long remaining, long maxOverdueDays, int openDebts) {
    }

    /** Profildagi statistika: jami xarajat va o'rtacha chek (narx kiritilgan ishlar bo'yicha). */
    public record SpendTotals(long totalSpent, long averageCheck, int pricedJobs) {
    }

    /** To'lovlar tarixi (tranzaksiyalar). */
    public record PaymentRow(Long id, Instant createdAt, long amount, String methodLabel, String serviceName,
                             String receivedBy, String note) {
    }

    /** Mashinaning hozirgi faol servislari (qaysi servis qilgani ko'rsatilmaydi — faqat "sizda" yoki yo'q). */
    public record ActiveService(String serviceName, LocalDate nextDate, long daysLeft, boolean doneHere) {
    }

    public record CustomerDetail(
            CustomerRow customer, String carUrl, Instant registeredAt,
            String noteUpdatedBy, Instant noteUpdatedAt,
            List<HistoryItem> history, List<ActiveService> activeServices, int jobsElsewhere,
            // ---- profil ----
            String firstName, String lastName, LocalDate birthDate, String gender, String source,
            /** Mijozga yuboriladigan havola: bot sozlangan bo'lsa t.me/... , aks holda sahifa havolasi. */
            String telegramLink,
            DebtTotals debts, SpendTotals spend,
            List<DebtDtos.DebtRow> debtRows, List<PaymentRow> payments,
            String blacklistedBy, Instant blacklistedAt
    ) {
    }

    /**
     * Ism, familiya, telefon, izoh: null bo'lsa o'zgartirilmaydi.
     * profileEdit = true bo'lsa tug'ilgan kun, jinsi va manba kelganidek saqlanadi (null — tozalanadi).
     */
    public record CustomerUpdate(
            @Size(max = 60, message = "Ism juda uzun") String firstName,
            @Size(max = 60, message = "Familiya juda uzun") String lastName,
            String phone,
            @Size(max = 500, message = "Izoh 500 belgidan oshmasin") String note,
            LocalDate birthDate,
            String gender,
            @Size(max = 60, message = "Manba juda uzun") String source,
            boolean profileEdit
    ) {
    }

    // ---------- Super admin ----------

    public record GlobalCustomerRow(
            Long id, String plateDisplay, String ownerName, String phone, String phoneDisplay,
            boolean telegramConnected, Instant registeredAt, List<String> centers
    ) {
    }

    public record GlobalCustomerList(long totalCars, long telegramConnected, List<GlobalCustomerRow> rows) {
    }
}
