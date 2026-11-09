package com.example.processautomation.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class OperatorPageController {
    private final OperatorLoginService login;

    public OperatorPageController(OperatorLoginService login) {
        this.login = login;
    }

    @GetMapping("/")
    public String home(HttpServletRequest request) {
        return login.isAuthenticated(request.getSession(false)) ? "forward:/index.html" : "redirect:/login.html";
    }

    @GetMapping("/login")
    public String loginPage(HttpServletRequest request) {
        return login.isAuthenticated(request.getSession(false)) ? "redirect:/" : "forward:/login.html";
    }
}
