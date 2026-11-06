package uz.carcare.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** /car/{token} manzilini mashina sahifasiga yo'naltiradi. */
@Controller
public class PageController {

    @GetMapping("/car/{token}")
    public String carPage() {
        return "forward:/car.html";
    }

    @GetMapping("/admin")
    public String adminPage() {
        return "forward:/admin.html";
    }
}
