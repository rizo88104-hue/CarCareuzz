package uz.carcare.admin;

import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uz.carcare.admin.CustomerDtos.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * Mijozlar bazasi API.
 *   GET   /api/admin/center/customers?q=&filter=   — ro'yxat (direktor, administrator)
 *   GET   /api/admin/center/customers/{id}         — mijoz kartasi (direktor, administrator)
 *   PATCH /api/admin/center/customers/{id}         — ism, telefon, izohni o'zgartirish (direktor, administrator)
 *   GET   /api/admin/center/customers/export       — Excel uchun CSV (faqat direktor)
 *   GET   /api/admin/super/customers?q=            — butun tizim bo'yicha qidiruv (super admin)
 */
@RestController
@RequestMapping("/api/admin")
public class CustomerController {

    private static final String AUTH = HttpHeaders.AUTHORIZATION;

    private final AdminAuthService auth;
    private final CustomerBaseService service;

    public CustomerController(AdminAuthService auth, CustomerBaseService service) {
        this.auth = auth;
        this.service = service;
    }

    @GetMapping("/center/customers")
    public CustomerList list(@RequestHeader(value = AUTH, required = false) String h,
                             @RequestParam(required = false) String q,
                             @RequestParam(required = false) Filter filter) {
        return service.list(auth.requireContact(h), q, filter);
    }

    @GetMapping("/center/customers/export")
    public ResponseEntity<byte[]> export(@RequestHeader(value = AUTH, required = false) String h) {
        byte[] body = service.exportCsv(auth.requireDirector(h));
        String name = "mijozlar-" + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .body(body);
    }

    @GetMapping("/center/customers/{id}")
    public CustomerDetail detail(@RequestHeader(value = AUTH, required = false) String h, @PathVariable Long id) {
        return service.detail(auth.requireContact(h), id);
    }

    @PatchMapping("/center/customers/{id}")
    public CustomerDetail update(@RequestHeader(value = AUTH, required = false) String h, @PathVariable Long id,
                                 @Valid @RequestBody CustomerUpdate req) {
        return service.update(auth.requireContact(h), id, req);
    }

    /** Mijozni bazaga qo'lda qo'shish. */
    @PostMapping("/center/customers")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public CustomerDetail create(@RequestHeader(value = AUTH, required = false) String h,
                                 @Valid @RequestBody NewCustomerRequest req) {
        return service.create(auth.requireContact(h), req);
    }

    /** Qora ro'yxatga qo'shish yoki chiqarish (faqat direktor). */
    @PatchMapping("/center/customers/{id}/blacklist")
    public CustomerDetail blacklist(@RequestHeader(value = AUTH, required = false) String h, @PathVariable Long id,
                                    @Valid @RequestBody BlacklistRequest req) {
        return service.setBlacklist(auth.requireDirector(h), id, req);
    }

    /** Mijozga Telegram bot orqali xabar. */
    @PostMapping("/center/customers/{id}/message")
    public java.util.Map<String, Boolean> message(@RequestHeader(value = AUTH, required = false) String h, @PathVariable Long id,
                                                  @Valid @RequestBody MessageRequest req) {
        return java.util.Map.of("ok", service.sendMessage(auth.requireContact(h), id, req));
    }

    /** «Qarzni so'ndirish»: summa mijozning ochiq qarzlariga taqsimlanadi (avval eng eski muddatli). */
    @PostMapping("/center/customers/{id}/pay")
    public CustomerDetail pay(@RequestHeader(value = AUTH, required = false) String h, @PathVariable Long id,
                              @Valid @RequestBody AdminDtos.PaymentRequest req) {
        return service.payDebts(auth.requireContact(h), id, req);
    }

    @GetMapping("/super/customers")
    public GlobalCustomerList global(@RequestHeader(value = AUTH, required = false) String h,
                                     @RequestParam(required = false) String q) {
        auth.requireSuper(h);
        return service.globalSearch(q);
    }
}
