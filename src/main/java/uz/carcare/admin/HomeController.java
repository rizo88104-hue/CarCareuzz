package uz.carcare.admin;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/admin/center/home — servis bosh sahifasi (direktor va administrator). */
@RestController
@RequestMapping("/api/admin/center")
public class HomeController {

    private final AdminAuthService auth;
    private final HomeService service;

    public HomeController(AdminAuthService auth, HomeService service) {
        this.auth = auth;
        this.service = service;
    }

    @GetMapping("/home")
    public HomeService.Home home(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String h) {
        return service.home(auth.requireContact(h));
    }
}
