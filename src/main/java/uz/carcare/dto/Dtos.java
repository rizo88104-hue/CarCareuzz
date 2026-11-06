package uz.carcare.dto;

import jakarta.validation.constraints.*;
import uz.carcare.model.ServiceType;

import java.time.LocalDate;
import java.util.List;

/** API orqali keladigan va qaytadigan ma'lumotlar. */
public final class Dtos {

    private Dtos() {
    }

    // ---------- So'rovlar ----------

    public record RegisterRequest(
            @NotBlank(message = "Ismni kiriting") @Size(max = 60, message = "Ism juda uzun") String firstName,
            @NotBlank(message = "Familiyani kiriting") @Size(max = 60, message = "Familiya juda uzun") String lastName,
            @NotBlank(message = "Telefon raqamni kiriting") String phone,
            @NotBlank(message = "Mashina raqamini kiriting") String plateNumber
    ) {
    }

    public record SettingsRequest(Boolean smsEnabled) {
    }

    public record RecordRequest(
            @NotNull(message = "Servis turini tanlang") ServiceType type,
            @Size(max = 80, message = "Nom juda uzun") String customName,
            @NotNull(message = "Almashtirilgan sanani kiriting") LocalDate doneDate,
            @NotNull(message = "Keyingi almashtirish sanasini kiriting") LocalDate nextDate,
            @PositiveOrZero(message = "Probeg manfiy bo'lmasin") Integer mileage,
            @PositiveOrZero(message = "Probeg manfiy bo'lmasin") Integer nextMileage,
            @Size(max = 300, message = "Izoh juda uzun") String note
    ) {
    }

    // ---------- Javoblar ----------

    public record RegisterResponse(String token, String carUrl, boolean existing) {
    }

    public record RecordDto(
            Long id,
            ServiceType type,
            String name,
            LocalDate doneDate,
            LocalDate nextDate,
            long daysLeft,
            Integer mileage,
            Integer nextMileage,
            String note,
            boolean active
    ) {
    }

    public record CarDto(
            String token,
            String firstName,
            String lastName,
            String phone,
            String plateNumber,
            String plateDisplay,
            boolean telegramConnected,
            String telegramLink,
            boolean smsEnabled,
            boolean smsAvailable,
            int reminderDaysBefore,
            List<RecordDto> records,
            /** Servislardagi jamg'arma kartalari (sadoqat dasturi). */
            List<uz.carcare.loyalty.LoyaltyDtos.PublicCard> loyalty
    ) {
    }

    public record ServiceTypeDto(String code, String label) {
    }
}
