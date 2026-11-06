package uz.carcare.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Barcha xatoliklarni {"error": "..."} ko'rinishida qaytaradi. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> api(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getDefaultMessage())
                .orElse("Ma'lumotlar noto'g'ri");
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "Ma'lumotlar formati noto'g'ri"));
    }

    /** Ikki xodim bir vaqtda bir narsani o'zgartirdi (masalan, bir kartadan ball yechish). */
    @ExceptionHandler({org.springframework.dao.OptimisticLockingFailureException.class,
            org.springframework.dao.DataIntegrityViolationException.class})
    public ResponseEntity<Map<String, String>> conflict(Exception e) {
        log.warn("Bir vaqtdagi o'zgarish: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Ma'lumot shu payt boshqa xodim tomonidan o'zgartirildi. Sahifani yangilab, qayta urinib ko'ring."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> other(Exception e) {
        // Spring ning o'z xatolari (404 sahifa, noto'g'ri metod va h.k.) — o'z statusi bilan
        if (e instanceof ErrorResponse er) {
            return ResponseEntity.status(er.getStatusCode()).body(Map.of("error", "So'rov bajarilmadi"));
        }
        log.error("Kutilmagan xato", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Serverda xatolik yuz berdi. Keyinroq urinib ko'ring."));
    }
}
