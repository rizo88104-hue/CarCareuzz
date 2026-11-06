package uz.carcare.admin;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Qarz daftari uchun so'rov va javoblar. Barcha summalar so'mda. */
public final class DebtDtos {

    private DebtDtos() {
    }

    public enum DebtStatus { UNPAID, PARTIAL, PAID }

    public enum DebtFilter { ALL, OVERDUE, UNPAID, PARTIAL, PAID }

    /** Ish sanasi bo'yicha davr. */
    public enum Period { ALL, YEAR, MONTH, WEEK, TODAY }

    public record DebtRow(
            Long recordId, Long carId, String plateDisplay, String ownerName, String phone, String phoneDisplay,
            boolean telegramConnected, String serviceName, LocalDate doneDate, String masterName,
            long price, long paid, long remaining, LocalDate payDueDate, DebtStatus status, long overdueDays,
            /** Sadoqat chegirmasi va ball bilan to'langan qism (so'mda). */
            long discount, long bonusPaid
    ) {
    }

    /**
     * debtors — qarzi bor mijozlar (mashinalar) soni;
     * overdue/unpaid/partial/paid — ishlar soni;
     * totalDebt — qarzga qilingan ishlar summasi, totalPaid — ular bo'yicha to'langan, totalRemaining — qoldiq.
     */
    public record DebtSummary(int debtors, int overdue, int unpaid, int partial, int paid,
                              long totalDebt, long totalPaid, long totalRemaining) {
    }

    public record DebtList(DebtSummary summary, Map<DebtFilter, Integer> counts, int total, List<DebtRow> rows) {
    }

    /** Usta kiritgan ishga keyin narx qo'yish. */
    public record PriceRequest(
            @NotNull(message = "Narxni kiriting") @Positive(message = "Narx noldan katta bo'lsin") Long price,
            LocalDate payDueDate,
            /** Sadoqat chegirmasini qo'llash (null — ha). */
            Boolean applyDiscount
    ) {
    }
}
