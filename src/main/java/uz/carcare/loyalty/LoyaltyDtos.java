package uz.carcare.loyalty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import uz.carcare.model.LoyaltyLevel;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Sadoqat dasturi so'rov va javoblari. 1 ball = 1 so'm. */
public final class LoyaltyDtos {

    private LoyaltyDtos() {
    }

    // ---------- Sozlamalar ----------

    public record LevelDto(String code, String label, String icon, long from, int cashbackPct, int discountPct) {
    }

    public record ProgramDto(boolean enabled, List<LevelDto> levels, int maxRedeemPct, long welcomeBonus,
                             long birthdayBonus, int expiryDays, String updatedBy, Instant updatedAt) {
    }

    public record LevelInput(
            @NotNull(message = "Darajani ko'rsating") LoyaltyLevel level,
            @NotNull(message = "Daraja chegarasini kiriting") @PositiveOrZero(message = "Chegara manfiy bo'lmasin")
            @Max(value = 100_000_000_000L, message = "Chegara juda katta") Long from,
            @NotNull(message = "Keshbek foizini kiriting") @Min(value = 0, message = "Keshbek 0–30% bo'lsin")
            @Max(value = 30, message = "Keshbek 0–30% bo'lsin") Integer cashbackPct,
            @NotNull(message = "Chegirma foizini kiriting") @Min(value = 0, message = "Chegirma 0–50% bo'lsin")
            @Max(value = 50, message = "Chegirma 0–50% bo'lsin") Integer discountPct
    ) {
    }

    public record ProgramRequest(
            @NotNull Boolean enabled,
            @NotNull @Size(min = 4, max = 4, message = "To'rtta daraja bo'lishi kerak") List<@Valid LevelInput> levels,
            @NotNull @Min(value = 0, message = "Ball bilan to'lash ulushi 0–100% bo'lsin")
            @Max(value = 100, message = "Ball bilan to'lash ulushi 0–100% bo'lsin") Integer maxRedeemPct,
            @NotNull @PositiveOrZero(message = "Bonus manfiy bo'lmasin") @Max(value = 10_000_000, message = "Bonus juda katta") Long welcomeBonus,
            @NotNull @PositiveOrZero(message = "Bonus manfiy bo'lmasin") @Max(value = 10_000_000, message = "Bonus juda katta") Long birthdayBonus,
            @NotNull @Min(value = 0, message = "Kuyish muddati manfiy bo'lmasin")
            @Max(value = 3650, message = "Kuyish muddati 10 yildan oshmasin") Integer expiryDays
    ) {
    }

    // ---------- Karta va harakatlar ----------

    public record CardDto(
            Long accountId, Long carId, String cardNumber, String cardDisplay,
            String ownerName, String plateDisplay, String phoneDisplay, boolean telegramConnected,
            String level, String levelLabel, String levelIcon, int cashbackPct, int discountPct,
            long balance, long earnedTotal, long redeemedTotal, long expiredTotal, long spendTotal,
            String nextLevelLabel, Long toNextLevel, int progressPct,
            LocalDate lastActivity, LocalDate expiresOn, Instant createdAt
    ) {
    }

    public record TxDto(Long id, Instant at, String type, String typeLabel, long points, long balanceAfter,
                        String serviceName, String note, String actorName,
                        Long carId, String ownerName, String plateDisplay) {
    }

    /** Mijoz profilidagi sadoqat bloki. */
    public record CustomerLoyalty(boolean programEnabled, boolean blacklisted, int maxRedeemPct,
                                  CardDto card, List<TxDto> transactions) {
    }

    /**
     * Ish kiritish formasi uchun (mashina raqami yozilganda).
     * willOpen — karta hali yo'q, ish saqlanganda ochiladi (welcomeBonus bilan).
     */
    public record LookupLoyalty(boolean programEnabled, boolean blacklisted, boolean willOpen,
                                String cardDisplay, String level, String levelLabel, String levelIcon,
                                long balance, int discountPct, int cashbackPct, int maxRedeemPct, long welcomeBonus) {
    }

    // ---------- Sadoqat bo'limi ----------

    public record Stats(int cards, int withBalance, long balanceTotal,
                        long earnedMonth, long redeemedMonth, long expiredMonth, int newCardsMonth) {
    }

    public record Overview(ProgramDto program, Stats stats, Map<String, Integer> levelCounts,
                           int total, List<CardDto> members, List<TxDto> recent) {
    }

    public record AdjustRequest(
            @NotNull(message = "Ball miqdorini kiriting") @Min(value = -100_000_000, message = "Miqdor juda katta")
            @Max(value = 100_000_000, message = "Miqdor juda katta") Long points,
            @NotBlank(message = "Sababini yozing") @Size(max = 200, message = "Sabab juda uzun") String note
    ) {
    }

    public record IssueAllRequest(boolean withWelcome) {
    }

    public record IssueAllResult(int created, String message) {
    }

    /** Mijoz sahifasi (car.html) va bot uchun. */
    public record PublicCard(String centerName, String cardDisplay, String levelLabel, String levelIcon,
                             long balance, int cashbackPct, int discountPct,
                             String nextLevelLabel, Long toNextLevel, LocalDate expiresOn, boolean active) {
    }
}
